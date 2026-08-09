package com.bruni.carscan.feature.settings

import androidx.compose.runtime.Composable
import com.bruni.carscan.core.data.backup.BackupSink
import com.bruni.carscan.core.data.backup.BackupSource

/** Opens the platform's file chooser. Both calls return immediately; the result arrives later. */
interface BackupLauncher {
    fun pickExportFile()
    fun pickImportFile()
}

/**
 * Platform file picking, wired to the two backup operations. Android needs the Storage Access
 * Framework here; there is no multiplatform file chooser, so this follows the same
 * `expect`/`actual` shape as `:feature:hud`'s `HudDisplayEffect`.
 *
 * The two callbacks are **suspending, and the file stays open for as long as they run**. That is
 * the whole reason they are not intents: a SAF stream only lives inside the block that opened it,
 * so the work has to happen there rather than being posted somewhere and awaited.
 *
 * [onCancelled] fires when the user backs out of the chooser, which is the only way out of the
 * "waiting for a file" state.
 */
@Composable
expect fun rememberBackupLauncher(
    onExportFile: suspend (BackupSink) -> Unit,
    onImportFile: suspend (BackupSource) -> Unit,
    onCancelled: () -> Unit,
): BackupLauncher
