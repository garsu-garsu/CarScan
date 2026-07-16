package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one attempt, at launch, to reconnect to the scanner the user actually got working last time
 * — so plugging into the same car again does not mean re-running the picker.
 *
 * Deliberately a single shot, not a retry loop. [ObdConnector.connect] already pays for a full
 * protocol probe on a cold bus; looping it against a car that is not there yet (ignition still
 * off) would mean the app hammering a socket in the background while the dashboard has nothing to
 * show for it. One attempt costs little and falls back to the manual picker exactly as if
 * auto-reconnect did not exist — which is the only behavior a background task is allowed to have
 * when it fails.
 */
class AutoConnector(
    private val connector: ObdConnector,
    private val source: SampleSource,
    private val adapters: AdapterRepository,
    private val settings: SettingsRepository,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            if (!settings.settings.first().autoReconnect) return@launch
            // A manual connect already in flight, or already connected, owns the session —
            // auto-reconnect must never race it for the one thing half-duplex allows: one socket.
            if (source.health.value.connection == ConnectionState.CONNECTED) return@launch

            val remembered = adapters.lastUsed() ?: return@launch
            val target = DiscoveredAdapter(
                kind = TransportKind.valueOf(remembered.kind),
                address = remembered.address,
                name = remembered.name,
            )

            try {
                connector.connect(target, remembered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Swallowed on purpose — see the class KDoc. The manual picker is still right there.
            }
        }
    }
}
