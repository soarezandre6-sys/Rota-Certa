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
 * Alem da posicao, o matching envia a direcao aproximada do deslocamento para
 * evitar que o GPS seja encaixado em pistas paralelas, marginais ou acessos no
 * sentido errado. Se o servico produzir um desvio muito maior que o rastro real,
 * o resultado e rejeitado e o chamador continua usando o GPS bruto.
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

        return merged.takeIf { it.size >= 2 && isPlausibleMatch(rawPoints, it) }
    }

    private fun matchChunk(points: List<RoutePoint>): List<RoutePoint>? {
        if (points.size < 2) return null

        val coordinates = points.joinToString(";") {
            String.format(Locale.US, "%.7f,%.7f", it.longitude, it.latitude)
        }
        val radiuses = points.joinToString(";") {
            String.format(
                Locale.US,
                "%.1f",
                it.accuracy.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
            )
        }
        val bearings = points.indices.joinToString(";") { index ->
            estimateBearing(points, index)?.let { bearing ->
                String.format(Locale.US, "%.0f,%.0f", bearing, BEARING_TOLERANCE_DEGREES)
            } ?: ""
        }

        val url = URL(
            "$OSRM_MATCH_URL/$coordinates" +
                "?geometries=geojson&overview=full&steps=false&gaps=ignore&tidy=true" +
                "&radiuses=$radiuses&bearings=$bearings"
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
            val matched = parseGeometry(body, points) ?: return null
            matched.takeIf { isPlausibleMatch(points, it) }
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
            if (matching.optDouble("confidence", 1.0) < MIN_MATCH_CONFIDENCE) return null

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
     * Usa a direcao do proprio rastro, e nao apenas o bearing informado pelo
     * sensor. Procuramos pontos alguns metros antes/depois para evitar que ruido
     * de GPS em amostras muito proximas aponte para a pista paralela errada.
     */
    private fun estimateBearing(points: List<RoutePoint>, index: Int): Float? {
        var beforeIndex = index
        while (beforeIndex > 0 &&
            distanceMeters(points[beforeIndex], points[index]) < MIN_BEARING_BASELINE_METERS
        ) {
            beforeIndex -= 1
        }

        var afterIndex = index
        while (afterIndex < points.lastIndex &&
            distanceMeters(points[index], points[afterIndex]) < MIN_BEARING_BASELINE_METERS
        ) {
            afterIndex += 1
        }

        val from: RoutePoint
        val to: RoutePoint
        when {
            beforeIndex < index && afterIndex > index -> {
                from = points[beforeIndex]
                to = points[afterIndex]
            }
            afterIndex > index -> {
                from = points[index]
                to = points[afterIndex]
            }
            beforeIndex < index -> {
                from = points[beforeIndex]
                to = points[index]
            }
            else -> return null
        }

        if (distanceMeters(from, to) < MIN_BEARING_BASELINE_METERS) return null

        val start = Location("rota-certa-bearing").apply {
            latitude = from.latitude
            longitude = from.longitude
        }
        val end = Location("rota-certa-bearing").apply {
            latitude = to.latitude
            longitude = to.longitude
        }

        val bearing = start.bearingTo(end)
        return if (bearing < 0f) bearing + 360f else bearing
    }

    /**
     * O matcher nao pode inventar um contorno/retorno muito maior do que aquilo
     * que foi realmente percorrido. Isso protege principalmente avenidas com
     * pistas proximas, marginais e retornos em paralelo.
     */
    private fun isPlausibleMatch(source: List<RoutePoint>, matched: List<RoutePoint>): Boolean {
        if (source.size < 2 || matched.size < 2) return false

        val rawLength = routeLengthMeters(source)
        val matchedLength = routeLengthMeters(matched)
        if (rawLength <= 0f || matchedLength <= 0f) return false

        val allowedLength = rawLength * MAX_MATCHED_LENGTH_RATIO + MAX_MATCHED_EXTRA_METERS
        if (matchedLength > allowedLength) return false

        if (distanceMeters(source.first(), matched.first()) > MAX_ENDPOINT_OFFSET_METERS) return false
        if (distanceMeters(source.last(), matched.last()) > MAX_ENDPOINT_OFFSET_METERS) return false

        return true
    }

    private fun routeLengthMeters(points: List<RoutePoint>): Float {
        var total = 0f
        for (index in 1 until points.size) {
            total += distanceMeters(points[index - 1], points[index])
        }
        return total
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

        // Uma tolerancia menor reduz o risco de atravessar para uma pista paralela.
        // Se o GPS estiver pior que isso, e mais seguro manter o rastro bruto do que inventar uma rua.
        private const val MIN_RADIUS_METERS = 4f
        private const val MAX_RADIUS_METERS = 28f
        private const val MATCHED_ACCURACY_METERS = 4f
        private const val MIN_OUTPUT_SPACING_METERS = 0.8f

        private const val MIN_BEARING_BASELINE_METERS = 6f
        private const val BEARING_TOLERANCE_DEGREES = 70f
        private const val MIN_MATCH_CONFIDENCE = 0.15

        private const val MAX_MATCHED_LENGTH_RATIO = 1.25f
        private const val MAX_MATCHED_EXTRA_METERS = 35f
        private const val MAX_ENDPOINT_OFFSET_METERS = 35f

        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val READ_TIMEOUT_MS = 4_000
    }
}
