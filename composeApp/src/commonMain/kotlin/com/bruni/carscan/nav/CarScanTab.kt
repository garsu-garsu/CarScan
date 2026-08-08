package com.bruni.carscan.nav

import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.common_settings
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_title
import com.bruni.carscan.core.designsystem.generated.resources.live_title
import org.jetbrains.compose.resources.StringResource

/**
 * The tabs. The bar is not the router — it is a shortcut to the three destinations a driver
 * switches between, and the rest are reached the way they are actually used: Connect is where the
 * app starts and pops itself once an adapter answers, and Live also opens by tapping a gauge.
 *
 * **Trips and the HUD are deliberately not here.** Both are real screens, reached from Home —
 * the HUD also from the dashboard. A bar wide enough for five tabs is a bar nobody can hit at
 * a glance while driving, which is the only time this bar is used.
 *
 * Declaration order is display order.
 */
enum class CarScanTab(val route: Route, val label: StringResource) {
    DASHBOARD(Route.Dashboard, Res.string.dashboard_title),
    LIVE(Route.Live(), Res.string.live_title),
    SETTINGS(Route.Settings, Res.string.common_settings),
}
