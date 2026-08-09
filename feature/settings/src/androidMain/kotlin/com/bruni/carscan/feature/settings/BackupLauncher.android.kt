package com.bruni.carscan.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.bruni.carscan.core.data.backup.BackupSink
import com.bruni.carscan.core.data.backup.BackupSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The extension is ours; the MIME type is deliberately generic so no app claims the file. */
private const val MIME = "application/octet-stream"

/**
 * The Storage Access Framework, which is the only way to write outside the app's own sandbox on
 * modern Android — and writing outside it is the entire point: a backup that lives in the app's
 * private storage dies with the app.
 *
 * `CreateDocument`/`OpenDocument` need no permission at all: the user picking the file *is* the
 * grant. That is why there is no runtime-permission dance here.
 */
@Composable
actual fun rememberBackupLauncher(
    onExportFile: suspend (BackupSink) -> Unit,
    onImportFile: suspend (BackupSource) -> Unit,
    onCancelled: () -> Unit,
): BackupLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        if (uri == null) {
            onCancelled()
        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    // `use` closes the stream, and the callback runs *inside* it — see the
                    // expect declaration. Buffered because the reader walks the header a byte
                    // at a time and every frame is a separate small write.
                    context.contentResolver.openOutputStream(uri)?.buffered()?.use { out ->
                        onExportFile { bytes -> out.write(bytes) }
                    }
                }
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onCancelled()
        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                        onImportFile { destination, offset, length -> input.read(destination, offset, length) }
                    }
                }
            }
        }
    }

    return remember(create, open) {
        object : BackupLauncher {
            override fun pickExportFile() = create.launch(defaultFileName())

            // Not the MIME type above: a file synced through Drive or sent over KakaoTalk comes
            // back with whatever type that service decided on, and filtering on ours would hide
            // the user's own backup from the picker.
            override fun pickImportFile() = open.launch(arrayOf("*/*"))
        }
    }
}

private fun defaultFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    return "carscan-$stamp.backup"
}
