package com.bruni.carscan.core.data

import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The vehicles the user has connected to.
 *
 * The `vehicle` table has existed since M3 and nothing ever wrote to it — which meant
 * `TripRepository.start()` could not be called at all, because `trip.vehicle_id` is
 * `NOT NULL REFERENCES vehicle(id)`. Recording was unreachable by construction, and no test
 * noticed because every test supplied its own vehicle row.
 */
data class Vehicle(
    /** UUID, not autoincrement: a restored backup must not collide with local rows. */
    val id: String,
    val vin: String? = null,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Long? = null,
    /** The OBDb repository slug, e.g. "Kia-EV6". Null until the user picks a vehicle. */
    val obdbRepo: String? = null,
    val displayName: String? = null,
    /** ATDPN. Seeds the session so a reconnect skips the protocol search. */
    val protocolNum: Long? = null,
    val lastConnectedMs: Long? = null,
    val createdMs: Long,
)

interface VehicleRepository {
    suspend fun all(): List<Vehicle>

    suspend fun byId(id: String): Vehicle?

    /**
     * A car with no readable VIN is still a car, and there may be several of them — the unique
     * index on `vin` is partial for exactly that reason. So this returns null rather than
     * inventing an identity.
     */
    suspend fun byVin(vin: String): Vehicle?

    /** Idempotent: `INSERT OR IGNORE`, so restoring the same backup twice yields one vehicle. */
    suspend fun remember(vehicle: Vehicle)

    suspend fun touchLastConnected(id: String, atMs: Long)

    suspend fun forget(id: String)
}

class DefaultVehicleRepository(private val db: CarScanDb) : VehicleRepository {

    override suspend fun all(): List<Vehicle> = withContext(Dispatchers.IO) {
        db.vehicleQueries.selectAll().executeAsList().map { it.toVehicle() }
    }

    override suspend fun byId(id: String): Vehicle? = withContext(Dispatchers.IO) {
        db.vehicleQueries.selectById(id).executeAsOneOrNull()?.toVehicle()
    }

    override suspend fun byVin(vin: String): Vehicle? = withContext(Dispatchers.IO) {
        db.vehicleQueries.selectByVin(vin).executeAsOneOrNull()?.toVehicle()
    }

    override suspend fun remember(vehicle: Vehicle): Unit = withContext(Dispatchers.IO) {
        db.vehicleQueries.insertOrIgnore(
            id = vehicle.id,
            vin = vehicle.vin,
            make = vehicle.make,
            model = vehicle.model,
            model_year = vehicle.modelYear,
            obdb_repo = vehicle.obdbRepo,
            display_name = vehicle.displayName,
            protocol_num = vehicle.protocolNum,
            last_connected_ms = vehicle.lastConnectedMs,
            created_ms = vehicle.createdMs,
        )
    }

    override suspend fun touchLastConnected(id: String, atMs: Long): Unit = withContext(Dispatchers.IO) {
        db.vehicleQueries.touchLastConnected(atMs, id)
    }

    override suspend fun forget(id: String): Unit = withContext(Dispatchers.IO) {
        db.vehicleQueries.deleteById(id)
    }
}

private fun com.bruni.carscan.db.Vehicle.toVehicle() = Vehicle(
    id = id,
    vin = vin,
    make = make,
    model = model,
    modelYear = model_year,
    obdbRepo = obdb_repo,
    displayName = display_name,
    protocolNum = protocol_num,
    lastConnectedMs = last_connected_ms,
    createdMs = created_ms,
)
