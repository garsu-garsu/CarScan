package com.bruni.carscan.core.monetization

/**
 * Platform port to the store's app-open-ad SDK. Shown when the app returns to the foreground —
 * see [FullScreenAdGate] for the shared cadence that decides *when*, and never call [show]
 * without asking it first.
 */
interface AppOpenAdPort {
    /** Loads and holds one ad ready to show. Safe to call repeatedly. */
    fun preload()

    /** Shows the preloaded ad, if one is ready. Returns whether an ad was actually shown. */
    suspend fun show(): Boolean
}
