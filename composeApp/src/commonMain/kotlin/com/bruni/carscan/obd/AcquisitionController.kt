package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AcquisitionBaseline
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.STANDARD_CORE_SIGNALS
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.model.MetricKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The data screens the app can be showing right now. `null` (not a member of this enum) stands
 * for every non-data page — home, trips, settings, connect — where [AcquisitionController] takes
 * over.
 */
enum class AcquisitionScreen { DASHBOARD, MONITORING, HUD }

/**
 * Keeps the poller alive once the user leaves the dashboard.
 *
 * `VisibleSignals.setVisible` *replaces* the polled set, and until now the only callers were the
 * data screens themselves (`DashboardViewModel`, `LiveViewModel`, `HudViewModel`) — each announcing
 * its own tiles while it is on screen. The moment the user taps over to Trips or Settings, nothing
 * calls `setVisible` again, so the poller goes on serving whatever the last screen asked for
 * forever, or nothing at all, and — because `TripRecorder` only ever writes samples that actually
 * arrive — the trip stops recording the instant the dashboard leaves the foreground.
 *
 * This is the fix: a controller that speaks up on the user's behalf whenever no data screen is in
 * the foreground, driving [VisibleSignals] from the acquisition-source setting instead. A data
 * screen that *is* in the foreground is left alone completely — it owns `setVisible` through its
 * own announcement, and this controller must never fight it for the one visible set half-duplex
 * polling allows.
 */
class AcquisitionController(
    private val settings: SettingsRepository,
    private val baseline: AcquisitionBaseline,
    private val visibility: VisibleSignals,
    private val bookmarks: BookmarkRepository,
) {

    private val foreground = MutableStateFlow<AcquisitionScreen?>(null)

    /** Called on every navigation change — a data screen, or `null` for everywhere else. */
    fun setForeground(screen: AcquisitionScreen?) {
        foreground.value = screen
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                foreground,
                settings.settings,
                baseline.dashboardSignals,
                bookmarks.bookmarks,
            ) { fg, prefs, dashboardSignals, bookmarkedSignals ->
                // A data screen in the foreground owns setVisible itself; this must emit nothing
                // that would make the collector below call it a second time on top.
                if (fg != null) null else baselineFor(prefs.acquisitionSource, dashboardSignals, bookmarkedSignals)
            }
                .distinctUntilChanged()
                .collect { keys -> keys?.let(visibility::setVisible) }
        }
    }

    private fun baselineFor(
        source: AcquisitionSource,
        dashboardSignals: Set<MetricKey>,
        bookmarkedSignals: Set<MetricKey>,
    ): Set<MetricKey> =
        when (source) {
            // An empty dashboard polls nothing on its own — see DashboardViewModel.restore — so
            // this is the same fallback that seeds a brand-new layout.
            AcquisitionSource.DASHBOARD -> dashboardSignals.ifEmpty { STANDARD_CORE_SIGNALS }
            AcquisitionSource.MONITORING -> bookmarkedSignals.ifEmpty { STANDARD_CORE_SIGNALS }
            // HUD stays on the standard core set for now.
            AcquisitionSource.HUD -> STANDARD_CORE_SIGNALS
        }
}
