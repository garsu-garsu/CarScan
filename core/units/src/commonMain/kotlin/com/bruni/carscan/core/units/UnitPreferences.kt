package com.bruni.carscan.core.units

/**
 * The user's display unit for every [Quantity] — one choice per quantity, never a single
 * metric/imperial switch.
 *
 * **The UK is why.** A British driver uses miles, litres and imperial MPG *simultaneously*, and
 * reads temperature in °C while doing it. An `isMetric` boolean cannot express that state, so
 * every app built on one is wrong for an entire country, and hears about it.
 *
 * The map is total: it holds an entry for every quantity, and each unit belongs to the quantity
 * it is filed under. Both are checked at construction, because the alternative is discovering a
 * missing entry at the presentation edge, which is the worst place to discover anything.
 */
data class UnitPreferences(val byQuantity: Map<Quantity, UnitId>) {

    init {
        val missing = Quantity.entries - byQuantity.keys
        require(missing.isEmpty()) { "no display unit chosen for $missing" }
        byQuantity.forEach { (quantity, unit) ->
            require(unit.quantity == quantity) { "$unit is a ${unit.quantity} unit, not a $quantity one" }
        }
    }

    operator fun get(quantity: Quantity): UnitId = byQuantity.getValue(quantity)

    fun with(quantity: Quantity, unit: UnitId): UnitPreferences =
        UnitPreferences(byQuantity + (quantity to unit))

    /**
     * Flattens to a single string for DataStore, which stores primitives.
     *
     * Names, not ordinals: an ordinal is a landmine the day someone inserts a unit in the middle
     * of the enum, and it silently reinterprets everyone's stored preferences.
     */
    fun encode(): String = Quantity.entries.joinToString(",") { "${it.name}=${byQuantity.getValue(it).name}" }

    companion object {

        /** km/h, km, bar, °C, litres, L/100km, kWh/100km, PS, N·m. */
        val METRIC: UnitPreferences = UnitPreferences(
            mapOf(
                Quantity.SPEED to UnitId.KMH,
                Quantity.DISTANCE to UnitId.KM,
                Quantity.PRESSURE to UnitId.BAR,
                Quantity.TEMPERATURE to UnitId.CELSIUS,
                Quantity.VOLUME to UnitId.LITRE,
                Quantity.CONSUMPTION to UnitId.L_PER_100KM,
                Quantity.ENERGY_CONSUMPTION to UnitId.KWH_PER_100KM,
                Quantity.POWER to UnitId.PS,
                Quantity.TORQUE to UnitId.NM,
            ),
        )

        /**
         * The units a first-run user in this region expects, before they touch a single setting.
         *
         * [languageTag] is BCP-47 (`"en-GB"`, `"pt-BR"`); [region] overrides the region inside it.
         * An unrecognised region is metric — a defensible default everywhere, and wrong in a way
         * the user can immediately see and fix, rather than subtly.
         */
        fun defaultsFor(languageTag: String, region: String? = null): UnitPreferences =
            when (region?.uppercase() ?: regionOf(languageTag)) {
                "US" -> imperial(
                    pressure = UnitId.PSI,
                    temperature = UnitId.FAHRENHEIT,
                    volume = UnitId.US_GALLON,
                    consumption = UnitId.MPG_US,
                )
                // Miles, psi and imperial MPG — but °C and litres. This row is the whole design.
                "GB" -> imperial(
                    pressure = UnitId.PSI,
                    temperature = UnitId.CELSIUS,
                    volume = UnitId.LITRE,
                    consumption = UnitId.MPG_UK,
                )
                // Metric, except that every tyre gauge in Canada reads psi.
                "CA" -> METRIC
                    .with(Quantity.PRESSURE, UnitId.PSI)
                    .with(Quantity.POWER, UnitId.HP)
                    .with(Quantity.TORQUE, UnitId.LB_FT)
                // Korea quotes economy as km/L, and tyre pressure in psi.
                "KR" -> METRIC
                    .with(Quantity.PRESSURE, UnitId.PSI)
                    .with(Quantity.CONSUMPTION, UnitId.KM_PER_L)
                else -> METRIC
            }

        /**
         * Rebuilds preferences from [encode].
         *
         * Tolerant by construction: an unknown quantity, an unknown unit, a unit filed under the
         * wrong quantity, or a missing entry all fall back to [METRIC] for that one quantity. A
         * preferences file written by a newer build must not brick the app on downgrade.
         */
        fun decode(encoded: String?): UnitPreferences {
            if (encoded.isNullOrBlank()) return METRIC

            val stored = encoded.split(',').mapNotNull { entry ->
                val (quantityName, unitName) = entry.split('=', limit = 2)
                    .takeIf { it.size == 2 }
                    ?: return@mapNotNull null
                val quantity = Quantity.entries.firstOrNull { it.name == quantityName }
                    ?: return@mapNotNull null
                val unit = UnitId.entries.firstOrNull { it.name == unitName && it.quantity == quantity }
                    ?: return@mapNotNull null
                quantity to unit
            }
            return UnitPreferences(METRIC.byQuantity + stored)
        }

        private fun imperial(
            pressure: UnitId,
            temperature: UnitId,
            volume: UnitId,
            consumption: UnitId,
        ) = UnitPreferences(
            mapOf(
                Quantity.SPEED to UnitId.MPH,
                Quantity.DISTANCE to UnitId.MILES,
                Quantity.PRESSURE to pressure,
                Quantity.TEMPERATURE to temperature,
                Quantity.VOLUME to volume,
                Quantity.CONSUMPTION to consumption,
                // EV economy follows the distance unit: mi/kWh is what miles-driving markets say.
                Quantity.ENERGY_CONSUMPTION to UnitId.MI_PER_KWH,
                Quantity.POWER to UnitId.HP,
                Quantity.TORQUE to UnitId.LB_FT,
            ),
        )

        /** The region subtag of a BCP-47 tag: the two-letter segment of `en-GB`, `en_gb`, `zh-Hans-CN`. */
        private fun regionOf(languageTag: String): String? = languageTag
            .split('-', '_')
            .drop(1)
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) }
            ?.uppercase()
    }
}
