package com.bruni.carscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.designsystem.ads.BannerAd
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.adapter_unnamed
import com.bruni.carscan.core.designsystem.generated.resources.app_name
import com.bruni.carscan.core.designsystem.generated.resources.common_settings
import com.bruni.carscan.core.designsystem.generated.resources.connect_title
import com.bruni.carscan.core.designsystem.generated.resources.connection_state_connected
import com.bruni.carscan.core.designsystem.generated.resources.connection_state_connecting
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_hud
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_title
import com.bruni.carscan.core.designsystem.generated.resources.home_connect_hint
import com.bruni.carscan.core.designsystem.generated.resources.home_no_vehicle
import com.bruni.carscan.core.designsystem.generated.resources.home_tagline
import com.bruni.carscan.core.designsystem.generated.resources.live_title
import com.bruni.carscan.core.designsystem.generated.resources.settings_vehicle
import com.bruni.carscan.core.designsystem.generated.resources.trips_title
import com.bruni.carscan.nav.Route
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The launcher, designed around one job: connect an adapter — nothing else works until you have.
 *
 * So it is not a row of equal choices (which is a row of ways to hesitate — Hick's Law). Connecting
 * is a single vivid gradient hero at the top; the things you do *after* connecting sit below in a
 * calm, adaptive grid. The header carries the value line so the first screen says what the app is for.
 *
 * Lives in `:composeApp` rather than a feature module because it is pure navigation: it names the
 * destinations, which only the module that owns the `NavController` may do. [onOpen] is the App's
 * `navController::navigate`, so this screen still never touches the controller itself. Likewise
 * [activeVehicleName] is resolved by App.kt (from [com.bruni.carscan.core.data.SettingsRepository]
 * and [com.bruni.carscan.core.data.VehicleRepository]) and simply handed down — this stays a
 * screen with no ViewModel or repository of its own.
 */
@Composable
fun HomeScreen(
    onOpen: (Route) -> Unit,
    activeVehicleName: String? = null,
    connection: ConnectionState = ConnectionState.DISCONNECTED,
    connectedAdapterName: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Adaptive, not a fixed two columns: a phone shows two, a tablet three or four, and the
            // tiles keep the same comfortable size on both instead of stretching wide on a big screen.
            columns = GridCells.Adaptive(minSize = 168.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        text = stringResource(Res.string.app_name),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(Res.string.home_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                CurrentVehicleRow(vehicleName = activeVehicleName, onClick = { onOpen(Route.Garage) })
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                ConnectHero(
                    connection = connection,
                    adapterName = connectedAdapterName,
                    // Connected, the hero is no longer a call to connect — it is the way back to
                    // the thing they connected *for*, so it opens the dashboard. Deliberately not
                    // "disconnect": that is the one destructive action here, and putting it under
                    // the largest touch target on the launcher, in a car, is a mis-tap that ends
                    // the drive's recording. Disconnecting stays where the connection is managed.
                    onClick = {
                        onOpen(
                            if (connection == ConnectionState.CONNECTED) Route.Dashboard else Route.Connect,
                        )
                    },
                )
            }

            items(FEATURE_ENTRIES) { entry ->
                FeatureTile(label = stringResource(entry.label), icon = entry.icon) { onOpen(entry.route) }
            }
        }

        BannerAd(Modifier.fillMaxWidth())
    }
}

/** The garage-picked vehicle, or a prompt to pick one — tapping either opens the garage. */
@Composable
private fun CurrentVehicleRow(vehicleName: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = vehicleName ?: stringResource(Res.string.home_no_vehicle),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The vivid gradient the hero fills itself with — the one place the screen turns up the colour.
private val HeroGradient = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4C7BFF)))

/**
 * The primary action, filled in a vivid gradient so the eye lands here first — and, once an
 * adapter answers, the one place on the launcher that says so.
 *
 * Home used to read "connect a scanner" with live data streaming into the dashboard behind it,
 * which meant the only way to find out whether you were connected was to open a data screen.
 */
@Composable
private fun ConnectHero(connection: ConnectionState, adapterName: String?, onClick: () -> Unit) {
    val connected = connection == ConnectionState.CONNECTED
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(HeroGradient)
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.White.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (connection) {
                            ConnectionState.CONNECTED -> Res.string.connection_state_connected
                            ConnectionState.CONNECTING -> Res.string.connection_state_connecting
                            ConnectionState.DISCONNECTED -> Res.string.connect_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    // Connected, the second line is which scanner — the adapter advertises a name
                    // and a user with two of them needs to know which one answered.
                    text = if (connected) {
                        adapterName ?: stringResource(Res.string.adapter_unnamed)
                    } else {
                        stringResource(Res.string.home_connect_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White)
        }
    }
}

/** One of the after-you-connect destinations, in the calm adaptive grid, with a colour-lit icon. */
@Composable
private fun FeatureTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(124.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private data class HomeEntry(val label: StringResource, val icon: ImageVector, val route: Route)

// The things you do after connecting. No DTC entry: manufacturer UDS diagnostics are out of scope
// (standard OBD only exposes the emissions codes). Display order.
private val FEATURE_ENTRIES = listOf(
    HomeEntry(Res.string.dashboard_title, Icons.Rounded.SpaceDashboard, Route.Dashboard),
    HomeEntry(Res.string.live_title, Icons.Rounded.ShowChart, Route.Live()),
    HomeEntry(Res.string.dashboard_hud, Icons.Rounded.Flip, Route.Hud),
    HomeEntry(Res.string.trips_title, Icons.Rounded.Route, Route.Trips),
    HomeEntry(Res.string.settings_vehicle, Icons.Rounded.DirectionsCar, Route.Garage),
    HomeEntry(Res.string.common_settings, Icons.Rounded.Settings, Route.Settings),
)
