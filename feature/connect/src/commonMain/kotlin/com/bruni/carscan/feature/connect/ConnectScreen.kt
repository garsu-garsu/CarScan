package com.bruni.carscan.feature.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.ads.BannerAd
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_ble
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_spp
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_wifi
import com.bruni.carscan.core.designsystem.generated.resources.adapter_unnamed
import com.bruni.carscan.core.designsystem.generated.resources.common_retry
import com.bruni.carscan.core.designsystem.generated.resources.common_settings
import com.bruni.carscan.core.designsystem.generated.resources.connect_budget
import com.bruni.carscan.core.designsystem.generated.resources.connect_change_method
import com.bruni.carscan.core.designsystem.generated.resources.connect_choose_method
import com.bruni.carscan.core.designsystem.generated.resources.connect_clone
import com.bruni.carscan.core.designsystem.generated.resources.connect_connect
import com.bruni.carscan.core.designsystem.generated.resources.connect_disconnect
import com.bruni.carscan.core.designsystem.generated.resources.connect_genuine_stn
import com.bruni.carscan.core.designsystem.generated.resources.connect_no_adapters
import com.bruni.carscan.core.designsystem.generated.resources.connect_proceed
import com.bruni.carscan.core.designsystem.generated.resources.connect_protocol
import com.bruni.carscan.core.designsystem.generated.resources.connect_scan
import com.bruni.carscan.core.designsystem.generated.resources.connect_scanning
import com.bruni.carscan.core.designsystem.generated.resources.connect_wifi_host
import com.bruni.carscan.core.designsystem.generated.resources.connect_wifi_port
import com.bruni.carscan.core.designsystem.generated.resources.connection_state_connecting
import com.bruni.carscan.core.designsystem.generated.resources.dashboard_tiles
import com.bruni.carscan.core.designsystem.generated.resources.error_adapter_unreachable
import com.bruni.carscan.core.designsystem.generated.resources.error_bluetooth_off
import com.bruni.carscan.core.designsystem.generated.resources.error_bluetooth_permission
import com.bruni.carscan.core.designsystem.generated.resources.error_ignition_off
import com.bruni.carscan.core.designsystem.generated.resources.error_init_failed
import com.bruni.carscan.core.designsystem.generated.resources.error_spp_pairing_required
import com.bruni.carscan.core.designsystem.generated.resources.error_wifi_not_joined
import com.bruni.carscan.core.designsystem.generated.resources.health_queries_per_second
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectScreen(
    state: ConnectState,
    onIntent: (ConnectIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val selectedKind = state.selectedKind

            if (selectedKind == null) {
                methodPicker(state, onIntent)
            } else {
                scanStep(state, selectedKind, onIntent)
            }
        }

        BannerAd(Modifier.fillMaxWidth())
    }
}

/** Step 1: pick exactly one method. Only the ones this platform actually has — see [ConnectState.availableKinds]. */
private fun LazyListScope.methodPicker(
    state: ConnectState,
    onIntent: (ConnectIntent) -> Unit,
) {
    item {
        Text(stringResource(Res.string.connect_choose_method), style = MaterialTheme.typography.headlineSmall)
    }

    items(state.availableKinds, key = { it }) { kind ->
        MethodCard(kind, onClick = { onIntent(ConnectIntent.SelectMethod(kind)) })
    }
}

