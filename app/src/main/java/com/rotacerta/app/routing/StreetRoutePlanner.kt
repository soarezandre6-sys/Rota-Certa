package com.rotacerta.app.routing

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import com.rotacerta.app.model.RoutePoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class StreetRoutePlanner(private val context: Context) {

    data class Waypoint(
        val requestedName: String,
        val resolvedName: String,
        val latitude: Double,
        val longitude: Double
    )

    data class PlannedRoute(
        val waypoints: List<Waypoint>,
        val points: List<RoutePoint>,
        val distanceMeters: Float
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun build(
        streetNames: List<String>,
        areaHint: String,
        biasLatitude: Double?,
        biasLongitude: Double?,
        onSuccess: (PlannedRoute) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.execute {
            runCatching {
                val names = streetNames
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .take(MAX_STREETS)

                require(names.size >= MIN_STREETS) {
                    "Informe pelo menos duas ruas, uma por linha."
                }

                val geocoder = Geocoder(context, Locale("pt", "BR"))
                val explicitArea = areaHint.trim()
                val inferredArea = if (explicitArea.isBlank()) {
                    resolveAreaSuffix(geocoder, biasLatitude, biasLongitude)
                } else {
                    ""
                }
                val area = explicitArea.ifBlank { inferredArea }

                if (area.isBlank() && (biasLatitude == null || biasLongitude == null)) {
                    error("Informe Cidade/UF ou permita a localização para eu saber em qual região procurar as ruas.")
                }

                val waypoints = mutableListOf<Waypoint>()
                val missing = mutableListOf<String>()
                var anchorLatitude = if (explicitArea.isBlank()) biasLatitude else null
                var anchorLongitude = if (explicitArea.isBlank()) biasLongitude else null

                names.forEach { name ->
                    val query = listOf(name, area)
                        .filter(String::isNotBlank)
                        .joinToString(", ")

                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, MAX_GEOCODER_RESULTS).orEmpty()
                    val chosen = chooseAddress(addresses, anchorLatitude, anchorLongitude)

                    if (chosen == null) {
                        missing += name
                        return@forEach
                    }

                    if (explicitArea.isBlank() && biasLatitude != null && biasLongitude != null) {
                        val fromBias = distanceMeters(
                            biasLatitude,
                            biasLongitude,
                            chosen.latitude,
                            chosen.longitude
                        )
                        if (fromBias > MAX_BIAS_DISTANCE_METERS) {
                            missing += name
                            return@forEach
                        }
                    }

                    val resolved = listOfNotNull(
                        chosen.thoroughfare ?: chosen.featureName,
                        chosen.locality,
                        chosen.adminArea
                    ).filter(String::isNotBlank)
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { name }

                    waypoints += Waypoint(
                        requestedName = name,
                        resolvedName = resolved,
                        latitude = chosen.latitude,
                        longitude = chosen.longitude
                    )

                    if (anchorLatitude == null || anchorLongitude == null) {
                        anchorLatitude = chosen.latitude
                        anchorLongitude = chosen.longitude
                    }
                }

                if (missing.isNotEmpty()) {
                    error("Não consegui localizar: ${missing.joinToString(", ")}. Confira o nome e a Cidade/UF.")
                }
                require(waypoints.size == names.size) {
                    "Nem todas as ruas puderam ser localizadas com segurança."
                }

                routeThroughWaypoints(waypoints)
            }.onSuccess(onSuccess).onFailure(onError)
        }
    }

    private fun chooseAddress(
        addresses: List<Address>,
        anchorLatitude: Double?,
        anchorLongitude: Double?
    ): Address? {
        if (addresses.isEmpty()) return null
        if (anchorLatitude == null || anchorLongitude == null) return addresses.first()
        return addresses.minByOrNull { address ->
            distanceMeters(
                anchorLatitude,
                anchorLongitude,
                address.latitude,
                address.longitude
            )
        }
    }

    private fun resolveAreaSuffix(
        geocoder: Geocoder,
        latitude: Double?,
        longitude: Double?
    ): String {
        if (latitude == null || longitude == null) return ""
        return runCatching {
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                ?: return@runCatching ""
            listOfNotNull(address.locality, address.subAdminArea, address.adminArea, address.countryName)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(", ")
        }.getOrDefault("")
    }

    private fun routeThroughWaypoints(waypoints: List<Waypoint>): PlannedRoute {
        val coordinates = waypoints.joinToString(";") {
            String.format(Locale.US, "%.7f,%.7f", it.longitude, it.latitude)
        }
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
                val message = json.optString("message").ifBlank { "Não existe rota viária entre essas ruas." }
                error(message)
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                error("Não encontrei um trajeto dirigível entre as ruas informadas.")
            }

            val route = routes.getJSONObject(0)
            val distanceMeters = route.optDouble("distance", 0.0).toFloat()
            if (distanceMeters <= 0f || distanceMeters > MAX_ROUTE_DISTANCE_METERS) {
                error("A rota calculada ficou fora do limite esperado. Confira as ruas e a região.")
            }

            val geometry = route.getJSONObject("geometry")
            val coordinatesJson = geometry.getJSONArray("coordinates")
            if (coordinatesJson.length() < 2) {
                error("O serviço não devolveu geometria suficiente para montar a rota.")
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

            val reduced = simplifyForStorage(points)
            return PlannedRoute(
                waypoints = waypoints,
                points = reduced,
                distanceMeters = distanceMeters
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun simplifyForStorage(points: List<RoutePoint>): List<RoutePoint> {
        if (points.size <= MAX_ROUTE_POINTS) return points
        val step = (points.size - 1).toFloat() / (MAX_ROUTE_POINTS - 1)
        return (0 until MAX_ROUTE_POINTS).map { index ->
            points[(index * step).toInt().coerceIn(0, points.lastIndex)]
        }
    }

    private fun distanceMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(latitudeA, longitudeA, latitudeB, longitudeB, result)
        return result[0]
    }

    fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
        private const val MIN_STREETS = 2
        private const val MAX_STREETS = 12
        private const val MAX_GEOCODER_RESULTS = 5
        private const val MAX_BIAS_DISTANCE_METERS = 120_000f
        private const val MAX_ROUTE_DISTANCE_METERS = 500_000f
        private const val MAX_ROUTE_POINTS = 1_500
        private const val ROUTED_POINT_ACCURACY_METERS = 4f
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val READ_TIMEOUT_MS = 7_000
    }
}
