package com.bruni.carscan.feature.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_ble
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_spp
import com.bruni.carscan.core.designsystem.generated.resources.adapter_kind_wifi
import com.bruni.carscan.core.designsystem.generated.resources.adapter_unnamed
import com.bruni.carscan.core.designsystem.generated.resources.common_retry
import com.bruni.carscan.core.designsystem.generated.resources.common_settings
import com.bruni.carscan.core.designsystem.generated.resources.connect_budget
import com.bruni.carscan.core.designsystem.generated.resources.connect_clone
import com.bruni.carscan.core.designsystem.generated.resources.connect_genuine_stn
import com.bruni.carscan.core.designsystem.generated.resources.connect_no_adapters
import com.bruni.carscan.core.designsystem.generated.resources.connect_protocol
import com.bruni.carscan.core.designsystem.generated.resources.connect_scan
import com.bruni.carscan.core.designsystem.generated.resources.connect_scanning
import com.bruni.carscan.core.designsystem.generated.resources.connect_title
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

/** Stable handles for the smoke tests, and the only place they are spelled. */
object ConnectTags {
    const val SCAN = "connect_scan"
    const val THROUGHPUT = "connect_throughput"
    const val PROTOCOL = "connect_protocol"
    const val FAILURE = "connect_failure"
    const val CHIP = "connect_chip"

    fun section(kind: TransportKind) = "connect_section_${kind.name}"
    fun adapter(address: String) = "connect_adapter_$address"
}

@Composable
fun ConnectScreen(
    state: ConnectState,
    onIntent: (ConnectIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.connect_title), style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = { onIntent(ConnectIntent.Scan) },
                    enabled = !state.isScanning && state.connectingTo == null,
                    modifier = Modifier.testTag(ConnectTags.SCAN),
                ) {
                    Text(
                        stringResource(
                            if (state.isScanning) Res.string.connect_scanning else Res.string.connect_scan,
                        ),
                    )
                }
            }
        }

        state.failure?.let { failure ->
            item { FailureCard(failure, onIntent) }
        }

        state.ready?.let { ready ->
            item { ReadoutCard(ready, state.throughput) }
        }

        state.connectingTo?.let { target ->
            item { ConnectingCard(target) }
        }

        // One section per transport this platform actually has. On iOS there is no SPP
        // section here at all — not a disabled one — because Apple will never allow it and a
        // greyed-out row that can never light up just invites the user to keep tapping it.
        state.sections.forEach { section ->
            item(key = section.kind) {
                Text(
                    text = stringResource(section.kind.label()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(ConnectTags.section(section.kind)),
                )
            }

            items(section.adapters, key = { it.address }) { adapter ->
                AdapterRow(adapter, onClick = { onIntent(ConnectIntent.Select(adapter)) })
            }
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

/** A discovered adapter, styled like the home launcher's list rows: icon badge + name + address. */
@Composable
private fun AdapterRow(adapter: DiscoveredAdapter, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConnectTags.adapter(adapter.address)),
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
private fun ReadoutCard(ready: ReadyReadout, throughput: ThroughputAdvice?) {
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
                modifier = Modifier.testTag(ConnectTags.CHIP),
            )

            // Absent when ATDPN would not say. Better a missing line than the word "unknown".
            ready.protocol?.let { protocol ->
                Text(
                    text = stringResource(Res.string.connect_protocol, protocol),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(ConnectTags.PROTOCOL),
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
                    modifier = Modifier.testTag(ConnectTags.THROUGHPUT),
                )
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
        modifier = Modifier.fillMaxWidth().testTag(ConnectTags.FAILURE),
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
