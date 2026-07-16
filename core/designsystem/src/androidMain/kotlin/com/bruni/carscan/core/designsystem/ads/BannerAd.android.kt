package com.bruni.carscan.core.designsystem.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * A real AdMob adaptive anchored banner. Sized from the current screen width so it always fills
 * the width it is given at the best height AdMob has for that width and orientation, rather than
 * a fixed 320x50 that wastes width on a tablet or wraps oddly on a narrow phone.
 */
@Composable
actual fun BannerAd(modifier: Modifier) {
    if (!LocalAdsEnabled.current) return

    val context = LocalContext.current
    val adUnitId = LocalBannerAdUnitId.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    AndroidView(
        modifier = modifier,
        factory = {
            AdView(context).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidthDp))
                setAdUnitId(adUnitId)
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}
