package com.rotacerta.app.routing

import com.rotacerta.app.model.RoutePoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class RouteApproachPlanner {

    data class PlannedApproach(
        val points: List<RoutePoint>,
        val distanceMeters: Float
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun plan(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
        onSuccess: (PlannedApproach) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.execute {
            runCatching {
                val coordinates = String.format(
                    Locale.US,
                    "%.7f,%.7f;%.7f,%.7f",
                    fromLongitude,
                    fromLatitude,
                    toLongitude,
                    toLatitude
                )
                val url = URL(
                    "$OSRM_BASE_URL/route/v1/driving/$coordinates" +
                        "?overview=full&geometries=geojson&steps=false&alternatives=false"
                )

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "RotaCerta-Android/0.7")
                }

                try {
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        error("O serviço de rotas respondeu com erro $status.")
                    }

                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optString("code") != "Ok") {
                        val message = json.optString("message")
                            .ifBlank { "Não encontrei um caminho até o início oficial da rota." }
                        error(message)
                    }

                    val routes = json.optJSONArray("routes")
                    if (routes == null || routes.length() == 0) {
                        error("Não encontrei um trajeto dirigível até o início da rota.")
                    }

                    val route = routes.getJSONObject(0)
                    val distanceMeters = route.optDouble("distance", 0.0).toFloat()
                    if (distanceMeters <= 0f || distanceMeters > MAX_APPROACH_DISTANCE_METERS) {
                        error("O trajeto até o início ficou fora do limite esperado.")
                    }

                    val coordinatesJson = route
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")
                    if (coordinatesJson.length() < 2) {
                        error("O serviço não devolveu geometria suficiente para chegar ao início.")
                    }

                    val now = System.currentTimeMillis()
                    val points = ArrayList<RoutePoint>(coordinatesJson.length())
                    for (index in 0 until coordinatesJson.length()) {
                        val pair = coordinatesJson.getJSONArray(index)
                        points += RoutePoint(
                            latitude = pair.getDouble(1),
                            longitude = pair.getDouble(0),
                            timestamp = now + index * 1_000L,
                            accuracy = ROUTED_POINT_ACCURACY_METERS,
                            speed = 0f,
                            bearing = 0f,
                            altitude = null
                        )
                    }

                    PlannedApproach(points = points, distanceMeters = distanceMeters)
                } finally {
                    connection.disconnect()
                }
            }.onSuccess(onSuccess).onFailure(onError)
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val MAX_APPROACH_DISTANCE_METERS = 250_000f
        private const val ROUTED_POINT_ACCURACY_METERS = 4f
    }
}
