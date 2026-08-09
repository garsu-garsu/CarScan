package com.bruni.carscan.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.data.backup.BackupOutcome
import com.bruni.carscan.core.data.backup.BackupSink
import com.bruni.carscan.core.data.backup.BackupSource
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.backup_error_failed
import com.bruni.carscan.core.designsystem.generated.resources.backup_error_unsupported_version
import com.bruni.carscan.core.designsystem.generated.resources.backup_error_wrong_password
import com.bruni.carscan.core.designsystem.generated.resources.backup_ok
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_confirm_label
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_hint_export
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_hint_import
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_label
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_mismatch
import com.bruni.carscan.core.designsystem.generated.resources.backup_password_too_short
import com.bruni.carscan.core.designsystem.generated.resources.backup_start
import com.bruni.carscan.core.designsystem.generated.resources.backup_success_export
import com.bruni.carscan.core.designsystem.generated.resources.backup_success_import
import com.bruni.carscan.core.designsystem.generated.resources.backup_working_export
import com.bruni.carscan.core.designsystem.generated.resources.backup_working_import
import com.bruni.carscan.core.designsystem.generated.resources.common_cancel
import com.bruni.carscan.core.designsystem.generated.resources.settings_backup_export
import com.bruni.carscan.core.designsystem.generated.resources.settings_backup_import
import org.jetbrains.compose.resources.stringResource

/** The shortest password that is worth deriving a key from. Anything less is a formality. */
private const val MIN_PASSWORD_LENGTH = 8

/**
 * The password prompt, the file chooser and the result, driven by [step].
 *
 * Emitted as a sibling of the settings list rather than inside it: a dialog hosted by a
 * `LazyColumn` item disappears the moment that item scrolls out of the composition.
 */
@Composable
fun BackupFlow(
    step: BackupStep?,
    onIntent: (SettingsIntent) -> Unit,
    onExportFile: suspend (BackupSink) -> Unit,
    onImportFile: suspend (BackupSource) -> Unit,
) {
    val launcher = rememberBackupLauncher(
        onExportFile = onExportFile,
        onImportFile = onImportFile,
        onCancelled = { onIntent(SettingsIntent.DismissBackup) },
    )

    LaunchedEffect(step) {
        if (step is BackupStep.Picking) {
            when (step.mode) {
                BackupMode.EXPORT -> launcher.pickExportFile()
                BackupMode.IMPORT -> launcher.pickImportFile()
            }
        }
    }

    when (step) {
        null, is BackupStep.Picking -> Unit
        is BackupStep.Password -> PasswordDialog(step.mode, onIntent)
        is BackupStep.Working -> WorkingDialog(step.mode)
        is BackupStep.Done -> DoneDialog(step, onIntent)
    }
}

@Composable
private fun PasswordDialog(mode: BackupMode, onIntent: (SettingsIntent) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    val tooShort = password.length < MIN_PASSWORD_LENGTH
    // Only on the way out. Mistyping the password of a file that already exists is answered by
    // the file itself failing to open, and asking twice there would just be in the way.
    val mismatched = mode == BackupMode.EXPORT && confirmation != password

    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissBackup) },
        title = {
            Text(
                stringResource(
                    if (mode == BackupMode.EXPORT) Res.string.settings_backup_export
                    else Res.string.settings_backup_import,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (mode == BackupMode.EXPORT) Res.string.backup_password_hint_export
                        else Res.string.backup_password_hint_import,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode == BackupMode.EXPORT) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(Res.string.backup_password_confirm_label)) },
                        singleLine = true,
                        isError = confirmation.isNotEmpty() && mismatched,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (password.isNotEmpty() && tooShort) {
                    Text(
                        stringResource(Res.string.backup_password_too_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (confirmation.isNotEmpty() && mismatched) {
                    Text(
                        stringResource(Res.string.backup_password_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onIntent(SettingsIntent.ConfirmBackupPassword(password)) },
                enabled = !tooShort && !mismatched,
            ) {
                Text(stringResource(Res.string.backup_start))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissBackup) }) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/** Not dismissible: a restore interrupted half way is recoverable, but confusing to look at. */
@Composable
private fun WorkingDialog(mode: BackupMode) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(16.dp))
                Text(
                    stringResource(
                        if (mode == BackupMode.EXPORT) Res.string.backup_working_export
                        else Res.string.backup_working_import,
                    ),
                )
            }
        },
    )
}

@Composable
private fun DoneDialog(step: BackupStep.Done, onIntent: (SettingsIntent) -> Unit) {
    val message = when (step.outcome) {
        BackupOutcome.OK ->
            if (step.mode == BackupMode.EXPORT) Res.string.backup_success_export
            else Res.string.backup_success_import
        BackupOutcome.WRONG_PASSWORD -> Res.string.backup_error_wrong_password
        BackupOutcome.UNSUPPORTED_VERSION -> Res.string.backup_error_unsupported_version
        BackupOutcome.FAILED -> Res.string.backup_error_failed
    }
    AlertDialog(
        onDismissRequest = { onIntent(SettingsIntent.DismissBackup) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = { onIntent(SettingsIntent.DismissBackup) }) {
                Text(stringResource(Res.string.backup_ok))
            }
        },
    )
}
