package com.bruni.carscan.nav

import kotlinx.serialization.Serializable

/**
 * Every destination in the app, in one place.
 *
 * `@Serializable` rather than string routes: a typo in `"live/{key}"` is a runtime crash on a
 * screen the user reached, and a missing argument is a silently-null `String?`. These are checked
 * by the compiler.
 *
 * A **single flat graph**, deliberately. Nested graphs exist to scope a ViewModel's lifetime to a
 * subtree, and nothing here wants that — the OBD session outlives every screen and lives in the
 * application scope. Nesting would only add a second place for a back stack to be wrong.
 */
sealed interface Route {

    /** The launcher. Every feature, including connecting an adapter, is reached from here. */
    @Serializable
    data object Home : Route

    @Serializable
    data object Connect : Route

    @Serializable
    data object Dashboard : Route

    /**
     * [metricKey] is the key the user tapped a tile for, encoded as `MetricKey`'s discriminated
     * string form. Null when the screen was reached from the tab bar rather than from a tile.
     */
    @Serializable
    data class Live(val metricKey: String? = null) : Route

    @Serializable
    data object Hud : Route

    @Serializable
    data object Trips : Route

    /** One trip's detail: its route on a map, and the harsh-driving events on it. */
    @Serializable
    data class TripDetail(val tripId: String) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object About : Route

    /** The garage / vehicle picker. Reached from Settings; picking a vehicle pops back. */
    @Serializable
    data object Garage : Route

    /** The paywall. Reached from Settings' Premium row; closing it pops back. */
    @Serializable
    data object Paywall : Route
}