/** Step 2: the chosen method's scan/list, or the Wi-Fi entry form — plus the ready/failure UI shared by both. */
private fun LazyListScope.scanStep(
    state: ConnectState,
    selectedKind: TransportKind,
    onIntent: (ConnectIntent) -> Unit,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onIntent(ConnectIntent.BackToMethods) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.connect_change_method))
            }

            Text(
                text = stringResource(selectedKind.label()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Wi-Fi is a fixed access point, not something to scan for — the form below has its
            // own Connect button instead.
            if (selectedKind == TransportKind.WIFI) {
                Spacer(Modifier.width(1.dp))
            } else {
                Button(
                    onClick = { onIntent(ConnectIntent.Scan) },
                    enabled = !state.isScanning && state.connectingTo == null,
                ) {
                    Text(
                        stringResource(
                            if (state.isScanning) Res.string.connect_scanning else Res.string.connect_scan,
                        ),
                    )
                }
            }
        }
    }

    state.failure?.let { failure ->
        item { FailureCard(failure, onIntent) }
    }

    state.ready?.let { ready ->
        item { ReadoutCard(
            ready,
            state.throughput,
            onProceed = { onIntent(ConnectIntent.Proceed) },
            onDisconnect = { onIntent(ConnectIntent.Disconnect) },
        ) }
    }

    state.connectingTo?.let { target ->
        item { ConnectingCard(target) }
    }

    if (selectedKind == TransportKind.WIFI) {
        item {
            WifiForm(onConnect = { host, port -> onIntent(ConnectIntent.ConnectWifi(host, port)) })
        }
    } else {
        items(state.adapters, key = { it.address }) { adapter ->
            AdapterRow(adapter, onClick = { onIntent(ConnectIntent.Select(adapter)) })
        }

        if (!state.hasAdapters && !state.isScanning) {
            item {
                Text(
                    text = stringResource(Res.string.connect_no_adapters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One method, styled like the home launcher's list rows: icon badge + name. */
@Composable
private fun MethodCard(kind: TransportKind, onClick: () -> Unit) {
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
                Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(kind.label()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Wi-Fi has nothing to scan for — every one of these adapters is its own fixed access point, and
 * almost none of them let you change the address — so the host and port are typed in instead,
 * prefilled with the value that works for nearly everyone.
 *
 * The prefill mirrors composeApp's `Transports.DEFAULT_WIFI_ADDRESS` ("192.168.0.10:35000"); it
 * is hardcoded here rather than threaded through the port because this feature module cannot see
 * :composeApp.
 */
@Composable
private fun WifiForm(onConnect: (host: String, port: Int) -> Unit) {
    var host by remember { mutableStateOf("192.168.0.10") }
    var portText by remember { mutableStateOf("35000") }
    val port = portText.toIntOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(Res.string.connect_wifi_host)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text(stringResource(Res.string.connect_wifi_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { port?.let { onConnect(host, it) } },
                enabled = host.isNotBlank() && port != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.connect_connect))
            }
        }
    }
}

/** A discovered adapter, styled like the home launcher's list rows: icon badge + name + address. */
@Composable
private fun AdapterRow(adapter: DiscoveredAdapter, onClick: () -> Unit) {
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
                Icon(adapter.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = adapter.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = adapter.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The connect-in-progress row, in the same card language as everything else on this screen. */
@Composable
private fun ConnectingCard(target: DiscoveredAdapter) {
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
            Text(
                text = "${stringResource(Res.string.connection_state_connecting)} ${target.displayName()}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * What the adapter turned out to be — and what it can actually deliver.
 *
 * The throughput line is the point of this whole screen. A counterfeit ELM327 does 10–20
 * queries a second in total; eight gauges at 10 Hz need 80. That gap cannot be closed, so
 * the only useful thing the app can do is say so, in units the user can act on. A user who
 * is told *"14 queries/sec — 6 gauges at 2 Hz"* buys a better adapter. A user who is told
 * nothing watches the dashboard stutter and blames us.
 */
@Composable
private fun ReadoutCard(
    ready: ReadyReadout,
    throughput: ThroughputAdvice?,
    onProceed: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = ready.adapter.displayName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = stringResource(
                    if (ready.isStn) Res.string.connect_genuine_stn else Res.string.connect_clone,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Absent when ATDPN would not say. Better a missing line than the word "unknown".
            ready.protocol?.let { protocol ->
                Text(
                    text = stringResource(Res.string.connect_protocol, protocol),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Null until the first round trip has been measured. Saying "0 queries/sec" in
            // that window would libel every adapter on every connect.
            throughput?.let { advice ->
                val rate = pluralStringResource(
                    Res.plurals.health_queries_per_second,
                    advice.queriesPerSec,
                    advice.queriesPerSec,
                )
                val tiles = pluralStringResource(Res.plurals.dashboard_tiles, advice.tiles, advice.tiles)

                Text(
                    text = "$rate — " + stringResource(Res.string.connect_budget, tiles, advice.hz),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // The only way off this screen. Connect is not a tab and pops itself off the stack
            // on the way out, so without this the sole exit is system Back — which leaves
            // Connect on the stack and drops the user onto a scan screen mid-drive.
            Button(
                onClick = onProceed,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(stringResource(Res.string.connect_proceed))
            }

            // Ending the drive is the one thing the user could not do from anywhere in the app.
            // A TextButton rather than a second filled one: next to a full-width primary action,
            // in a car, two equally loud buttons is how the wrong one gets pressed.
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.connect_disconnect))
            }
        }
    }
}

/**
 * A failure the user can do something about — never an ELM error code.
 *
 * [ConnectFailure] is an enum with no payload precisely so that `BUFFER FULL` has nowhere to
 * ride into this card. The quirks are degraded through silently by the session, and the
 * connection succeeds anyway.
 */
@Composable
private fun FailureCard(failure: ConnectFailure, onIntent: (ConnectIntent) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(failure.message()), style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onIntent(ConnectIntent.Retry) }) {
                    Text(stringResource(Res.string.common_retry))
                }

                // Only a refused permission has somewhere useful to send the user. Offering
                // a settings link for "turn the ignition on" would just be noise.
                if (failure == ConnectFailure.BLUETOOTH_PERMISSION) {
                    TextButton(onClick = { onIntent(ConnectIntent.OpenSettings) }) {
                        Text(stringResource(Res.string.common_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredAdapter.displayName(): String = name ?: stringResource(Res.string.adapter_unnamed)

private fun TransportKind.label(): StringResource = when (this) {
    TransportKind.BLE -> Res.string.adapter_kind_ble
    TransportKind.SPP -> Res.string.adapter_kind_spp
    TransportKind.WIFI -> Res.string.adapter_kind_wifi
}

private fun TransportKind.icon(): ImageVector = when (this) {
    TransportKind.BLE, TransportKind.SPP -> Icons.Rounded.Bluetooth
    TransportKind.WIFI -> Icons.Rounded.Wifi
}

private fun ConnectFailure.message(): StringResource = when (this) {
    ConnectFailure.IGNITION_OFF -> Res.string.error_ignition_off
    ConnectFailure.BLUETOOTH_PERMISSION -> Res.string.error_bluetooth_permission
    ConnectFailure.BLUETOOTH_OFF -> Res.string.error_bluetooth_off
    ConnectFailure.SPP_PAIRING_REQUIRED -> Res.string.error_spp_pairing_required
    ConnectFailure.WIFI_NOT_JOINED -> Res.string.error_wifi_not_joined
    ConnectFailure.ADAPTER_UNREACHABLE -> Res.string.error_adapter_unreachable
    // The adapter talked but never became usable, for a reason we cannot turn into an
    // instruction. This is the only entry that admits as much — which is why it is last
    // resort rather than a catch-all.
    ConnectFailure.INIT_FAILED -> Res.string.error_init_failed
}
