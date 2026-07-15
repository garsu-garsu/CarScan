package com.bruni.carscan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.app_name
import com.bruni.carscan.core.designsystem.generated.resources.common_settings
import com.bruni.carscan.core.designsystem.generated.resources.connect_title
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_hud
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_title
import com.bruni.carscan.core.designsystem.generated.resources.live_title
import com.bruni.carscan.core.designsystem.generated.resources.settings_vehicle
import com.bruni.carscan.core.designsystem.generated.resources.trips_title
import com.bruni.carscan.nav.Route
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The launcher. Connecting an adapter is one entry here, not the forced first screen — every
 * feature is reachable from one place.
 *
 * Lives in `:composeApp` rather than a feature module because it is pure navigation: it names the
 * destinations, which only the module that owns the `NavController` may do. [onOpen] is the App's
 * `navController::navigate`, so this screen still never touches the controller itself.
 */
@Composable
fun HomeScreen(onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium) }

        items(HOME_ENTRIES) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.route) },
            ) {
                Text(
                    text = stringResource(entry.label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

private data class HomeEntry(val label: StringResource, val route: Route)

// Connect first — it is the thing a user needs before anything else works. No DTC entry: manufacturer
// UDS diagnostics are out of scope (standard OBD only exposes the emissions codes). Order is display order.
private val HOME_ENTRIES = listOf(
    HomeEntry(Res.string.connect_title, Route.Connect),
    HomeEntry(Res.string.dashboard_title, Route.Dashboard),
    HomeEntry(Res.string.live_title, Route.Live()),
    HomeEntry(Res.string.dashboard_hud, Route.Hud),
    HomeEntry(Res.string.trips_title, Route.Trips),
    HomeEntry(Res.string.settings_vehicle, Route.Garage),
    HomeEntry(Res.string.common_settings, Route.Settings),
)
