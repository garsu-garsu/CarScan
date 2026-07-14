package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLayoutRepositoryTest {

    private val driver = createTestDriver()
    private val db: CarScanDb = createDatabase(driver).also { it.seedVehicle() }
    private val repo = DefaultDashboardLayoutRepository(db)

    private fun layout(id: String, vehicleId: String?, name: String, active: Boolean = false) =
        DashboardLayout(id, vehicleId, name, active, """{"tiles":[]}""")

    /** Before a car is paired there is nothing to key a layout on, so a layout with no vehicle is a real thing. */
    @Test
    fun `a global layout applies to a vehicle that has none of its own`() = runTest {
        repo.save(layout("l-global", null, "Default", active = true))

        assertEquals("l-global", repo.activeFor(VEHICLE)?.id)
        assertEquals(listOf("l-global"), repo.forVehicle(VEHICLE).map { it.id })
    }

    /** A layout built for this car wins over the generic one. */
    @Test
    fun `a vehicle's own layout is preferred over the global one`() = runTest {
        repo.save(layout("l-global", null, "Default", active = true))
        repo.save(layout("l-ev6", VEHICLE, "EV6", active = true))

        assertEquals("l-ev6", repo.activeFor(VEHICLE)?.id)
    }

    @Test
    fun `activating a layout deactivates the others`() = runTest {
        repo.save(layout("l-a", VEHICLE, "A", active = true))
        repo.save(layout("l-b", VEHICLE, "B"))

        repo.activate("l-b", VEHICLE)

        assertEquals("l-b", repo.activeFor(VEHICLE)?.id)
        assertEquals(1, repo.forVehicle(VEHICLE).count { it.isActive })
    }

    @Test
    fun `saving the same layout id twice updates it rather than duplicating it`() = runTest {
        repo.save(layout("l-a", VEHICLE, "A"))
        repo.save(layout("l-a", VEHICLE, "A renamed"))

        val all = repo.forVehicle(VEHICLE)
        assertEquals(1, all.size)
        assertEquals("A renamed", all.single().name)
    }

    @Test
    fun `the layout json is stored opaquely`() = runTest {
        val json = """{"tiles":[{"key":{"signal":"ENGINE_RPM"},"x":0,"y":0,"gauge":"arc"}]}"""
        repo.save(DashboardLayout("l-a", VEHICLE, "A", true, json))
        assertEquals(json, repo.activeFor(VEHICLE)!!.layoutJson)
    }

    @Test
    fun `with no layouts at all there is no active one`() = runTest {
        assertNull(repo.activeFor(VEHICLE))
        assertTrue(repo.forVehicle(VEHICLE).isEmpty())
    }

    @Test
    fun `deleting a layout removes it`() = runTest {
        repo.save(layout("l-a", VEHICLE, "A", active = true))
        repo.delete("l-a")
        assertNull(repo.activeFor(VEHICLE))
    }
}
