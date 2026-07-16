package com.bruni.carscan.core.monetization

/**
 * The cadence product decided on for full-screen ads: two minutes of warmup after a session
 * starts, 75 seconds between any two full-screen ads, and a hard cap of four per session.
 */
data class AdCadenceConfig(
    val warmupMs: Long = 120_000,
    val minIntervalMs: Long = 75_000,
    val maxPerSession: Int = 4,
)

/**
 * The single gate every full-screen ad format — interstitial and app open alike — must ask
 * before showing, so the two can never fire back-to-back and share one cadence instead of each
 * enforcing its own.
 *
 * The session start is stamped once, at construction, so [shouldShow] answers `false` for
 * [AdCadenceConfig.warmupMs] no matter which format asks first. [clock] is injected — tests
 * drive it explicitly instead of sleeping; production binds it to `System.currentTimeMillis()`.
 * Bind one instance as a Koin singleton so every caller shares the same session state.
 */
class FullScreenAdGate(
    private val clock: () -> Long,
    private val config: AdCadenceConfig = AdCadenceConfig(),
) {
    private val sessionStart: Long = clock()
    private var lastShownMs: Long? = null
    private var shownThisSession: Int = 0

    /** Whether a full-screen ad may show right now. Does not itself count as a show — see [record]. */
    fun shouldShow(): Boolean {
        val now = clock()
        val warmedUp = now - sessionStart >= config.warmupMs
        val intervalElapsed = lastShownMs?.let { now - it >= config.minIntervalMs } ?: true
        val underCap = shownThisSession < config.maxPerSession
        return warmedUp && intervalElapsed && underCap
    }

    /** Call once an ad has actually been shown, so the next [shouldShow] respects the cadence. */
    fun record() {
        lastShownMs = clock()
        shownThisSession++
    }
}
