package com.bruni.carscan.platform.android.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The activity currently in the foreground, if any.
 *
 * [PlayBillingPort] is a Koin singleton scoped to the whole app — the same instance across every
 * screen — but `launchBillingFlow` needs a foreground [Activity] to attach the purchase sheet to,
 * and [BillingPort][com.bruni.carscan.core.monetization.BillingPort]'s `purchase(kind)` signature
 * (fixed in `:core:monetization`) takes none. [ActivityTracker] is the app-wide substitute:
 * registered once in `CarScanApplication`, it keeps this pointed at whatever activity is resumed.
 */
object CurrentActivity {
    @Volatile
    var value: Activity? = null
        internal set
}

/** Registered once via `Application.registerActivityLifecycleCallbacks` — see [CurrentActivity]. */
class ActivityTracker : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        CurrentActivity.value = activity
    }

    override fun onActivityPaused(activity: Activity) {
        if (CurrentActivity.value === activity) CurrentActivity.value = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
