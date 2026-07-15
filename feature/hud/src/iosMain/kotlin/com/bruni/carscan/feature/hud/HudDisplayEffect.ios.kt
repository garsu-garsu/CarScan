package com.bruni.carscan.feature.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

/**
 * **Never compiled.** Apple targets are only registered on a macOS host — see the note on
 * `:core:units`'s iOS `NumberFormatter` actual.
 *
 * Orientation lock is intentionally not attempted here: it needs either a scene-based override or
 * a UIViewController flag threaded through SwiftUI/UIKit interop, which is materially harder than
 * brightness + idle timer — and brightness + idle timer is enough for this skeleton.
 */
@Composable
actual fun HudDisplayEffect() {
    DisposableEffect(Unit) {
        val screen = UIScreen.mainScreen
        val originalBrightness = screen.brightness
        screen.brightness = 1.0
        UIApplication.sharedApplication.idleTimerDisabled = true

        // TODO: lock landscape orientation on iOS.

        onDispose {
            screen.brightness = originalBrightness
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}
