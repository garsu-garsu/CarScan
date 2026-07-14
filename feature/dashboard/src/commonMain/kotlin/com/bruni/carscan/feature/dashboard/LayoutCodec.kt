package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The dashboard layout's on-disk format.
 *
 * `DashboardLayoutRepository` stores the layout as an opaque JSON string, so the schema of a
 * tile is this file's problem and not the database's. Two decisions are load-bearing:
 *
 * **The codec is hand-written rather than `@Serializable`.** kotlinx's generated serializer for
 * a sealed hierarchy discriminates on the *fully-qualified class name*, so `MetricKey.Metric`
 * would persist as `"com.bruni.carscan.core.model.MetricKey.Metric"` and every saved layout on
 * every phone would break the day somebody renamed or moved that class. Here the discriminator
 * is the presence of a `metric` or a `signal` field, and a metric is written under OBDb's own
 * wire name — taken from the enum's serializer, so it cannot drift from the schema.
 *
 * **Decoding never throws.** A layout is data the user owns; a tile that cannot be understood is
 * dropped and the rest of the dashboard still comes up. Throwing would turn a single unreadable
 * tile — written by a newer build, or naming a signal this vehicle has never heard of — into a
 * screen that cannot be opened at all, on the screen the user spends their drive looking at.
 */
object LayoutCodec {

    private const val VERSION = 1

    /** What an unrecognised style falls back to, rather than refusing to draw the tile. */
    private val DEFAULT_STYLE = GaugeStyleId.MODERN_ARC

    fun encode(tiles: List<DashboardTile>): String {
        val root = buildJsonObject {
            put("version", VERSION)
            put(
                "tiles",
                buildJsonArray {
                    for (tile in tiles) {
                        add(
                            buildJsonObject {
                                put("id", tile.id)
                                when (val key = tile.key) {
                                    is MetricKey.Metric -> put("metric", key.metric.wireName())
                                    is MetricKey.Signal -> put("signal", key.signalId)
                                }
                                put("style", tile.style.name)
                                put("min", tile.min)
                                put("max", tile.max)
                            },
                        )
                    }
                },
            )
        }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(json: String): List<DashboardTile> {
        val tiles = runCatching {
            Json.parseToJsonElement(json).jsonObject["tiles"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        return tiles.mapNotNull { element ->
            runCatching { tile(element.jsonObject) }.getOrNull()
        }
    }

    private fun tile(o: JsonObject): DashboardTile? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null

        val key = when {
            o.containsKey("metric") -> {
                val metric = metricOrNull(o.getValue("metric").jsonPrimitive.content) ?: return null
                MetricKey.Metric(metric)
            }

            o.containsKey("signal") -> MetricKey.Signal(o.getValue("signal").jsonPrimitive.content)
            else -> return null
        }

        return DashboardTile(
            id = id,
            key = key,
            // A style this build has never heard of is a cosmetic loss. Dropping the tile over
            // it would be a data loss, and the layout came from the user, not from us.
            style = GaugeStyleId.entries
                .firstOrNull { it.name == o["style"]?.jsonPrimitive?.contentOrNull }
                ?: DEFAULT_STYLE,
            min = o["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            max = o["max"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    /** OBDb's name for this metric ("speed"), via the enum's own `@SerialName`s. */
    private fun SuggestedMetric.wireName(): String =
        Json.encodeToJsonElement(SuggestedMetric.serializer(), this).jsonPrimitive.content

    /** Null for a metric this build does not have — a layout from a newer one, most likely. */
    private fun metricOrNull(wireName: String): SuggestedMetric? = runCatching {
        Json.decodeFromJsonElement(SuggestedMetric.serializer(), JsonPrimitive(wireName))
    }.getOrNull()
}
