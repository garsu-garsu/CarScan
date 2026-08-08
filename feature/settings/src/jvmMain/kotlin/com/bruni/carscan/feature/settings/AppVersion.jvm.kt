package com.bruni.carscan.feature.settings

import androidx.compose.runtime.Composable

/**
 * No genuine JVM target consumes this today, and a desktop build has no APK/bundle version to
 * read — a constant is the honest answer here, same call `:feature:hud`'s JVM
 * `HudDisplayEffect` makes for its no-op.
 */
@Composable
actual fun appVersionName(): String = "1.0.0"
