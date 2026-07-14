package com.bruni.carscan.core.database

import com.bruni.carscan.db.CarScanDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaTest {

    private val driver = createTestDriver()
    private val db: CarScanDb = createDatabase(driver)

    @AfterTest fun tearDown() = driver.close()

    private fun insertVehicle(id: String, vin: String? = null) {
        db.vehicleQueries.insertOrIgnore(
            id = id, vin = vin, make = "Kia", model = "EV6", model_year = 2023,
            obdb_repo = "Kia-EV6", display_name = null, protocol_num = 6,
            last_connected_ms = null, created_ms = 0,
        )
    }

    private fun insertTrip(id: String, vehicleId: String) {
        db.tripQueries.insertOrIgnore(
            id = id, vehicle_id = vehicleId, started_ms = 0, ended_ms = null,
            distance_m = 0.0, fuel_ml = 0.0, energy_wh = 0.0,
            max_speed_kmh = 0.0, idle_ms = 0, sample_count = 0,
        )
    }

    // --- PRAGMAs ---------------------------------------------------------------

    /**
     * foreign_keys is OFF by default in SQLite, and a schema full of REFERENCES
     * clauses that are never enforced looks exactly like one that is. Deleting a
     * vehicle would leave its trips behind as unreachable rows that still count
     * against storage forever.
     */
    @Test
    fun `foreign keys are enforced`() {
        assertEquals("1", driver.queryScalar("PRAGMA foreign_keys;"))
    }

    @Test
    fun `journal mode is WAL on a real file database`() {
        val fileDriver = createTestDriver(tempDbPath("pragma.db"))
        try {
            assertEquals("wal", fileDriver.queryScalar("PRAGMA journal_mode;")?.lowercase())
            assertEquals("1", fileDriver.queryScalar("PRAGMA synchronous;")) // NORMAL
            assertEquals("3000", fileDriver.queryScalar("PRAGMA busy_timeout;"))
            assertEquals("-8000", fileDriver.queryScalar("PRAGMA cache_size;"))
            assertEquals("2", fileDriver.queryScalar("PRAGMA temp_store;")) // MEMORY
        } finally {
            fileDriver.close()
        }
    }

    // --- Foreign keys ----------------------------------------------------------

    @Test
    fun `deleting a vehicle cascades to its trips and their series`() {
        insertVehicle("v1")
        insertTrip("t1", "v1")
        db.tripSeriesQueries.upsert(
            trip_id = "t1", signal_id = "SPEED", chunk_index = 0, t0_s = 0, n = 3,
            series = floatArrayOf(1f, 2f, 3f), min_v = 1.0, max_v = 3.0, avg_v = 2.0,
        )
        db.commandSupportQueries.upsert("v1", "010C", 1, 0, 0)

        assertEquals(1L, db.tripSeriesQueries.countAll().executeAsOne())

        db.vehicleQueries.deleteById("v1")

        assertEquals(0L, db.tripQueries.countAll().executeAsOne())
        assertEquals(0L, db.tripSeriesQueries.countAll().executeAsOne())
        assertEquals(0L, db.commandSupportQueries.countAll().executeAsOne())
    }

    @Test
    fun `a trip cannot reference a vehicle that does not exist`() {
        assertFailsWith<Exception> { insertTrip("t1", "nope") }
    }

    // --- Uniqueness / idempotency ---------------------------------------------

    /**
     * A VIN identifies a physical car, so the unique index means a second row for the
     * same VIN can never exist — and because the insert is OR IGNORE, the duplicate is
     * *dropped rather than raised*. The original row survives untouched, which is the
     * behaviour restoring a backup needs.
     *
     * The consequence for callers is the point of this test: a repository must resolve
     * a car by VIN before minting a new UUID for it, because inserting one under a
     * fresh id will silently do nothing rather than fail.
     */
    @Test
    fun `a second vehicle with the same VIN does not duplicate the car`() {
        insertVehicle("v1", vin = "KNAC381CFN")
        insertVehicle("v2", vin = "KNAC381CFN")

        assertEquals(1L, db.vehicleQueries.countAll().executeAsOne())
        assertEquals("v1", db.vehicleQueries.selectByVin("KNAC381CFN").executeAsOne().id)
    }

    /**
     * The unique index on vin is partial. A vehicle whose VIN could not be read is
     * still a vehicle, and a user may well have two of them.
     */
    @Test
    fun `several vehicles may have no VIN at all`() {
        insertVehicle("v1", vin = null)
        insertVehicle("v2", vin = null)
        assertEquals(2L, db.vehicleQueries.countAll().executeAsOne())
    }

    @Test
    fun `only one DTC per vehicle, code and ECU may be open at a time`() {
        insertVehicle("v1")
        db.dtcEventQueries.insertOrIgnore("d1", "v1", "P0420", "stored", "7E8", 100, 100, null, null)
        // Same code, same ECU, still open — must not create a second row.
        db.dtcEventQueries.insertOrIgnore("d2", "v1", "P0420", "stored", "7E8", 200, 200, null, null)
        assertEquals(1L, db.dtcEventQueries.countAll().executeAsOne())

        // A different ECU reporting the same code is a genuinely different fault.
        db.dtcEventQueries.insertOrIgnore("d3", "v1", "P0420", "stored", "7E9", 200, 200, null, null)
        assertEquals(2L, db.dtcEventQueries.countAll().executeAsOne())
    }

    @Test
    fun `a cleared DTC may come back as a new event`() {
        insertVehicle("v1")
        db.dtcEventQueries.insertOrIgnore("d1", "v1", "P0420", "stored", "7E8", 100, 100, null, null)
        db.dtcEventQueries.clearAllForVehicle(cleared_ms = 150, vehicle_id = "v1")
        db.dtcEventQueries.insertOrIgnore("d2", "v1", "P0420", "stored", "7E8", 200, 200, null, null)

        assertEquals(2L, db.dtcEventQueries.countAll().executeAsOne())
        assertEquals(1, db.dtcEventQueries.selectOpenForVehicle("v1").executeAsList().size)
    }

    // --- UUID idempotency ------------------------------------------------------

    /**
     * Restoring the same backup twice must not double the user's garage. This is
     * only possible because the ids are UUIDs — with autoincrement keys the two
     * imports would land on different rows by construction.
     */
    @Test
    fun `importing the same vehicle twice produces one vehicle`() {
        insertVehicle("v1", vin = "KNAC381CFN")
        insertVehicle("v1", vin = "KNAC381CFN")
        assertEquals(1L, db.vehicleQueries.countAll().executeAsOne())
    }

    /**
     * INSERT OR IGNORE rather than OR REPLACE: REPLACE deletes the conflicting row
     * first, and ON DELETE CASCADE would take the trip's whole series with it. An
     * import that erases the data it is importing is worse than one that fails.
     */
    @Test
    fun `re-importing a trip does not destroy the series already stored under it`() {
        insertVehicle("v1")
        insertTrip("t1", "v1")
        db.tripSeriesQueries.upsert("t1", "SPEED", 0, 0, 3, floatArrayOf(1f, 2f, 3f), 1.0, 3.0, 2.0)

        insertTrip("t1", "v1") // same UUID again

        assertEquals(1L, db.tripQueries.countAll().executeAsOne())
        assertEquals(1L, db.tripSeriesQueries.countAll().executeAsOne())
    }

    // --- Columnar shape --------------------------------------------------------

    @Test
    fun `a series chunk round trips through the BLOB column`() {
        insertVehicle("v1")
        insertTrip("t1", "v1")
        val values = FloatArray(600) { it * 0.25f }
        db.tripSeriesQueries.upsert("t1", "SPEED", 0, 0, 600, values, 0.0, 149.75, 74.875)

        val row = db.tripSeriesQueries.selectChunk("t1", "SPEED", 0).executeAsOne()
        assertTrue(values.contentEquals(row.series))
        assertEquals(600L, row.n)
    }

    @Test
    fun `a global dashboard layout has no vehicle and survives vehicle deletion`() {
        insertVehicle("v1")
        db.dashboardLayoutQueries.insertOrIgnore("l-global", null, "Default", 1, "{}")
        db.dashboardLayoutQueries.insertOrIgnore("l-v1", "v1", "EV6", 0, "{}")

        db.vehicleQueries.deleteById("v1")

        val remaining = db.dashboardLayoutQueries.selectAll().executeAsList()
        assertEquals(1, remaining.size)
        assertEquals("l-global", remaining.single().id)
        assertNull(remaining.single().vehicle_id)
    }

    @Test
    fun `the OBDb signalset is stored as opaque text and comes back byte for byte`() {
        val json = """{"signals":[{"id":"ENGINE_RPM","fmt":{"len":16,"div":4}}]}"""
        db.signalsetQueries.upsert("Kia-EV6", "default", "W/\"abc\"", 1234, json)
        assertEquals(json, db.signalsetQueries.selectByRepoVariant("Kia-EV6", "default").executeAsOne().json)
    }
}
