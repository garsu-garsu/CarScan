package com.bruni.carscan.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun appVersionName(): String {
    val context = LocalContext.current
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
