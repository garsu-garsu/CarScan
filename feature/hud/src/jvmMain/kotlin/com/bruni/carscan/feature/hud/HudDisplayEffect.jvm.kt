package com.bruni.carscan.feature.hud

import androidx.compose.runtime.Composable

/**
 * No-op. There is no genuine JVM target consuming this today — this file exists only so
 * `:feature:hud`'s source-set layout mirrors `:core:units`'s exactly (commonMain + androidMain +
 * iosMain + this jvmMain).
 */
@Composable
actual fun HudDisplayEffect() {
}
