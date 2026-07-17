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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.bruni.carscan.core.data.VehicleRepository
import com.bruni.carscan.core.designsystem.ads.LocalAdsEnabled
import com.bruni.carscan.core.designsystem.ads.LocalBannerAdUnitId
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.theme.CarScanTheme
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.FullScreenAdGate
import com.bruni.carscan.core.monetization.InterstitialAdPort
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
import com.bruni.carscan.feature.settings.PaywallEffect
import com.bruni.carscan.feature.settings.PaywallScreen
import com.bruni.carscan.feature.settings.PaywallViewModel
import com.bruni.carscan.feature.settings.SettingsEffect
import com.bruni.carscan.feature.settings.SettingsScreen
import com.bruni.carscan.feature.settings.SettingsViewModel
import com.bruni.carscan.feature.trip.TripDetailIntent
import com.bruni.carscan.feature.trip.TripDetailScreen
import com.bruni.carscan.feature.trip.TripDetailViewModel
import com.bruni.carscan.feature.trip.TripListEffect
import com.bruni.carscan.feature.trip.TripListViewModel
import com.bruni.carscan.feature.trip.TripScreen
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.nav.CarScanTab
import com.bruni.carscan.nav.Route
import com.bruni.carscan.nav.decodeMetricKeyRoute
import com.bruni.carscan.nav.encodeForRoute
import com.bruni.carscan.obd.AcquisitionController
import com.bruni.carscan.obd.AcquisitionScreen
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
fun App(bannerAdUnitId: String? = null) {
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())

    // The launcher's "current vehicle" chip — resolved here, once, and handed down, so HomeScreen
    // stays pure navigation with no repository of its own. Re-resolves whenever the active vehicle
    // id changes (a garage pick) rather than once at first composition.
    val vehicleRepository: VehicleRepository = koinInject()
    val activeVehicleName by produceState<String?>(initialValue = null, settings.activeVehicleId, vehicleRepository) {
        value = settings.activeVehicleId?.let { id -> vehicleRepository.byId(id)?.displayName }
    }

    val entitlements: Entitlements = koinInject()
    val isPremium by entitlements.isPremium.collectAsStateWithLifecycle()

    val interstitialAd: InterstitialAdPort = koinInject()
    val adGate: FullScreenAdGate = koinInject()

    // Keeps the poller alive on whichever data screen the user chose as their acquisition source,
    // once they navigate away from it — see AcquisitionController's KDoc.
    val acquisitionController: AcquisitionController = koinInject()

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
        // Premium users and any screen this provider does not reach (Dashboard, Live, HUD) never
        // see a BannerAd — see :core:designsystem's LocalAdsEnabled. The banner ad-unit id, when
        // the Android entry supplies a real one, overrides the test-id default; other platforms
        // pass null and keep it.
        val adProviders = if (bannerAdUnitId != null) {
            arrayOf(LocalAdsEnabled provides !isPremium, LocalBannerAdUnitId provides bannerAdUnitId)
        } else {
            arrayOf(LocalAdsEnabled provides !isPremium)
        }
        CompositionLocalProvider(*adProviders) {
            val navController = rememberNavController()
            val entry by navController.currentBackStackEntryAsState()

            // Interstitial at a natural break: arriving back at the launcher after finishing a
            // screen. The shared FullScreenAdGate enforces warmup, spacing, and the per-session cap
            // (so the cold-start Home never qualifies), and premium users never do — leaving at most
            // an occasional full-screen ad at a transition, never mid-drive.
            LaunchedEffect(entry) {
                if (entry?.destination?.hasRoute(Route.Home::class) == true &&
                    !isPremium && adGate.shouldShow()
                ) {
                    // Count it only if an ad actually appeared — a no-fill or missing Activity
                    // must not burn a session slot or reset the spacing interval.
                    if (interstitialAd.show()) adGate.record()
                }
            }

            // Tells AcquisitionController which data screen, if any, is actually in the
            // foreground — see its KDoc. Every other destination (home, trips, settings, connect,
            // garage, paywall, about, dtc) hands acquisition back to the chosen background source.
            LaunchedEffect(entry) {
                acquisitionController.setForeground(
                    when {
                        entry?.destination?.hasRoute(Route.Dashboard::class) == true -> AcquisitionScreen.DASHBOARD
                        entry?.destination?.hasRoute(Route.Live::class) == true -> AcquisitionScreen.MONITORING
                        entry?.destination?.hasRoute(Route.Hud::class) == true -> AcquisitionScreen.HUD
                        else -> null
                    },
                )
            }

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
                    CarScanNavHost(navController, activeVehicleName)
                }
            }
        }
    }
}

@Composable
private fun CarScanNavHost(navController: NavHostController, activeVehicleName: String?) {
    NavHost(navController = navController, startDestination = Route.Home) {

        composable<Route.Home> {
            HomeScreen(onOpen = { route -> navController.navigate(route) }, activeVehicleName = activeVehicleName)
        }

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

                        DashboardEffect.OpenHud -> navController.navigate(Route.Hud)
                    }
                }
            }

            DashboardScreen(state, viewModel::onIntent)
        }

        composable<Route.Live> { entry ->
            val route = entry.toRoute<Route.Live>()
            val viewModel: LiveViewModel = koinViewModel()

            // The tile the user tapped is the signal the detail view opens on. Keyed on the
            // route, so coming back to a detail they already had open does not close it back out.
            LaunchedEffect(route.metricKey) {
                route.metricKey
                    ?.let(::decodeMetricKeyRoute)
                    ?.let { key -> viewModel.onIntent(LiveIntent.Select(key)) }
            }

            LiveScreen(viewModel = viewModel)
        }

        composable<Route.Dtc> { DtcScreen() }
        composable<Route.Hud> { HudScreen() }

        composable<Route.Trips> {
            val viewModel: TripListViewModel = koinViewModel()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is TripListEffect.OpenTrip -> navController.navigate(Route.TripDetail(effect.tripId))
                    }
                }
            }

            TripScreen(viewModel = viewModel)
        }

        composable<Route.TripDetail> { entry ->
            val route = entry.toRoute<Route.TripDetail>()
            val viewModel: TripDetailViewModel = koinViewModel()

            LaunchedEffect(route.tripId) {
                viewModel.onIntent(TripDetailIntent.Load(route.tripId))
            }

            TripDetailScreen(viewModel = viewModel)
        }

        composable<Route.Settings> {
            val viewModel: SettingsViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        SettingsEffect.OpenAbout -> navController.navigate(Route.About)
                        SettingsEffect.OpenGarage -> navController.navigate(Route.Garage)
                        SettingsEffect.OpenPaywall -> navController.navigate(Route.Paywall)
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

        composable<Route.Paywall> {
            val viewModel: PaywallViewModel = koinViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        PaywallEffect.Close -> navController.popBackStack()
                    }
                }
            }

            PaywallScreen(state, viewModel::onIntent)
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
