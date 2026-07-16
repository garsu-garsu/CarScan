package com.bruni.carscan.core.designsystem.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether the app may show ads at all. Defaults to `false`, so a [BannerAd] rendered outside the
 * composition root's provider — a stray preview, a screen someone forgot to wrap — shows nothing
 * rather than an ad no one gated.
 *
 * `:core:designsystem` has no dependency on `:core:monetization`, so this is the seam between
 * them: the root reads `Entitlements.isPremium` and provides `!isPremium` here, which is also
 * what makes a banner disappear the instant premium is granted.
 */
val LocalAdsEnabled = compositionLocalOf { false }

/** Google's public AdMob test banner unit — safe to load from any app, any account. */
const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"

/**
 * The AdMob ad unit id a [BannerAd] loads. Defaults to [TEST_BANNER_AD_UNIT_ID]; the root can
 * override it with a real id once one exists.
 */
val LocalBannerAdUnitId = staticCompositionLocalOf { TEST_BANNER_AD_UNIT_ID }

/**
 * An adaptive anchored banner ad, meant to be pinned to the bottom of a browsing/config screen
 * (Home, Connect, Garage, Trips) — never an active-driving screen (Dashboard, Live, HUD).
 *
 * Renders nothing unless [LocalAdsEnabled] is `true`. On Android this hosts a real AdMob
 * `AdView`; other platforms render nothing until an ad SDK is wired there.
 */
@Composable
expect fun BannerAd(modifier: Modifier = Modifier)
