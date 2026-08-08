package com.bruni.carscan.feature.settings

import androidx.compose.runtime.Composable
import platform.Foundation.NSBundle

/**
 * **Never compiled.** Apple targets are only registered on a macOS host — see the note on
 * `:core:units`'s iOS `NumberFormatter` actual.
 */
@Composable
actual fun appVersionName(): String =
    NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "?"
