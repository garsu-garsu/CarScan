package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetProvider
import com.bruni.carscan.core.data.Vehicle
import com.bruni.carscan.core.data.VehicleCatalog
import com.bruni.carscan.core.data.VehicleRepository
import kotlinx.coroutines.launch

/**
 * The garage: picking a vehicle from the curated catalog makes it the active one, so the rest
 * of the app knows which OBDb signalset to load.
 *
 * The catalog is read once, at construction — [VehicleCatalog.all] is a bundled, in-memory list,
 * not something that changes underneath a running screen.
 */
class GarageViewModel(
    catalog: VehicleCatalog,
    private val vehicles: VehicleRepository,
    private val settings: SettingsRepository,
    private val signalsets: SignalsetProvider,
    private val now: () -> Long,
    private val newId: () -> String,
) : MviViewModel<GarageState, GarageIntent, GarageEffect>(
    GarageState(entries = catalog.all()),
) {

    override fun onIntent(intent: GarageIntent) = when (intent) {
        is GarageIntent.Select -> select(intent.entry)
    }

    private fun select(entry: CatalogEntry) {
        scope.launch {
            val vehicle = Vehicle(
                id = newId(),
                make = entry.make,
                model = entry.model,
                // A representative year for the skeleton — a year picker can come later.
                modelYear = entry.maxYear.toLong(),
                obdbRepo = entry.obdbRepo,
                displayName = entry.displayName,
                createdMs = now(),
            )
            // Recorded and made active now, even if the download below fails — the choice
            // sticks, the connect path falls back to standard PIDs, and this can be retried.
            vehicles.remember(vehicle)
            settings.setActiveVehicleId(vehicle.id)

            setState { copy(downloading = entry.obdbRepo, message = null) }
            val availability = signalsets.ensureAvailable(entry.obdbRepo)
            setState { copy(downloading = null) }

            when (availability) {
                SignalsetAvailability.AVAILABLE, SignalsetAvailability.DOWNLOADED ->
                    emitEffect(GarageEffect.Selected)
                SignalsetAvailability.NO_NETWORK ->
                    setState { copy(message = DownloadMessage.Offline) }
                SignalsetAvailability.NOT_FOUND, SignalsetAvailability.FAILED ->
                    setState { copy(message = DownloadMessage.Failed) }
            }
        }
    }
}
