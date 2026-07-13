package com.bruni.carscan.core.vehicle

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.model.obdb.Signalset
import com.bruni.carscan.core.model.obdb.commandId
import com.bruni.carscan.core.model.obdb.matches

/** A command that applies to this vehicle-year, and whether OBDb has actually verified it. */
data class EffectiveCommand(
    val command: ObdbCommand,
    /** Unverified upstream. Poll it if you like, but never present it as fact. */
    val experimental: Boolean,
) {
    /** Header-qualified identity, e.g. "7E4.220101". */
    val id: String get() = command.commandId()
}

/** One signal, together with the command that has to be sent to obtain it. */
data class SignalSource(
    val command: EffectiveCommand,
    val signal: ObdbSignal,
)

/**
 * The commands that actually apply to one vehicle in one model year, indexed for the two
 * things the rest of the app does with them: poll ([commands]) and bind ([sourcesByKey]).
 *
 * A vehicle's OBDb repo holds ONLY that manufacturer's commands — Ford-F-150 has 116 commands
 * and not one of them is mode 01. The standard SAE J1979 PIDs (RPM, speed, coolant) live in a
 * separate repo, `OBDb/SAEJ1979`. Neither half is usable alone, so the union is the unit that
 * every caller works with, and it is built once here rather than re-derived at each call site.
 */
class EffectiveSignalset(
    val modelYear: Int,
    val commands: List<EffectiveCommand>,
    /**
     * Every signal, addressed the way a dashboard tile addresses it.
     *
     * Two properties of this map are load-bearing:
     *
     *  - **Every signal is indexed under [MetricKey.Signal], and additionally under
     *    [MetricKey.Metric] when OBDb gives it a `suggestedMetric`.** A metric-only index
     *    cannot address engine RPM, which has no `suggestedMetric` — so a metric-only index
     *    cannot address the single most important gauge in the app.
     *
     *  - **The value is a list, not a single source.** A 2023 EV6 reports state of charge four
     *    different ways (three of its own plus SAE's `BAT_SOC`), and SAEJ1979 itself defines
     *    `SHRTFT11` on two commands. Picking one and dropping the rest would be a policy
     *    decision — which sensor to trust — silently baked into a lookup table. Callers choose.
     */
    val sourcesByKey: Map<MetricKey, List<SignalSource>>,
) {
    operator fun get(key: MetricKey): List<SignalSource> = sourcesByKey[key].orEmpty()

    fun sourcesOf(metric: SuggestedMetric): List<SignalSource> = get(MetricKey.Metric(metric))

    fun sourcesOf(signalId: String): List<SignalSource> = get(MetricKey.Signal(signalId))

    companion object {
        /**
         * @param standard the parsed `OBDb/SAEJ1979` signalset — the mode-01 PIDs every
         *   OBD-II vehicle answers, which no vehicle repo contains.
         * @param vehicle the parsed signalset for this vehicle, already resolved to the right
         *   model-year variant file by [SignalsetVariant.select].
         */
        fun of(standard: Signalset, vehicle: Signalset, modelYear: Int): EffectiveSignalset {
            val commands = (standard.commands + vehicle.commands)
                .filter { it.filter.matches(modelYear) }
                .map { command ->
                    EffectiveCommand(
                        command = command,
                        // `dbgfilter` must be null-checked BEFORE calling matches(): a null
                        // YearFilter matches every year, which is right for `filter` (absent
                        // means "all years") and catastrophic for `dbgfilter` (absent means
                        // "never unverified"). Unguarded, all 103 SAEJ1979 commands — none of
                        // which has a dbgfilter — come back flagged experimental, and the flag
                        // stops meaning anything.
                        experimental = command.dbg ||
                            (command.dbgfilter != null && command.dbgfilter.matches(modelYear)),
                    )
                }

            val sourcesByKey = buildMap<MetricKey, MutableList<SignalSource>> {
                for (command in commands) {
                    for (signal in command.command.signals) {
                        val source = SignalSource(command, signal)
                        getOrPut(MetricKey.Signal(signal.id)) { mutableListOf() } += source
                        signal.suggestedMetric?.let { metric ->
                            getOrPut(MetricKey.Metric(metric)) { mutableListOf() } += source
                        }
                    }
                }
            }

            return EffectiveSignalset(
                modelYear = modelYear,
                commands = commands,
                sourcesByKey = sourcesByKey.mapValues { (_, sources) -> sources.toList() },
            )
        }
    }
}
