package com.bruni.carscan.obd

import com.bruni.carscan.core.data.newUuid
import com.bruni.carscan.db.CarScanDb

/**
 * The vehicle a trip is hung on.
 *
 * `trip.vehicle_id` is `NOT NULL REFERENCES vehicle(id)`, and :core:data has no vehicle
 * repository, so **without this nothing can insert a trip at all** — the foreign key rejects it.
 * This is composition-root glue standing in for a `VehicleRepository` that does not exist yet, and
 * it should be deleted the day one does.
 *
 * The row it creates is almost empty. Nothing has read a VIN yet (that is `0902`, and no screen
 * asks for it), so a car is identified by nothing but the fact that the user drove it. One row is
 * created on the first recorded drive and reused forever after, which is right for the
 * overwhelmingly common case of a phone that meets exactly one car.
 */
class VehicleRows(
    private val db: CarScanDb,
    private val newId: () -> String = { newUuid() },
) {

    /** The active vehicle's id, creating its row on the first recorded drive. */
    fun current(nowMs: Long): String {
        db.vehicleQueries.selectAll().executeAsList().firstOrNull()?.let { return it.id }

        val id = newId()
        db.vehicleQueries.insertOrIgnore(
            id = id,
            vin = null,
            make = null,
            model = null,
            model_year = null,
            obdb_repo = null,
            display_name = null,
            protocol_num = null,
            last_connected_ms = nowMs,
            created_ms = nowMs,
        )
        return id
    }
}
