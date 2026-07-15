package com.bruni.carscan.feature.garage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.garage_download_failed
import com.bruni.carscan.core.designsystem.generated.resources.garage_downloading
import com.bruni.carscan.core.designsystem.generated.resources.garage_empty
import com.bruni.carscan.core.designsystem.generated.resources.garage_instruction
import com.bruni.carscan.core.designsystem.generated.resources.garage_offline
import com.bruni.carscan.core.designsystem.generated.resources.garage_title
import org.jetbrains.compose.resources.stringResource

/** A tappable list of the curated catalog, grouped by make. Picking a row selects that vehicle. */
@Composable
fun GarageScreen(
    state: GarageState,
    onIntent: (GarageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(Res.string.garage_title), style = MaterialTheme.typography.headlineSmall) }

        if (state.downloading != null) {
            item { Text(stringResource(Res.string.garage_downloading), style = MaterialTheme.typography.bodyMedium) }
        }
        state.message?.let { message ->
            item {
                Text(
                    text = stringResource(
                        when (message) {
                            DownloadMessage.Offline -> Res.string.garage_offline
                            DownloadMessage.Failed -> Res.string.garage_download_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.entries.isEmpty()) {
            item { Text(stringResource(Res.string.garage_empty)) }
        } else {
            item { Text(stringResource(Res.string.garage_instruction), style = MaterialTheme.typography.bodyMedium) }

            for ((make, models) in state.byMake) {
                item {
                    Text(
                        text = make,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(models, key = { it.displayName }) { entry ->
                    // Brand names, not translated — see CatalogEntry.displayName.
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(GarageIntent.Select(entry)) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
