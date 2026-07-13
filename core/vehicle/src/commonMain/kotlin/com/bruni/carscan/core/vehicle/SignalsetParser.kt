package com.bruni.carscan.core.vehicle

import com.bruni.carscan.core.model.obdb.Signalset
import kotlinx.serialization.json.Json

/**
 * Reads an OBDb `signalsets/v3` document.
 *
 * The two settings here pull in opposite directions on purpose:
 *
 *  - `ignoreUnknownKeys = true` — OBDb adds fields over time, and a field we have never seen
 *    is not a reason to leave a user with a vehicle that will not load. Unknown keys are data
 *    we do not use yet, not corruption.
 *
 *  - enums stay STRICT (`coerceInputValues` is left at its default `false`) — an unrecognized
 *    `unit` or `suggestedMetric` throws. This is not inconsistency: an unknown key carries no
 *    meaning we are discarding, but an unknown enum does. Coercing `"kilowattHours"` we did
 *    not model down to `null` would not lose the reading, it would decode it into the wrong
 *    quantity and put it on a gauge, and nothing downstream could ever tell.
 */
object SignalsetParser {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** @throws kotlinx.serialization.SerializationException on malformed JSON or unknown enums. */
    fun parse(json: String): Signalset = this.json.decodeFromString(json)
}
