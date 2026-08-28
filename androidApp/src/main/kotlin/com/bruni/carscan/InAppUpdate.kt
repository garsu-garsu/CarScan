package com.bruni.carscan

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Puts every user on the newest published build before they can use the app.
 *
 * [AppUpdateType.IMMEDIATE], not `FLEXIBLE`: the flexible flow downloads in the background and
 * politely asks, which means a user who keeps saying "later" keeps driving on an old build. This
 * one is Google's own full-screen, blocking UI, and Play restarts the app into the new version
 * itself — there is nothing for us to install, monitor or restart.
 *
 * **Driven from `onResume`, not `onCreate`.** Two things need that, and one method covers both:
 *  - `DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS` — the user backgrounded the app *during* an update.
 *    Without resuming the flow they come back to a half-downloaded update and no way to finish it.
 *    Google's own docs call this out, and it is the part everyone forgets.
 *  - `UPDATE_AVAILABLE` — they dismissed the sheet. `onResume` fires again the moment they do, so
 *    the only way past it is to update. That is what "always on the latest version" costs; if it
 *    ever needs to be softer, this is the one condition to drop.
 *
 * **Nothing happens on a build that did not come from Play.** A sideloaded or `adb install`ed
 * APK — every debug build, and every build used for bench testing — makes [AppUpdateManager]'s
 * task fail rather than succeed, so [enforce] simply does nothing. No crash, no dialog, and no
 * special-casing needed here. It also means this cannot be verified from the desk: it takes a
 * Play-installed build with a *higher* versionCode already live on some track.
 */
class InAppUpdate(
    private val manager: AppUpdateManager,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
) {

    fun enforce() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.blocksUse()) {
                manager.startUpdateFlowForResult(info, launcher, IMMEDIATE)
            }
        }
    }

    private fun AppUpdateInfo.blocksUse(): Boolean = when (updateAvailability()) {
        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> true
        // isUpdateTypeAllowed can be false even with an update waiting — a device low on storage,
        // or an update Play will only hand over over Wi-Fi. Launching the flow anyway throws.
        UpdateAvailability.UPDATE_AVAILABLE -> isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        else -> false
    }

    private companion object {
        val IMMEDIATE: AppUpdateOptions = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
    }
}
