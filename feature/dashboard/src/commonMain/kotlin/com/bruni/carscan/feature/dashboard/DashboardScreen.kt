package com.bruni.carscan.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bruni.carscan.core.designsystem.gauge.ClassicAnalogGauge
import com.bruni.carscan.core.designsystem.gauge.Gauge
import com.bruni.carscan.core.designsystem.gauge.GaugeRenderer
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.LinearBarHGauge
import com.bruni.carscan.core.designsystem.gauge.LinearBarVGauge
import com.bruni.carscan.core.designsystem.gauge.ModernArcGauge
import com.bruni.carscan.core.designsystem.gauge.NumericGauge
import com.bruni.carscan.core.designsystem.gauge.SemicircleGauge
import com.bruni.carscan.core.designsystem.theme.GaugeThemes
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.common_cancel
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_add_tile
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_edit
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_edit_done
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_edit_hint
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_empty
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_hud
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_remove_confirm
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_remove_tile
import com.bruni.carscan.core.designsystem.generated.resources.health_slowed_down
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_classic_analog
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_linear_bar_h
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_linear_bar_v
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_modern_arc
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_numeric
import com.bruni.carscan.core.designsystem.generated.resources.settings_gauge_style_semicircle
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = rememberLazyGridState()
    val drag = remember { TileDrag() }

    // Which tile has its style sheet open, and which one is being asked about before it is
    // removed. Both are transient and purely visual — they say nothing about the layout until
    // the user commits — so they stay in the composition rather than in DashboardState. The
    // reducer still owns every change to the layout itself.
    var styleFor by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

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
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.dashboard_add_tile))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // The two things you can do to the dashboard as a whole: drive with it, or rearrange
            // it. Editing is entered by tapping a labelled chip up here and nothing else — no
            // long-press, no gesture anywhere on a gauge — because the person holding this phone
            // is in a moving car and a pothole must not be able to rearrange their dashboard.
            //
            // While editing, the HUD chip goes away: it navigates off the screen mid-edit, and
            // the row is instead used to say how reordering works, which is otherwise invisible.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.editing) {
                    Text(
                        text = stringResource(Res.string.dashboard_edit_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = { onIntent(DashboardIntent.OpenHud) },
                        label = { Text(stringResource(Res.string.dashboard_hud)) },
                        leadingIcon = { Icon(Icons.Rounded.Flip, contentDescription = null) },
                    )
                    Spacer(Modifier.width(8.dp))
                }

                AssistChip(
                    onClick = { onIntent(DashboardIntent.ToggleEditing) },
                    label = {
                        Text(
                            stringResource(
                                if (state.editing) {
                                    Res.string.dashboard_edit_done
                                } else {
                                    Res.string.dashboard_edit
                                },
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (state.editing) Icons.Rounded.Done else Icons.Rounded.Edit,
                            contentDescription = null,
                        )
                    },
                )
            }

            // The honest number. An adapter that cannot keep up makes the dashboard slow whatever
            // we do, and saying so is the only thing that lets the user act on it.
            if (state.health.isOverSubscribed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.health_slowed_down),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.tiles.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.SpaceDashboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.dashboard_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // The key IS the tile id, and the snapshotFlow above reads it straight back
                    // out. Keying on the index would report a *position*, and the poller would be
                    // told to promote whatever now happens to sit in slot three.
                    items(state.tiles, key = { it.id }) { tile ->
                        // Read in composition, so every visible tile recomposes when a drag
                        // starts and again when it ends — twice per drag, not once per frame.
                        // The offset a drag actually moves by never reaches composition at all;
                        // see TileDrag.
                        val dragging = drag.id == tile.id
                        TileCard(
                            tile = tile,
                            editing = state.editing,
                            dragging = dragging,
                            drag = drag,
                            grid = grid,
                            onIntent = onIntent,
                            onStyle = { styleFor = tile.id },
                            onRemove = { removing = tile.id },
                            // Everything else slides to its new slot. The dragged tile is placed
                            // by the finger, and letting the animation have it too means the two
                            // fight over the same pixels.
                            modifier = if (dragging) Modifier else Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (state.pickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { onIntent(DashboardIntent.ClosePicker) },
        ) {
            TilePickerSheet(state.available, onIntent)
        }
    }

    // A gauge is never one tap from gone. Edit mode already takes a deliberate tap to enter, but
    // inside it the remove button sits on a 160dp tile among five others in a car that is moving,
    // and a mis-hit that silently deletes the tile the user was reading is not recoverable —
    // re-adding it loses its style and its place.
    removing?.let { id ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(Res.string.dashboard_remove_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(DashboardIntent.Remove(id))
                        removing = null
                    },
                ) {
                    Text(stringResource(Res.string.dashboard_remove_tile))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    styleFor?.let { id ->
        ModalBottomSheet(onDismissRequest = { styleFor = null }) {
            GaugeStyleSheet(
                selected = state.tiles.firstOrNull { it.id == id }?.style,
                onPick = { style ->
                    onIntent(DashboardIntent.SetStyle(id, style))
                    styleFor = null
                },
            )
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
    dragging: Boolean,
    drag: TileDrag,
    grid: LazyGridState,
    onIntent: (DashboardIntent) -> Unit,
    onStyle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = tileFace(tile.style, MaterialTheme.colorScheme.surface)

    Card(
        modifier = modifier
            .aspectRatio(1f)
            // Above the tiles it is being dragged over, not under them.
            .zIndex(if (dragging) 1f else 0f)
            // A draw-phase read: the drag offset changes every frame and must never invalidate
            // the composition of a gauge that is also being fed live data.
            .graphicsLayer {
                if (dragging) {
                    translationX = drag.offset.x
                    translationY = drag.offset.y
                    scaleX = LIFTED
                    scaleY = LIFTED
                }
            }
            .then(
                if (editing) {
                    Modifier.reorderable(tile.id, grid, drag) { from, to ->
                        onIntent(DashboardIntent.Move(from, to))
                    }
                } else {
                    // Tapping a gauge opens its live chart — but only outside edit mode, where
                    // the tile's own two buttons are the only things that answer a tap. That is
                    // also what keeps a long-press from ever competing with a click.
                    Modifier.clickable { onIntent(DashboardIntent.TileTapped(tile.id)) }
                },
            ),
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

            // Full IconButtons rather than a bare glyph: 48dp is the smallest target a thumb can
            // be asked to find in a car, and these two sit in opposite corners so that missing
            // one cannot hit the other.
            if (editing) {
                IconButton(onClick = onStyle, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(
                        Icons.Rounded.Palette,
                        contentDescription = stringResource(Res.string.settings_gauge_style),
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.dashboard_remove_tile),
                    )
                }
            }
        }
    }
}

/**
 * The per-tile gauge style. Settings picks the app's default; this overrides it for one tile,
 * which is the whole point of [LayoutCodec] persisting a style per tile.
 *
 * A plain [Column]: there are six styles and there will not be many more, and a lazy list here
 * would only add a scroll container inside a sheet that already scrolls.
 */
@Composable
private fun GaugeStyleSheet(
    selected: GaugeStyleId?,
    onPick: (GaugeStyleId) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(Res.string.settings_gauge_style),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        for (style in GaugeStyleId.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(style) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(styleLabel(style), modifier = Modifier.weight(1f))
                if (style == selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun styleLabel(style: GaugeStyleId): String = stringResource(
    when (style) {
        GaugeStyleId.MODERN_ARC -> Res.string.settings_gauge_style_modern_arc
        GaugeStyleId.CLASSIC_ANALOG -> Res.string.settings_gauge_style_classic_analog
        GaugeStyleId.SEMICIRCLE -> Res.string.settings_gauge_style_semicircle
        GaugeStyleId.NUMERIC -> Res.string.settings_gauge_style_numeric
        GaugeStyleId.LINEAR_BAR_H -> Res.string.settings_gauge_style_linear_bar_h
        GaugeStyleId.LINEAR_BAR_V -> Res.string.settings_gauge_style_linear_bar_v
    },
)

/** How much a picked-up tile grows, so it reads as lifted off the grid. */
private const val LIFTED = 1.05f

/** Renderers are stateless, so one of each serves the whole grid. */
private val MODERN_ARC: GaugeRenderer = ModernArcGauge()
private val CLASSIC_ANALOG: GaugeRenderer = ClassicAnalogGauge()
private val SEMICIRCLE: GaugeRenderer = SemicircleGauge()
private val NUMERIC: GaugeRenderer = NumericGauge()
private val LINEAR_BAR_H: GaugeRenderer = LinearBarHGauge()
private val LINEAR_BAR_V: GaugeRenderer = LinearBarVGauge()

private fun rendererFor(style: GaugeStyleId): GaugeRenderer = when (style) {
    GaugeStyleId.MODERN_ARC -> MODERN_ARC
    GaugeStyleId.CLASSIC_ANALOG -> CLASSIC_ANALOG
    GaugeStyleId.SEMICIRCLE -> SEMICIRCLE
    GaugeStyleId.NUMERIC -> NUMERIC
    GaugeStyleId.LINEAR_BAR_H -> LINEAR_BAR_H
    GaugeStyleId.LINEAR_BAR_V -> LINEAR_BAR_V
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
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        item {
            Text(
                text = stringResource(Res.string.dashboard_add_tile),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        items(offers, key = { it.key.toString() }) { offer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onIntent(DashboardIntent.Add(offer.key)) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ShowChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(tileLabelOf(offer.key, offer.label))
                }

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
            TextButton(
                onClick = { onIntent(DashboardIntent.ClosePicker) },
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        }
    }
}
