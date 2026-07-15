package com.bruni.carscan.feature.settings

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
import com.bruni.carscan.core.designsystem.generated.resources.about_licenses_heading
import com.bruni.carscan.core.designsystem.generated.resources.about_obdb_attribution
import com.bruni.carscan.core.designsystem.generated.resources.about_obdb_license_url
import com.bruni.carscan.core.designsystem.generated.resources.about_obdb_no_changes
import com.bruni.carscan.core.designsystem.generated.resources.about_version
import com.bruni.carscan.core.designsystem.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource

/**
 * The legal gate before any store upload.
 *
 * Several OBDb signalsets ship in the APK under CC BY-SA 4.0 — `SAEJ1979.json` plus the curated
 * per-vehicle sets (Kia EV6, Ioniq 5, Elantra, F-150); see
 * `composeApp/src/commonMain/composeResources/files/obdb/SOURCE.md`. The attribution below names
 * the OBDb project as a whole rather than any one file, so it covers every bundled set, and BY-SA's
 * distribution obligation is exactly the three lines below: attribution, a link to the license, and
 * a statement of whether the data was changed (it was not — all are verbatim copies). The URL and
 * "CC BY-SA 4.0" are passed in as format arguments rather than written into the translated
 * sentence, so no translation can touch either.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(Res.string.about_version, APP_VERSION), style = MaterialTheme.typography.bodyMedium) }

        item {
            Text(
                text = stringResource(Res.string.about_obdb_attribution, OBDB_SOURCE_URL, OBDB_LICENSE_NAME),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Text(
                text = stringResource(Res.string.about_obdb_license_url, OBDB_LICENSE_URL),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { Text(stringResource(Res.string.about_obdb_no_changes), style = MaterialTheme.typography.bodyMedium) }

        item {
            Text(
                text = stringResource(Res.string.about_licenses_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(OSS_LICENSES) { (name, license) ->
            Text("$name — $license", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// A placeholder until this is wired to the platform's own version string.
private const val APP_VERSION = "1.0.0"

private const val OBDB_SOURCE_URL = "https://github.com/OBDb"
private const val OBDB_LICENSE_NAME = "CC BY-SA 4.0"
private const val OBDB_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

/** Library name and license identifier — proper nouns and SPDX ids, not sentences, so they are not translated. */
private val OSS_LICENSES = listOf(
    "Kable" to "Apache-2.0",
    "Koin" to "Apache-2.0",
    "SQLDelight" to "Apache-2.0",
    "Ktor" to "Apache-2.0",
    "Vico" to "Apache-2.0",
    "Compose Multiplatform" to "Apache-2.0",
    "kotlinx (coroutines, serialization, datetime)" to "Apache-2.0",
)
