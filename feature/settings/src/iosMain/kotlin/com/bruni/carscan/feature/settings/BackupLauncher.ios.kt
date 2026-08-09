package com.bruni.carscan.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bruni.carscan.core.data.backup.BackupSink
import com.bruni.carscan.core.data.backup.BackupSource

/**
 * **Never compiled.** Apple targets are only registered on a macOS host — see `:feature:hud`'s
 * `HudDisplayEffect.ios.kt`.
 *
 * Not implemented, and it says so by immediately calling [onCancelled] rather than pretending a
 * chooser opened: the UI then falls straight back out of the "waiting for a file" state instead
 * of hanging on a picker that never appeared. The real implementation is
 * `UIDocumentPickerViewController` (`forExportingURLs:` to save, `forOpeningContentTypes:` to
 * load) bridged through a `UIViewControllerRepresentable`, plus `NSFileHandle` for the streams —
 * and note that `BackupCrypto.ios.kt` has to be written first, since there is no cipher on iOS
 * to write a file with.
 */
@Composable
actual fun rememberBackupLauncher(
    onExportFile: suspend (BackupSink) -> Unit,
    onImportFile: suspend (BackupSource) -> Unit,
    onCancelled: () -> Unit,
): BackupLauncher = remember(onCancelled) {
    object : BackupLauncher {
        override fun pickExportFile() = onCancelled()
        override fun pickImportFile() = onCancelled()
    }
}
