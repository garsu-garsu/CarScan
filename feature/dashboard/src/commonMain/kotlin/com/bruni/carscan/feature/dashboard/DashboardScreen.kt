package com.bruni.carscan.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.gauge.ClassicAnalogGauge
import com.bruni.carscan.core.designsystem.gauge.Gauge
import com.bruni.carscan.core.designsystem.gauge.GaugeRenderer
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.ModernArcGauge
import com.bruni.carscan.core.designsystem.theme.GaugeThemes
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.common_cancel
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_add_tile
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_empty
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_hud
import com.bruni.carscan.core.designsystem.generated.resources.health_slowed_down
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

/** Stable handles for the smoke tests, and the only place they are spelled. */
object DashboardTags {
    const val GRID = "dashboard_grid"
    const val ADD = "dashboard_add"
    const val PICKER = "dashboard_picker"
    const val EMPTY = "dashboard_empty"
    const val HEALTH = "dashboard_health"

    fun tile(id: String) = "dashboard_tile_$id"
    fun offer(key: String) = "dashboard_offer_$key"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = rememberLazyGridState()

    // The single most load-bearing line on this screen.
    //
    // A counterfeit ELM327 answers 10-20 queries per second in total. The scheduler promotes what
    // is on screen to CRITICAL and stretches everything else — but it only knows what is on screen
    // because of this. Without it the budget is spread evenly over PIDs that scrolled away three
    // screens ago, and the gauges the user is actually looking at crawl.
    //
    // snapshotFlow reads the layout, so it re-emits when the user scrolls *and* when a tile is
    // added or removed. distinctUntilChanged because the grid republishes its visible items on
    // every scroll frame.
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged()
            .collect { onIntent(DashboardIntent.VisibleTiles(it)) }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onIntent(DashboardIntent.OpenPicker) },
                modifier = Modifier.testTag(DashboardTags.ADD),
            ) {
                Text(stringResource(Res.string.dashboard_add_tile))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Enter the windshield HUD. A full-screen driving mode, so it is a destination rather
            // than a tab — the screen only asks; :composeApp navigates.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onIntent(DashboardIntent.OpenHud) }) {
                    Text(stringResource(Res.string.dashboard_hud))
                }
            }

            // The honest number. An adapter that cannot keep up makes the dashboard slow whatever
            // we do, and saying so is the only thing that lets the user act on it.
            if (state.health.isOverSubscribed) {
                Text(
                    text = stringResource(Res.string.health_slowed_down),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(DashboardTags.HEALTH),
                )
            }

            if (state.tiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(Res.string.dashboard_empty),
                        modifier = Modifier.testTag(DashboardTags.EMPTY),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag(DashboardTags.GRID),
                ) {
                    // The key IS the tile id, and the snapshotFlow above reads it straight back
                    // out. Keying on the index would report a *position*, and the poller would be
                    // told to promote whatever now happens to sit in slot three.
                    items(state.tiles, key = { it.id }) { tile ->
                        TileCard(tile = tile, editing = state.editing, onIntent = onIntent)
                    }
                }
            }
        }
    }

    if (state.pickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { onIntent(DashboardIntent.ClosePicker) },
            modifier = Modifier.testTag(DashboardTags.PICKER),
        ) {
            TilePickerSheet(state.available, onIntent)
        }
    }
}

/**
 * One tile — and the two things it must get right that no assertion about a gauge's geometry can
 * catch.
 *
 * **The style is the tile's, not the app's.** `LocalGaugeRenderer` carries the *global* style
 * setting, but a tile persists its own [GaugeStyleId] and the user can mix them on one dashboard.
 * Taking the renderer from the composition local would silently ignore every per-tile style the
 * layout has saved — the layout would round-trip perfectly and the screen would draw one style.
 *
 * **The face is the tile's too.** See [tileFace]: a classic gauge fills no dial, so on this app's
 * dark surface it is a black needle on a black ground.
 */
@Composable
private fun TileCard(
    tile: TileState,
    editing: Boolean,
    onIntent: (DashboardIntent) -> Unit,
) {
    val face = tileFace(tile.style, MaterialTheme.colorScheme.surface)

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .testTag(DashboardTags.tile(tile.id))
            .clickable { onIntent(DashboardIntent.TileTapped(tile.id)) },
        colors = CardDefaults.cardColors(containerColor = face),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Gauge(
                // The words are put on here, at the only layer that can resolve a locale. The
                // mapper decided which unit the number is in; this decides how to say it.
                spec = tile.spec.copy(
                    label = tileLabelOf(tile.key, tile.spec.label),
                    unitLabel = unitLabelOf(tile.displayUnit, tile.nativeUnit),
                ),
                theme = GaugeThemes.forStyle(tile.style),
                renderer = rendererFor(tile.style),
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )

            if (editing) {
                TextButton(
                    onClick = { onIntent(DashboardIntent.Remove(tile.id)) },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text("×")
                }
            }
        }
    }
}

/** Renderers are stateless, so one of each serves the whole grid. */
private val MODERN_ARC: GaugeRenderer = ModernArcGauge()
private val CLASSIC_ANALOG: GaugeRenderer = ClassicAnalogGauge()

private fun rendererFor(style: GaugeStyleId): GaugeRenderer = when (style) {
    GaugeStyleId.MODERN_ARC -> MODERN_ARC
    GaugeStyleId.CLASSIC_ANALOG -> CLASSIC_ANALOG
}

/**
 * Only what this car can actually answer.
 *
 * The list arrives already filtered — see [availableTiles]. A PID the vehicle has refused would
 * make a tile that is stale forever, and users read a permanently stale tile as a broken app
 * rather than as a sensor their trim level does not have.
 */
@Composable
private fun TilePickerSheet(
    offers: List<PickerEntry>,
    onIntent: (DashboardIntent) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        items(offers, key = { it.key.toString() }) { offer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DashboardTags.offer(offer.key.toString()))
                    .clickable { onIntent(DashboardIntent.Add(offer.key)) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tileLabelOf(offer.key, offer.label))

                // OBDb has not verified this signal for this vehicle. Offer it — it may well
                // work — but never present it as fact.
                if (offer.experimental) {
                    Text(
                        text = "?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        item {
            TextButton(onClick = { onIntent(DashboardIntent.ClosePicker) }) {
                Text(stringResource(Res.string.common_cancel))
            }
        }
    }
}
