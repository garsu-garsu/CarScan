package com.bruni.carscan.feature.hud

import androidx.compose.runtime.Composable

/**
 * Puts the display into HUD condition for as long as this composable is part of the tree, and
 * restores it on dispose: screen kept on, brightness at max, and (Android only — see the iOS
 * actual) orientation locked to landscape.
 *
 * [HudScreen] calls this once, at its root, so the whole control is self-contained — nothing
 * outside `:feature:hud` has to wire it up.
 */
@Composable
expect fun HudDisplayEffect()
