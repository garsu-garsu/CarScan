package com.bruni.carscan.feature.garage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.designsystem.ads.BannerAd
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.garage_download_failed
import com.bruni.carscan.core.designsystem.generated.resources.garage_downloading
import com.bruni.carscan.core.designsystem.generated.resources.garage_empty
import com.bruni.carscan.core.designsystem.generated.resources.garage_instruction
import com.bruni.carscan.core.designsystem.generated.resources.garage_no_matches
import com.bruni.carscan.core.designsystem.generated.resources.garage_offline
import com.bruni.carscan.core.designsystem.generated.resources.garage_search_hint
import com.bruni.carscan.core.designsystem.generated.resources.garage_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A tappable list of the curated (654-vehicle) catalog, grouped by make behind sticky headers,
 * with a fixed search bar and an A-Z fast-scroll rail. Picking a row selects that vehicle.
 */
@Composable
fun GarageScreen(
    state: GarageState,
    onIntent: (GarageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(Res.string.garage_title), style = MaterialTheme.typography.headlineSmall)

            if (state.downloading != null) {
                Spacer(Modifier.height(12.dp))
                DownloadingCard()
            }
            state.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                MessageHint(message)
            }

            // The search bar stays put outside the scrolling list, so it's always reachable
            // while browsing a 654-entry catalog.
            if (!state.loading && state.entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SearchField(
                    query = state.query,
                    onQueryChange = { onIntent(GarageIntent.Search(it)) },
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.garage_instruction), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> LoadingState()
                state.entries.isEmpty() -> EmptyState()
                state.byMake.isEmpty() -> NoMatchesState()
                else -> GarageList(byMake = state.byMake, onSelect = { onIntent(GarageIntent.Select(it)) })
            }
        }

        BannerAd(Modifier.fillMaxWidth())
    }
}

/**
 * The grouped, filtered catalog: a [LazyColumn] with one [stickyHeader] per make, plus the A-Z
 * rail pinned to the right edge for jumping straight to a manufacturer.
 */
@Composable
private fun GarageList(byMake: Map<String, List<CatalogEntry>>, onSelect: (CatalogEntry) -> Unit) {
    val rows = remember(byMake) { garageRows(byMake) }
    val index = remember(rows) { letterIndex(rows) }
    val letters = remember(index) { index.map { it.first } }
    val headerIndexByLetter = remember(index) { index.toMap() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var draggedLetter by remember { mutableStateOf<Char?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 36.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((make, entries) in byMake) {
                stickyHeader(key = make) { GarageMakeHeader(make) }
                items(entries, key = { it.displayName }) { entry ->
                    // Brand names, not translated — see CatalogEntry.displayName.
                    VehicleRow(
                        displayName = entry.displayName,
                        onClick = { onSelect(entry) },
                    )
                }
            }
        }

        if (letters.isNotEmpty()) {
            AlphabetRail(
                letters = letters,
                onLetterSelected = { letter ->
                    val itemIndex = headerIndexByLetter[letter] ?: return@AlphabetRail
                    scope.launch { listState.scrollToItem(itemIndex) }
                },
                onDragLetterChange = { draggedLetter = it },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        draggedLetter?.let { letter ->
            LetterBubble(letter, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** The sticky section header for one manufacturer's group of vehicles. */
@Composable
private fun GarageMakeHeader(make: String) {
    Text(
        text = make,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
    )
}

/**
 * A slim A-Z rail pinned to the list's right edge. Tapping or dragging over a letter calls
 * [onLetterSelected]; [onDragLetterChange] reports the letter currently under the finger (or
 * null once the touch ends) so the caller can show/hide the big overlay bubble.
 */
@Composable
private fun AlphabetRail(
    letters: List<Char>,
    onLetterSelected: (Char) -> Unit,
    onDragLetterChange: (Char?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var railHeightPx by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var position = down.position.y

                    fun report() {
                        letterAt(letters, position, railHeightPx)?.let { letter ->
                            onDragLetterChange(letter)
                            onLetterSelected(letter)
                        }
                    }
                    report()

                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null) {
                            position = change.position.y
                            change.consume()
                            report()
                            pressed = change.pressed
                        } else {
                            pressed = false
                        }
                    }
                    onDragLetterChange(null)
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** The big centered "current letter" cue shown while dragging across the [AlphabetRail]. */
@Composable
private fun LetterBubble(letter: Char, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** One vehicle in the catalog, styled like the connect screen's adapter rows. */
@Composable
private fun VehicleRow(displayName: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The signalset download in flight, in the same card language as the connect screen's spinner. */
@Composable
private fun DownloadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.garage_downloading), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Offline or failed download outcome, an inline hint matching the dashboard's health warning. */
@Composable
private fun MessageHint(message: DownloadMessage) {
    val isError = message is DownloadMessage.Failed
    val tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            if (isError) Icons.Rounded.WarningAmber else Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                when (message) {
                    DownloadMessage.Offline -> Res.string.garage_offline
                    DownloadMessage.Failed -> Res.string.garage_download_failed
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

/** Filters the (possibly 654-long) catalog down to what the user is looking for. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.garage_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
    )
}

/** The catalog is still being read off the bundled asset — the same spinner [DownloadingCard] uses. */
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** The search query matched nothing in the catalog. */
@Composable
private fun NoMatchesState() {
    Text(
        text = stringResource(Res.string.garage_no_matches),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        textAlign = TextAlign.Center,
    )
}

/** No vehicles in the curated catalog, styled like the dashboard's empty state. */
@Composable
private fun EmptyState() {
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
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.garage_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
