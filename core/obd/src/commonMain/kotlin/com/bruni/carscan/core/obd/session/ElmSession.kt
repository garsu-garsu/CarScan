package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.ElmState
import com.bruni.carscan.core.obd.Exchanger
import com.bruni.carscan.core.transport.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.time.Duration

/**
 * The half-duplex core.
 *
 * **The invariant: exactly one command is outstanding, ever.** Not "usually", not "except
 * while initializing" — ever. An ELM327 delimits responses with nothing but a `>` prompt,
 * so a second write issued before the first answer has been read does not interleave, it
 * *shifts*: every response from that moment on is attributed to the request before it.
 * The symptom is not an exception. The symptom is a coolant temperature on the tachometer,
 * and it lasts for the rest of the session.
 *
 * One [Mutex] enforces it, and it is the reason this class exists. Everything else here —
 * framing, retries, recovery — is what you are allowed to do *after* you have guaranteed
 * that.
 */
class ElmSession(
    private val transport: ObdTransport,
    private val scope: CoroutineScope,
    private val config: ElmSessionConfig = ElmSessionConfig(),
) : Exchanger {

    /** The invariant, in one field. */
    private val mutex = Mutex()

    /**
     * Raw bytes from the reader coroutine.
     *
     * Unbounded on purpose: a dropped chunk is a dropped response, and a dropped response
     * desynchronizes everything after it. Better to grow than to lose.
     */
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Framing lives here and not in the reader, so that only the coroutine holding [mutex]
     * ever touches it. That is what makes it safe without a lock of its own.
     */
    private val framer = PromptFramer()

    /** Responses that completed with nothing outstanding. Their existence *is* the desync. */
    private val orphans = ArrayDeque<String>()

    private val facts = AdapterFacts()

    /** AT commands this adapter answered `?` to. Asking again costs a round trip and gets `?`. */
    private val unsupported = mutableSetOf<String>()

    private var reader: Job? = null
    private var timeoutStreak = 0
    private var lastEchoed = false

    /** True while the init ladder runs. The ladder *is* the recovery, so it must not trigger one. */
    private var inLadder = false

    private val _state = MutableStateFlow<ElmState>(ElmState.Disconnected)
    val state: StateFlow<ElmState> = _state.asStateFlow()

    /**
     * What the adapter's AT registers hold.
     *
     * Both an input and an output: seed [AtStateCache.protocol] from a persisted
     * [AdapterInfo] before [connect] and the initializer skips the protocol search
     * entirely; read it back afterwards to persist what was found.
     */
    val atCache = AtStateCache()

    /** Everything learned about this adapter so far. A snapshot — it keeps changing. */
    val adapterInfo: AdapterInfo get() = facts.snapshot()

    /** The initializer runs inside the lock, so it cannot go back through [exchange], which takes it. */
    private val io = object : ElmIo {
        override suspend fun exchange(request: ElmRequest) = doExchange(request)
        override suspend fun drain() = drainTransport()
        override val lastEchoed: Boolean get() = this@ElmSession.lastEchoed
        override val facts: AdapterFacts get() = this@ElmSession.facts
    }

    override suspend fun exchange(request: ElmRequest): ElmResponse =
        mutex.withLock { doExchange(request) }

    suspend fun connect(): AdapterInfo = mutex.withLock {
        _state.value = ElmState.Initializing
        transport.open()
        startReading()

        inLadder = true
        try {
            ElmInitializer(io, config).initialize(atCache)
            _state.value = ElmState.Ready
        } catch (failure: ElmInitFailure) {
            _state.value = ElmState.Failed(failure.message ?: "initialization failed")
            throw failure
        } finally {
            inLadder = false
        }
        facts.snapshot()
    }

    suspend fun close() {
        reader?.cancel()
        reader = null
        transport.close()
        _state.value = ElmState.Disconnected
    }

    /**
     * Collect before the first write.
     *
     * `incoming` is cold and single-consumer. Subscribing after the write loses whatever
     * arrived in between — and on a half-duplex link, one lost response shifts every
     * answer that follows it, for the rest of the session.
     */
    private fun startReading() {
        reader = scope.launch {
            transport.incoming.collect { chunks.send(it) }
        }
    }

    // --- one exchange, without the lock (callers must already hold it) -------

    private suspend fun doExchange(request: ElmRequest): ElmResponse {
        if (!inLadder) {
            staleBytes()?.let { stale ->
                // Something answered with nothing outstanding, so the prompt stream is
                // ahead of us and every later answer would belong to the wrong request.
                // There is no way to reason past this, so we do not try.
                recover()
                return ElmResponse.Err(ElmErrorKind.DESYNC, stale)
            }
        }

        val atWord = atWordOf(request.ascii)
        if (atWord != null && atWord in unsupported) {
            return ElmResponse.Err(ElmErrorKind.QUESTION_MARK, "?")
        }

        var attempts = 0
        var current = request
        while (true) {
            attempts++
            val response = attempt(current)
            val canRetry = attempts <= request.retries

            if (response !is ElmResponse.Err) {
                timeoutStreak = 0
                return response
            }

            when (response.kind) {
                ElmErrorKind.TIMEOUT -> {
                    // The adapter may still be mid-sentence. Writing the next command into
                    // that is what turns a slow reply into a permanently shifted stream.
                    drainTransport()
                    timeoutStreak++
                    if (timeoutStreak >= TIMEOUTS_BEFORE_RESET) {
                        timeoutStreak = 0
                        recover()
                        return response
                    }
                    if (canRetry) continue
                    return response
                }

                ElmErrorKind.BUFFER_FULL -> {
                    facts.shrinkAfterBufferFull()
                    if (canRetry) {
                        current = current.copy(expectedFrames = null)
                        continue
                    }
                    return response
                }

                ElmErrorKind.QUESTION_MARK -> {
                    // `?` to `010C1` does not mean the adapter cannot read `010C`. It means
                    // it never learnt the frame-count suffix. Retire the suffix, keep the
                    // command — dropping the command would silently lose a gauge.
                    if (current.expectedFrames != null && facts.supportsExpectedFrames) {
                        facts.supportsExpectedFrames = false
                        current = current.copy(expectedFrames = null)
                        continue
                    }
                    if (atWord != null) {
                        unsupported += atWord
                        if (atWord == AT_RX_FILTER) facts.supportsRxFilter = false
                    }
                    return response
                }

                ElmErrorKind.CAN_ERROR, ElmErrorKind.BUS_BUSY -> {
                    if (canRetry) continue
                    recover()
                    return response
                }

                // A brown-out on the OBD port: the adapter reset itself and every AT
                // register we set is gone. It will answer the next command from the
                // default header, without complaining, and the number will look fine.
                ElmErrorKind.LV_RESET, ElmErrorKind.ACT_ALERT -> {
                    recover()
                    return response
                }

                ElmErrorKind.STOPPED -> {
                    config.onAssert(
                        "STOPPED while sending '${current.ascii}' — the adapter was still " +
                            "talking when we wrote to it. Something exchanged outside the mutex.",
                    )
                    recover()
                    return response
                }

                // NO_DATA is the car saying it does not have that PID. It is not a fault,
                // and reconnecting will not conjure the sensor into existence: back off.
                // The bus errors are the car's problem, not the adapter's, so a reset
                // would only cost a second and change nothing.
                else -> return response
            }
        }
    }

    private suspend fun attempt(request: ElmRequest): ElmResponse {
        val ascii = wireFormOf(request)
        val started = config.timeSource.markNow()

        write(ascii)
        val raw = awaitPrompt(request.timeout)
        if (raw == null) {
            lastEchoed = false
            return ElmResponse.Err(ElmErrorKind.TIMEOUT, "")
        }

        val reply = parseElmResponse(raw, ascii, started.elapsedNow())
        lastEchoed = reply.echoed
        return reply.response
    }

    /**
     * `010C` plus the frame count is `010C1`, which tells the adapter to answer the moment
     * it has that many frames instead of waiting out its timeout — roughly double the
     * throughput on a clone. Suppressed once the adapter has told us it cannot do it.
     */
    private fun wireFormOf(request: ElmRequest): String {
        val frames = request.expectedFrames
        if (frames == null || !facts.supportsExpectedFrames) return request.ascii
        return request.ascii + frames.toString(16).uppercase()
    }

    private suspend fun write(ascii: String) {
        val bytes = (ascii + '\r').encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + facts.maxWriteChunk, bytes.size)
            transport.write(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun awaitPrompt(timeout: Duration): String? = withTimeoutOrNull(timeout) {
        var response: String? = null
        while (response == null) {
            val completed = framer.feed(chunks.receive())
            if (completed.isNotEmpty()) {
                response = completed.first()
                // Two prompts for one command. The extras were asked for by nobody.
                orphans += completed.drop(1)
            }
        }
        response
    }

    /** Whatever is on the wire before we have written anything. There should be nothing. */
    private suspend fun staleBytes(): String? {
        // Let the reader have its turn first: bytes that arrived while we were idle must be
        // recognised as unsolicited *before* we write, not mistaken for the answer after.
        yield()
        while (true) {
            val chunk = chunks.tryReceive().getOrNull() ?: break
            orphans += framer.feed(chunk)
        }

        val leftover = framer.partial
        if (orphans.isEmpty() && leftover.isEmpty()) return null

        val stale = (orphans.toList() + leftover).filter { it.isNotEmpty() }.joinToString("\r")
        orphans.clear()
        framer.reset()
        return stale
    }

    /** Read and discard: whatever the adapter is still saying, we no longer want to hear it. */
    private suspend fun drainTransport() {
        withTimeoutOrNull(config.drainWindow) {
            while (true) framer.feed(chunks.receive())
        }
        orphans.clear()
        framer.reset()
    }

    private suspend fun recover() {
        if (inLadder) return
        inLadder = true
        _state.value = ElmState.Recovering
        try {
            drainTransport()
            ElmInitializer(io, config).recover(atCache)
            _state.value = ElmState.Ready
        } catch (failure: ElmInitFailure) {
            _state.value = ElmState.Failed(failure.message ?: "recovery failed")
        } finally {
            inLadder = false
        }
    }

    /**
     * The command word of an AT/ST command, so that a `?` to `ATCRA 7E8` also retires
     * `ATCRA 7EC`. The adapter is missing the command, not that particular argument.
     */
    private fun atWordOf(ascii: String): String? {
        val text = ascii.trim().uppercase()
        if (!text.startsWith("AT") && !text.startsWith("ST")) return null
        return text.substringBefore(' ')
    }

    private companion object {
        const val TIMEOUTS_BEFORE_RESET = 2
        const val AT_RX_FILTER = "ATCRA"
    }
}
