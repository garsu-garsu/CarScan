package com.bruni.carscan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.theme.CarScanTheme
import com.bruni.carscan.feature.connect.ConnectEffect
import com.bruni.carscan.feature.connect.ConnectScreen
import com.bruni.carscan.feature.connect.ConnectViewModel
import com.bruni.carscan.feature.dashboard.DashboardEffect
import com.bruni.carscan.feature.dashboard.DashboardScreen
import com.bruni.carscan.feature.dashboard.DashboardViewModel
import com.bruni.carscan.feature.dtc.DtcScreen
import com.bruni.carscan.feature.garage.GarageEffect
import com.bruni.carscan.feature.garage.GarageScreen
import com.bruni.carscan.feature.garage.GarageViewModel
import com.bruni.carscan.feature.hud.HudScreen
import com.bruni.carscan.feature.live.LiveIntent
import com.bruni.carscan.feature.live.LiveScreen
import com.bruni.carscan.feature.live.LiveViewModel
import com.bruni.carscan.feature.settings.AboutScreen
import com.bruni.carscan.feature.settings.SettingsEffect
import com.bruni.carscan.feature.settings.SettingsScreen
import com.bruni.carscan.feature.settings.SettingsViewModel
import com.bruni.carscan.feature.trip.TripScreen
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.nav.CarScanTab
import com.bruni.carscan.nav.Route
import com.bruni.carscan.nav.decodeMetricKeyRoute
import com.bruni.carscan.nav.encodeForRoute
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app, and the only `NavController` in it.
 *
 * **Features never depend on each other.** A screen that wants to be somewhere else emits an
 * effect saying so — `DashboardEffect.OpenLiveChart` — and this file is the only thing that turns
 * one into a navigation. A dashboard that called `navController.navigate(…)` itself would put
 * `:feature:dashboard` on `:feature:live`'s classpath, and from there the module graph stops
 * meaning anything.
 */
@Composable
fun App() {
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val gaugeStyle = when (settings.gaugeStyle) {
        "CLASSIC_ANALOG" -> GaugeStyleId.CLASSIC_ANALOG
        else -> GaugeStyleId.MODERN_ARC
    }

    CarScanTheme(darkTheme = darkTheme, gaugeStyle = gaugeStyle) {
        val navController = rememberNavController()
        val entry by navController.currentBackStackEntryAsState()

        // Connect is a thing you finish, not a place you go: it is the start destination, and it
        // pops itself off the stack the moment an adapter answers. A tab bar over it would offer
        // a dashboard with nothing to put on it.
        val onTab = entry?.destination?.hierarchy
            ?.any { destination -> CarScanTab.entries.any { destination.hasRoute(it.route::class) } }
            ?: false

        Scaffold(
            bottomBar = { if (onTab) CarScanNavigationBar(navController, entry?.destination) },
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                CarScanNavHost(navController)
            }
        }
    }
}

@Composable
private fun CarScanNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Route.Connect) {

        composable<Route.Connect> {
            val viewModel: ConnectViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings: AppSettingsOpener = koinInject()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        ConnectEffect.NavigateToDashboard -> navController.navigate(Route.Dashboard) {
                            // Nothing to go back to. Leaving Connect on the stack means Back drops
                            // the user onto a scan screen mid-drive.
                            popUpTo<Route.Connect> { inclusive = true }
                        }

                        // `BLUETOOTH_PERMISSION` is the one failure with a working remedy attached,
                        // and this is the remedy. The deep link is platform code, so the platform
                        // gets to write it.
                        ConnectEffect.OpenAppSettings -> settings.open()
                    }
                }
            }

            ConnectScreen(state, viewModel::onIntent)
        }

        composable<Route.Dashboard> {
            val viewModel: DashboardViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is DashboardEffect.OpenLiveChart ->
                            navController.navigate(Route.Live(effect.key.encodeForRoute()))
                    }
                }
            }

            DashboardScreen(state, viewModel::onIntent)
        }

        composable<Route.Live> { entry ->
            val route = entry.toRoute<Route.Live>()
            val viewModel: LiveViewModel = koinViewModel()

            // The tile the user tapped is the series the chart opens on. Keyed on the route, so
            // coming back to a chart they already had open does not toggle it back off.
            LaunchedEffect(route.metricKey) {
                route.metricKey
                    ?.let(::decodeMetricKeyRoute)
                    ?.let { key -> viewModel.onIntent(LiveIntent.ToggleSeries(key)) }
            }

            LiveScreen(viewModel = viewModel)
        }

        composable<Route.Dtc> { DtcScreen() }
        composable<Route.Hud> { HudScreen() }
        composable<Route.Trips> { TripScreen() }

        composable<Route.Settings> {
            val viewModel: SettingsViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        SettingsEffect.OpenAbout -> navController.navigate(Route.About)
                        SettingsEffect.OpenGarage -> navController.navigate(Route.Garage)
                    }
                }
            }

            SettingsScreen(state, viewModel::onIntent)
        }

        composable<Route.About> { AboutScreen() }

        composable<Route.Garage> {
            val viewModel: GarageViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        // Picked from Settings, so returning there is the least-surprising landing.
                        GarageEffect.Selected -> navController.popBackStack()
                    }
                }
            }

            GarageScreen(state, viewModel::onIntent)
        }
    }
}

@Composable
private fun CarScanNavigationBar(
    navController: NavHostController,
    current: androidx.navigation.NavDestination?,
) {
    NavigationBar {
        for (tab in CarScanTab.entries) {
            NavigationBarItem(
                selected = current?.hierarchy?.any { it.hasRoute(tab.route::class) } == true,
                onClick = {
                    navController.navigate(tab.route) {
                        // Without this every tap pushes another copy, and Back then walks the user
                        // back through their whole tab history one screen at a time.
                        popUpTo(Route.Dashboard) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                // Label-only. The icon set is a design-system decision that has not been made, and
                // a placeholder glyph on every tab is worse than none.
                icon = {},
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
