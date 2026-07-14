package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.commandId
import com.bruni.carscan.core.model.obdb.spec
import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.Exchanger
import com.bruni.carscan.core.obd.session.AtStateCache
import com.bruni.carscan.core.obd.session.applyContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The adaptive PID scheduler: it decides what to ask the adapter next, and it is honest about
 * what it cannot ask.
 *
 * **The problem here is not speed, it is arithmetic.** A counterfeit ELM327 answers 10–20
 * queries per second *in total*. Eight gauges at 10 Hz need 80. No scheduler closes that gap,
 * so this one is built to fail gracefully and to say so: it groups commands by ECU so the
 * adapter's own bookkeeping stops eating the budget, it slows down what nobody is looking at
 * before it slows down what they are, it never queues work it cannot do, and it publishes
 * [health] so the UI can say *"your adapter: 14 queries/sec — showing 6 tiles at 2 Hz"*
 * instead of showing six gauges that silently lag a second behind the car.
 *
 * **One coroutine.** [run] is the whole engine. ELM327 is half-duplex — exactly one command
 * may be outstanding, ever — so a second coroutine could not do any real work here. It could
 * only queue, and an unbounded queue of stale requests is precisely the failure this class
 * exists to prevent.
 */
class PidScheduler(
    private val exchanger: Exchanger,
    private val cache: AtStateCache,
    private val clock: PollClock = PollClock.system(),
    private val config: PollConfig = PollConfig(),
) {

    /** The addressing a command needs. Two commands with equal contexts need no AT between them. */
    private data class AtContext(
        val hdr: String,
        val rax: String?,
        val eax: String?,
        val flowControl: Boolean,
    )

    private fun ObdbCommand.atContext() = AtContext(
        hdr = hdr.uppercase(),
        rax = rax?.uppercase(),
        eax = eax?.uppercase(),
        flowControl = fcm1,
    )

    /**
     * One command's place in the plan.
     *
     * The declared period is never mutated. Two multipliers ride on top of it — [stretch] from
     * the governor and [backoff] from failure memory — kept separate because they are undone by
     * different events. Folded into one number, a command that recovered from a `NO DATA` would
     * silently inherit the governor's slowdown forever.
     */
    private class Scheduled(val entry: PollEntry, val context: AtContext) {
        val command: ObdbCommand = entry.command
        val id: String = command.commandId()
        val keys: Set<MetricKey> = command.metricKeys()

        var dueAt: Long = 0
        var stretch: Int = 1
        var backoff: Int = 1
        var failures: Int = 0
        var promoted: Boolean = false
        var dropped: Boolean = false
        var learnedFrames: Int? = null

        val priority: Priority get() = if (promoted) Priority.CRITICAL else entry.priority
        val effectivePeriodMs: Long get() = entry.periodMs * stretch * backoff
    }

    private sealed interface Control {
        class Submit(val entries: List<PollEntry>) : Control
        class Visible(val keys: Set<MetricKey>) : Control
    }

    /**
     * [submit] and [setVisible] are called from the UI; the plan is owned by [run]'s coroutine.
     * A channel is how the two meet without a lock — and without a lock there is no way to
     * mutate the plan halfway through a batch.
     */
    private val control = Channel<Control>(Channel.UNLIMITED)
    private val plan = mutableListOf<Scheduled>()
    private val decoder = PollDecoder()

    private var ewmaRttMs = 0.0
    private var servedCycles = 0L
    private var skippedCycles = 0L
    private var lastKeepAliveMs = Long.MIN_VALUE

    private val _samples = MutableSharedFlow<SensorSample>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Decoded readings, in the unit the car reports them in.
     *
     * No replay, and the oldest is dropped under back-pressure: a gauge that has fallen behind
     * wants the newest value, not the backlog. Replaying to a late subscriber would draw it a
     * chart of the past and call it live.
     */
    val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _health = MutableStateFlow(PollerHealth())
    val health: StateFlow<PollerHealth> = _health.asStateFlow()

    private val _unsupported = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Commands this vehicle refused to answer until we stopped asking, as `"7E0.0142"`.
     *
     * **The repository must persist these against the vehicle.** A signalset describes every
     * trim and model year, so a given car says `NO DATA` to a good fraction of it — and
     * re-probing a PID the car does not have costs a round trip every session, out of the
     * fifteen a second we have.
     */
    val unsupported: StateFlow<Set<String>> = _unsupported.asStateFlow()

    private val _capabilities = MutableStateFlow(AdapterCapabilities())

    /** **Persist against the adapter, not the vehicle** — this is a property of the hardware. */
    val capabilities: StateFlow<AdapterCapabilities> = _capabilities.asStateFlow()

    fun submit(entries: List<PollEntry>) {
        control.trySend(Control.Submit(entries))
    }

    /** Promotes what is on screen to [Priority.CRITICAL], and demotes what scrolled off it. */
    fun setVisible(keys: Set<MetricKey>) {
        control.trySend(Control.Visible(keys))
    }

    /** The one coroutine. Runs until cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            drainControl()
            tick()
        }
    }

    // --- the loop ------------------------------------------------------------

    private suspend fun tick() {
        val now = clock.nowMs()
        val active = plan.filterNot { it.dropped }
        val due = active.filter { it.dueAt <= now }

        if (due.isEmpty()) {
            idle(active.minOfOrNull { it.dueAt } ?: (now + config.keepAliveIntervalMs), now)
            return
        }
        runEntry(next(due))
    }

    /**
     * The next command to run — AT affinity lives here, and it is a matter of *ordering*, not
     * batching.
     *
     * [applyContext] already sends nothing when the adapter is in the right state, so consecutive
     * commands on the same ECU cost one `ATSH` between them however they are grouped. What costs
     * a header is *alternating*: engine, battery, engine, battery is six headers and six filters
     * on top of six reads, where engine, engine, engine, battery, battery, battery is two and
     * two. On an adapter that manages fifteen exchanges a second, that ratio *is* the frame rate
     * of the dashboard. So the only thing needed is to prefer, among the commands that are due,
     * one the adapter is already configured for. (Bundling them into a batch object and running
     * them back to back would save no AT command at all — it would only fire commands that are
     * not yet due, early, out of a budget that has nothing to spare.)
     *
     * **The preference is deliberately bounded, and the bound is the whole correctness of this
     * method.** Preferring the current context outright starves every other ECU the moment the
     * adapter saturates: the engine's four gauges are then permanently overdue, they always share
     * the current header, and the battery module on `7E4` is never selected again — not slowly,
     * *never*. (It happened, and the emulator caught it: zero state-of-charge readings in twenty
     * seconds.) So affinity may only choose between commands due within
     * [PollConfig.affinityWindowMs] of the most overdue one. A command can be passed over for a
     * cheaper header, but only by ~150 ms, and never indefinitely.
     */
    private fun next(due: List<Scheduled>): Scheduled {
        val mostOverdue = due.minWith(compareBy({ it.priority.ordinal }, { it.dueAt }))
        return due
            .filter {
                it.priority == mostOverdue.priority &&
                    it.dueAt <= mostOverdue.dueAt + config.affinityWindowMs
            }
            .minWith(compareBy({ if (it.context == appliedContext) 0 else 1 }, { it.dueAt }))
    }

    /** What the last [applyContext] put the adapter into, or null if the cache was invalidated. */
    private val appliedContext: AtContext?
        get() = cache.header?.let { header ->
            AtContext(
                hdr = header.uppercase(),
                rax = cache.rxFilter?.uppercase(),
                eax = cache.extAddr?.uppercase(),
                flowControl = cache.fc != null,
            )
        }

    private suspend fun runEntry(scheduled: Scheduled) {
        val startedAt = clock.nowMs()
        val period = scheduled.effectivePeriodMs

        // Lateness. Past two periods, the missed cycles are *dropped*, not caught up. Firing
        // them back to back would spend the adapter's whole budget re-reading values that are
        // already seconds stale, while the reading the user is looking at waits behind them.
        val lateBy = startedAt - scheduled.dueAt
        if (lateBy > config.latenessPeriods * period) {
            skippedCycles += lateBy / period
            scheduled.dueAt = startedAt
        }

        exchanger.applyContext(scheduled.command, cache)
        exchange(scheduled)

        scheduled.dueAt += scheduled.effectivePeriodMs
        publishHealth()
    }

    private suspend fun exchange(scheduled: Scheduled) {
        val ascii = scheduled.command.spec().request
        val hint = frameHint(scheduled)

        var response = exchanger.exchange(ElmRequest(ascii, expectedFrames = hint))
        if (hint != null && response is ElmResponse.Err && response.kind.rejectsFrameCount()) {
            // `?` or `BUFFER FULL` in reply to `010C1` is the *adapter* saying it never learnt
            // the frame-count suffix — not the *car* saying it lacks the PID. Read it the other
            // way and five perfectly good commands get struck off the plan for good.
            _capabilities.value = _capabilities.value.copy(expectedFrames = false)
            response = exchanger.exchange(ElmRequest(ascii))
        }

        when (response) {
            is ElmResponse.Ok -> {
                scheduled.failures = 0
                scheduled.backoff = 1
                servedCycles++
                observeRtt(response.roundTrip.inWholeMilliseconds)

                decoder.decode(scheduled.command, response.lines, clock.epochMs())
                    .forEach { _samples.tryEmit(it) }

                // The frame count is only knowable from an answer that arrived. Guessing it low
                // truncates a multi-frame reply into something that still decodes — wrongly.
                if (scheduled.command.rax != null && decoder.lastFrameCount > 0) {
                    scheduled.learnedFrames = decoder.lastFrameCount
                }
                if (scheduled.entry.priority == Priority.ONESHOT) scheduled.dropped = true
                regovern()
            }

            is ElmResponse.Err -> when (response.kind) {
                ElmErrorKind.NO_DATA, ElmErrorKind.QUESTION_MARK -> fail(scheduled)

                // The adapter reset itself, or the prompt stream is no longer trustworthy. Every
                // belief about its AT registers is now a guess, and a stale header attributes one
                // ECU's answer to another command.
                ElmErrorKind.LV_RESET, ElmErrorKind.ACT_ALERT,
                ElmErrorKind.DESYNC, ElmErrorKind.STOPPED,
                -> cache.invalidate()

                // Transient bus trouble; the Exchanger already retried. Nothing to remember.
                else -> Unit
            }
        }
    }

    /** Back off x1, x4, x16 — and after five, the car does not have it and will not tomorrow. */
    private fun fail(scheduled: Scheduled) {
        scheduled.failures++
        scheduled.backoff = when (scheduled.failures) {
            1 -> 1
            2 -> 4
            else -> 16
        }
        if (scheduled.failures >= config.maxConsecutiveFailures) {
            scheduled.dropped = true
            _unsupported.value = _unsupported.value + scheduled.id
            regovern()
        }
    }

    private fun frameHint(scheduled: Scheduled): Int? =
        if (_capabilities.value.expectedFrames && scheduled.command.rax != null) {
            scheduled.learnedFrames
        } else {
            null
        }

    private fun ElmErrorKind.rejectsFrameCount(): Boolean =
        this == ElmErrorKind.QUESTION_MARK || this == ElmErrorKind.BUFFER_FULL

    // --- the idle slot -------------------------------------------------------

    /**
     * Nothing is due. Keep the link warm, then sleep until something is.
     *
     * Idle time is not free time. An ELM327 left silent long enough has its BLE connection
     * dropped by the phone, and an ECU held in a non-default diagnostic session falls back out
     * of it after five seconds of silence (the UDS S3 timer) — which turns the next multi-frame
     * read into a `NO DATA` that looks like an unsupported PID and gets the command struck off
     * the plan. So the idle slot spends a round trip on the cheapest thing that prevents both,
     * and on nothing else: the plan is never busy-polled.
     */
    private suspend fun idle(nextDueAt: Long, now: Long) {
        // Re-read every pass, never cached: ElmSession clears this map on adapter recovery, and a
        // stale copy would keep sending tester-present into a session that no longer exists.
        val sessions = cache.ecuSession
        val interval =
            if (sessions.isEmpty()) config.keepAliveIntervalMs else config.testerPresentIntervalMs

        // The link was last used a moment ago; the first keep-alive is due one interval from here.
        if (lastKeepAliveMs == Long.MIN_VALUE) lastKeepAliveMs = now
        val keepAliveAt = lastKeepAliveMs + interval

        if (keepAliveAt <= now) {
            keepAlive(sessions)
            return
        }
        delay((minOf(nextDueAt, keepAliveAt) - now).coerceAtLeast(1))
    }

    private suspend fun keepAlive(sessions: Map<String, String>) {
        lastKeepAliveMs = clock.nowMs()
        if (sessions.isEmpty()) {
            // The cheapest thing an ELM327 answers, and the car never even hears it.
            exchanger.exchange(ElmRequest("ATRV"))
            return
        }
        for (hdr in sessions.keys.toList()) {
            if (!hdr.equals(cache.header, ignoreCase = true)) {
                if (exchanger.exchange(ElmRequest("ATSH $hdr")) !is ElmResponse.Ok) {
                    cache.invalidate()
                    return
                }
                cache.header = hdr
            }
            exchanger.exchange(ElmRequest("3E00"))
        }
    }

    // --- the governor --------------------------------------------------------

    private fun drainControl() {
        var changed = false
        while (true) {
            when (val message = control.tryReceive().getOrNull()) {
                null -> {
                    if (changed) {
                        regovern()
                        publishHealth()
                    }
                    return
                }

                is Control.Submit -> {
                    val now = clock.nowMs()
                    message.entries.forEach { entry ->
                        plan += Scheduled(entry, entry.command.atContext()).also { it.dueAt = now }
                    }
                    changed = true
                }

                is Control.Visible -> {
                    plan.forEach { it.promoted = it.keys.any(message.keys::contains) }
                    changed = true
                }
            }
        }
    }

    private fun observeRtt(rttMs: Long) {
        val sample = rttMs.coerceAtLeast(1).toDouble()
        ewmaRttMs = if (ewmaRttMs == 0.0) {
            sample
        } else {
            config.rttAlpha * sample + (1 - config.rttAlpha) * ewmaRttMs
        }
    }

    private fun capacityHz(): Double = if (ewmaRttMs <= 0.0) 0.0 else 1000.0 / ewmaRttMs

    /** What we are asking for, at the periods the signalset declared. */
    private fun demandHz(): Double = plan.filterNot { it.dropped }.sumOf { 1000.0 / it.entry.periodMs }

    /** What we are asking for right now, after stretching. */
    private fun scheduledHz(): Double =
        plan.filterNot { it.dropped }.sumOf { 1000.0 / it.effectivePeriodMs }

    /**
     * Fits the plan to the adapter that is actually plugged in.
     *
     * Above 80% of measured capacity the adapter is the bottleneck, so periods double —
     * BACKGROUND first, then NORMAL, and **never CRITICAL**, because CRITICAL is what the user
     * is looking at right now.
     *
     * When CRITICAL alone exceeds capacity there is nothing left to give. The plan then stays as
     * declared, the reads simply come out late, cycles are skipped, and [health] reports the
     * shortfall. Stretching the visible gauges instead would *hide* the adapter's limit by making
     * the whole dashboard uniformly slower — a laggy dashboard that claims to be live. An honest
     * number beats that, which is the entire point of this milestone.
     */
    private fun regovern() {
        plan.forEach { it.stretch = 1 }
        val capacity = capacityHz()
        if (capacity <= 0.0) return

        val budget = capacity * config.targetUtilization
        for (klass in listOf(Priority.BACKGROUND, Priority.NORMAL)) {
            while (scheduledHz() > budget) {
                val victims = plan.filter {
                    !it.dropped && it.priority == klass && it.stretch < MAX_STRETCH
                }
                if (victims.isEmpty()) break
                victims.forEach { it.stretch *= 2 }
            }
        }
    }

    private fun publishHealth() {
        val cycles = servedCycles + skippedCycles
        _health.value = PollerHealth(
            capacityHz = capacityHz(),
            loadHz = demandHz(),
            meanRttMs = ewmaRttMs,
            dropRatePct = if (cycles == 0L) 0.0 else 100.0 * skippedCycles / cycles,
            stretchedCount = plan.count { !it.dropped && it.stretch > 1 },
        )
    }

    private companion object {
        /** Past 1/64 of the declared rate a poll is not slow any more, it is off. */
        const val MAX_STRETCH = 64
    }
}
