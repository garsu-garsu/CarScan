package com.bruni.carscan.core.monetization

/**
 * Platform port to the store's interstitial-ad SDK. Full-screen, shown at a natural break in
 * the flow (e.g. the user disconnecting from their car) — see [FullScreenAdGate] for the shared
 * cadence that decides *when*, and never call [show] without asking it first.
 */
interface InterstitialAdPort {
    /** Loads and holds one ad ready to show. Safe to call repeatedly. */
    fun preload()

    /** Shows the preloaded ad, if one is ready. Returns whether an ad was actually shown. */
    suspend fun show(): Boolean
}
