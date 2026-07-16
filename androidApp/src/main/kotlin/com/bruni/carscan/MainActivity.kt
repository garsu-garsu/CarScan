package com.bruni.carscan

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.bruni.carscan.platform.android.ads.AdsConsent
import com.bruni.carscan.platform.android.ads.BuildConfig

class MainActivity : ComponentActivity() {

    /**
     * The result is deliberately ignored.
     *
     * A refusal is not handled here, because handling it here would mean handling it *twice*. The
     * scan is going to fail anyway; `SecurityException` and `SppPermissionDeniedException` are
     * already classified into `ConnectFailure.BLUETOOTH_PERMISSION` at the transport edge; and the
     * connect screen already offers the settings deep-link, which — once the user has refused
     * twice and Android stops showing the dialog — is the only remedy left. A second refusal path
     * in the Activity would be a second thing to keep in step with the first.
     */
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions.launch(bluetoothPermissions())
        setContent { App(bannerAdUnitId = BuildConfig.BANNER_AD_UNIT_ID) }

        // Gathered here because this is the first point an Activity exists to attach a consent
        // form to. Non-blocking and defensive by design — see AdsConsent's KDoc — so a slow or
        // failed consent update never delays anything on screen.
        AdsConsent.gather(this)
    }
}

/**
 * What this Android version actually needs in order to see a Bluetooth adapter.
 *
 * The split at API 31 is not cosmetic. Below it **a BLE scan legally requires location**, and
 * without it `startScan` returns an empty list with *no error at all* — so the app looks broken
 * rather than unpermitted, and the user is left staring at an empty adapter list. From 31 on,
 * `neverForLocation` on `BLUETOOTH_SCAN` in the manifest is what lets us stop asking a driver for
 * their location in order to read their coolant temperature.
 */
private fun bluetoothPermissions(): Array<String> {
    val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }
    // Foreground location for the trip route + start/arrival address. Also what a BLE scan needs
    // on API <=30, which is why it was already requested there; now it is asked for on every
    // version. A refusal is fine — GPS just records nothing, see FusedLocationSource.
    return bt + Manifest.permission.ACCESS_FINE_LOCATION
}
