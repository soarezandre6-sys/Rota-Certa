package com.rotacerta.app.data

import android.content.Context
import com.rotacerta.app.model.RecordedRoute
import com.rotacerta.app.model.RoutePoint
import org.json.JSONArray
import org.json.JSONObject

class RouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("rota_certa_routes", Context.MODE_PRIVATE)

    fun save(route: RecordedRoute) {
        val all = readArray()
        all.put(routeToJson(route))
        prefs.edit().putString(KEY_ROUTES, all.toString()).apply()
    }

    fun loadAll(): List<RecordedRoute> {
        val array = readArray()
        return buildList {
            for (i in 0 until array.length()) {
                runCatching { jsonToRoute(array.getJSONObject(i)) }.getOrNull()?.let(::add)
            }
        }
    }

    fun loadLast(): RecordedRoute? = loadAll().maxByOrNull { it.finishedAt }

    private fun readArray(): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_ROUTES, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun routeToJson(route: RecordedRoute): JSONObject = JSONObject().apply {
        put("id", route.id)
        put("name", route.name)
        put("startedAt", route.startedAt)
        put("finishedAt", route.finishedAt)
        put("distanceMeters", route.distanceMeters.toDouble())
        put("points", JSONArray().apply {
            route.points.forEach { point ->
                put(JSONObject().apply {
                    put("lat", point.latitude)
                    put("lng", point.longitude)
                    put("time", point.timestamp)
                    put("accuracy", point.accuracy.toDouble())
                    put("speed", point.speed.toDouble())
                    put("bearing", point.bearing.toDouble())
                    point.altitude?.let { put("altitude", it) }
                })
            }
        })
    }

    private fun jsonToRoute(json: JSONObject): RecordedRoute {
        val pointsJson = json.getJSONArray("points")
        val points = buildList {
            for (i in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONObject(i)
                add(
                    RoutePoint(
                        latitude = p.getDouble("lat"),
                        longitude = p.getDouble("lng"),
                        timestamp = p.getLong("time"),
                        accuracy = p.optDouble("accuracy", 0.0).toFloat(),
                        speed = p.optDouble("speed", 0.0).toFloat(),
                        bearing = p.optDouble("bearing", 0.0).toFloat(),
                        altitude = if (p.has("altitude")) p.getDouble("altitude") else null
                    )
                )
            }
        }
        return RecordedRoute(
            id = json.getLong("id"),
            name = json.getString("name"),
            startedAt = json.getLong("startedAt"),
            finishedAt = json.getLong("finishedAt"),
            distanceMeters = json.getDouble("distanceMeters").toFloat(),
            points = points
        )
    }

    companion object {
        private const val KEY_ROUTES = "routes"
    }
}
