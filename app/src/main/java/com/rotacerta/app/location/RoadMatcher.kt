package com.rotacerta.app.location

import android.location.Location
import com.rotacerta.app.model.RoutePoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Faz map matching do rastro GPS na malha viaria do OpenStreetMap via OSRM.
 *
 * O objetivo aqui nao e apenas suavizar a linha: o retorno contem a geometria
 * real das ruas percorridas, incluindo curvas e rotatorias. Em caso de falha
 * de rede o chamador continua usando os pontos GPS brutos, sem perder a rota.
 */
class RoadMatcher {

    fun match(rawPoints: List<RoutePoint>): List<RoutePoint>? {
        if (rawPoints.size < MIN_POINTS_TO_MATCH) return null

        val merged = mutableListOf<RoutePoint>()
        var start = 0

        while (start < rawPoints.lastIndex) {
            val endExclusive = min(rawPoints.size, start + MAX_POINTS_PER_REQUEST)
            val chunk = rawPoints.subList(start, endExclusive)
            val matchedChunk = matchChunk(chunk) ?: return null

            appendChunk(merged, matchedChunk)

            if (endExclusive >= rawPoints.size) break
            start = (endExclusive - CHUNK_OVERLAP_POINTS).coerceAtLeast(start + 1)
        }

        return merged.takeIf { it.size >= 2 }
    }

    private fun matchChunk(points: List<RoutePoint>): List<RoutePoint>? {
        if (points.size < 2) return null

        val coordinates = points.joinToString(";") {
            String.format(Locale.US, "%.7f,%.7f", it.longitude, it.latitude)
        }
        val radiuses = points.joinToString(";") {
            String.format(Locale.US, "%.1f", it.accuracy.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS))
        }

        val url = URL(
            "$OSRM_MATCH_URL/$coordinates" +
                "?geometries=geojson&overview=full&steps=false&gaps=ignore&tidy=true" +
                "&radiuses=$radiuses"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseGeometry(body, points)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeometry(jsonText: String, sourcePoints: List<RoutePoint>): List<RoutePoint>? {
        val root = JSONObject(jsonText)
        if (root.optString("code") != "Ok") return null

        val matchings = root.optJSONArray("matchings") ?: return null
        val result = mutableListOf<RoutePoint>()

        for (matchingIndex in 0 until matchings.length()) {
            val matching = matchings.optJSONObject(matchingIndex) ?: continue
            val geometry = matching.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue

            for (index in 0 until coordinates.length()) {
                val pair = coordinates.optJSONArray(index) ?: continue
                if (pair.length() < 2) continue

                val longitude = pair.optDouble(0, Double.NaN)
                val latitude = pair.optDouble(1, Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue

                val sourceIndex = if (coordinates.length() <= 1) 0 else {
                    ((index.toDouble() / (coordinates.length() - 1)) * sourcePoints.lastIndex)
                        .roundToInt()
                        .coerceIn(0, sourcePoints.lastIndex)
                }
                val source = sourcePoints[sourceIndex]

                val point = source.copy(
                    latitude = latitude,
                    longitude = longitude,
                    accuracy = min(source.accuracy, MATCHED_ACCURACY_METERS)
                )

                if (result.lastOrNull()?.let { distanceMeters(it, point) < MIN_OUTPUT_SPACING_METERS } != true) {
                    result += point
                }
            }
        }

        return result.takeIf { it.size >= 2 }
    }

    /**
     * Os pedidos longos sao divididos com sobreposicao. Ao juntar os blocos,
     * encontra o ponto do novo bloco mais proximo do fim ja confirmado e so
     * acrescenta dali para frente, evitando voltar alguns metros na emenda.
     */
    private fun appendChunk(target: MutableList<RoutePoint>, chunk: List<RoutePoint>) {
        if (chunk.isEmpty()) return
        if (target.isEmpty()) {
            target += chunk
            return
        }

        val last = target.last()
        var nearestIndex = 0
        var nearestDistance = Float.MAX_VALUE
        chunk.forEachIndexed { index, point ->
            val distance = distanceMeters(last, point)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }

        chunk.drop(nearestIndex).forEach { point ->
            if (distanceMeters(target.last(), point) >= MIN_OUTPUT_SPACING_METERS) {
                target += point
            }
        }
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    companion object {
        private const val OSRM_MATCH_URL = "https://router.project-osrm.org/match/v1/driving"
        private const val USER_AGENT = "RotaCerta-Android/0.7"

        // Mantem cada URL em tamanho seguro e evita exceder limites comuns do servico de matching.
        private const val MAX_POINTS_PER_REQUEST = 70
        private const val CHUNK_OVERLAP_POINTS = 6
        private const val MIN_POINTS_TO_MATCH = 4

        private const val MIN_RADIUS_METERS = 4f
        private const val MAX_RADIUS_METERS = 45f
        private const val MATCHED_ACCURACY_METERS = 4f
        private const val MIN_OUTPUT_SPACING_METERS = 0.8f

        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val READ_TIMEOUT_MS = 4_000
    }
}
