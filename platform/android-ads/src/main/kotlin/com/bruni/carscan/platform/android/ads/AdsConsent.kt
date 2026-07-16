package com.bruni.carscan.platform.android.ads

import android.app.Activity
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Gathers GDPR/UMP consent, non-blocking and defensive: nothing here may ever stop ads from
 * working, so every outcome — success, failure, or nothing required — just calls [onComplete].
 *
 * Requesting consent info is safe to call on every launch (Google's own guidance), so [gather]
 * takes no "have we already asked" guard of its own — call it once an [Activity] exists, e.g.
 * from `MainActivity.onCreate`.
 *
 * NOTE for the AdMob account owner: this only shows a real form once a GDPR privacy message has
 * been configured in the AdMob console (Privacy & messaging). Until then,
 * `loadAndShowConsentFormIfRequired` correctly finds nothing to show and this is a silent no-op
 * — that is expected, not a bug in this code.
 */
object AdsConsent {
    fun gather(activity: Activity, onComplete: () -> Unit = {}) {
        val params = ConsentRequestParameters.Builder().build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    // The form error, if any, is deliberately ignored — see the class KDoc.
                    onComplete()
                }
            },
            { onComplete() }, // Consent info update failed (no network, etc.) — proceed anyway.
        )
    }
}
