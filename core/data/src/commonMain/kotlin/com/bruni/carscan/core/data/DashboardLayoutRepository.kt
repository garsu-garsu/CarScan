package com.bruni.carscan.core.data

import com.bruni.carscan.db.CarScanDb
import com.bruni.carscan.db.Dashboard_layout

/**
 * A saved dashboard.
 *
 * [vehicleId] null means a global layout — one that applies to any car. That is not an
 * edge case: before a user has paired anything there is no vehicle to key a layout on,
 * and the app still has to show them a dashboard.
 *
 * [layoutJson] stays opaque here. Tile geometry and gauge styling belong to the
 * presentation layer, and the schema should not need a migration every time a new
 * gauge type ships.
 */
data class DashboardLayout(
    val id: String,
    val vehicleId: String?,
    val name: String,
    val isActive: Boolean,
    val layoutJson: String,
)

interface DashboardLayoutRepository {
    /** Idempotent on the layout's UUID: saving the same id twice updates, never duplicates. */
    suspend fun save(layout: DashboardLayout)

    /** This vehicle's layouts plus the global ones. */
    suspend fun forVehicle(vehicleId: String): List<DashboardLayout>

    /** The vehicle's own active layout, else the active global one, else null. */
    suspend fun activeFor(vehicleId: String): DashboardLayout?

    suspend fun activate(id: String, vehicleId: String?)

    suspend fun delete(id: String)
}

class DefaultDashboardLayoutRepository(private val db: CarScanDb) : DashboardLayoutRepository {

    override suspend fun save(layout: DashboardLayout) {
        db.transaction {
            // OR IGNORE + UPDATE, not OR REPLACE: REPLACE deletes the row first, and the
            // FK to vehicle is ON DELETE CASCADE.
            db.dashboardLayoutQueries.insertOrIgnore(
                id = layout.id,
                vehicle_id = layout.vehicleId,
                name = layout.name,
                is_active = if (layout.isActive) 1L else 0L,
                layout_json = layout.layoutJson,
            )
            db.dashboardLayoutQueries.update(
                vehicle_id = layout.vehicleId,
                name = layout.name,
                is_active = if (layout.isActive) 1L else 0L,
                layout_json = layout.layoutJson,
                id = layout.id,
            )
        }
    }

    override suspend fun forVehicle(vehicleId: String): List<DashboardLayout> =
        db.dashboardLayoutQueries.selectForVehicle(vehicleId).executeAsList()
            .map(Dashboard_layout::toLayout)

    override suspend fun activeFor(vehicleId: String): DashboardLayout? =
        db.dashboardLayoutQueries.selectActiveForVehicle(vehicleId).executeAsOneOrNull()?.toLayout()

    /** Exactly one layout is active at a time, so both halves go in one transaction. */
    override suspend fun activate(id: String, vehicleId: String?) {
        db.transaction {
            db.dashboardLayoutQueries.deactivateForVehicle(vehicleId)
            db.dashboardLayoutQueries.activate(id)
        }
    }

    override suspend fun delete(id: String) {
        db.dashboardLayoutQueries.deleteById(id)
    }
}

private fun Dashboard_layout.toLayout() = DashboardLayout(
    id = id,
    vehicleId = vehicle_id,
    name = name,
    isActive = is_active != 0L,
    layoutJson = layout_json,
)
