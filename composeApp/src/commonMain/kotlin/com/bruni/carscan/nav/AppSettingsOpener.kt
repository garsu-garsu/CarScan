package com.bruni.carscan.nav

/**
 * Takes the user to this app's permission settings.
 *
 * The other half of `ConnectFailure.BLUETOOTH_PERMISSION`. Classifying a refused permission
 * correctly is only worth doing if there is somewhere to send the user afterwards — and once
 * Android has decided the user means it, the in-app dialog never appears again, so system settings
 * is the *only* place the permission can still be granted.
 */
fun interface AppSettingsOpener {
    fun open()
}
