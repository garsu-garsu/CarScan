package com.bruni.carscan

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bruni.carscan.core.monetization.AppOpenAdPort
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.FullScreenAdGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows an app-open ad each time the app returns to the foreground — except the very first
 * time, which is cold start: [FullScreenAdGate]'s warmup already covers that window, and firing
 * here too would just be a second, redundant reason to skip it.
 *
 * Registered once against `ProcessLifecycleOwner` in [CarScanApplication], so `onStart` fires on
 * the *process* coming to the foreground — an Activity rotating or a dialog appearing over it
 * does not re-trigger this, only actually backgrounding the app and returning does.
 */
class AppOpenAdManager(
    private val appOpenAd: AppOpenAdPort,
    private val gate: FullScreenAdGate,
    private val entitlements: Entitlements,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private var isColdStart = true

    override fun onStart(owner: LifecycleOwner) {
        if (isColdStart) {
            isColdStart = false
            return
        }

        if (entitlements.isPremium.value || !gate.shouldShow()) return
        gate.record()
        scope.launch { appOpenAd.show() }
    }
}
