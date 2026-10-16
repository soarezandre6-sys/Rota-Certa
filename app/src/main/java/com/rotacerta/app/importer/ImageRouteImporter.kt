package com.rotacerta.app.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rotacerta.app.location.RoadMatcher
import com.rotacerta.app.model.RoutePoint
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageRouteImporter(private val context: Context) {

    data class OcrReference(
        val label: String,
        val x: Float,
        val y: Float
    )

    data class ImagePoint(val x: Float, val y: Float)

    data class ScanResult(
        val references: List<OcrReference>,
        val visualTrace: List<ImagePoint>,
        val width: Int,
        val height: Int
    )

    data class GeocodedStreet(
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val sourceX: Float = 0.5f,
        val sourceY: Float = 0.5f
    )

    data class GeoPoint(val latitude: Double, val longitude: Double)

    data class ReconstructedRoute(
        val points: List<GeoPoint>,
        val usedVisualTrace: Boolean,
        val confidence: Int
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val roadMatcher = RoadMatcher()

    fun scanRouteImage(
        uri: Uri,
        onSuccess: (ScanResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.execute {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    ?: error("Não consegui abrir a imagem")
            }.getOrElse {
                onError(it)
                return@execute
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    executor.execute {
                        runCatching {
                            val references = result.textBlocks
                                .flatMap { block -> block.lines }
                                .mapNotNull { line ->
                                    val label = normalizeCandidate(line.text)
                                    if (!looksLikeLocationReference(label)) return@mapNotNull null
                                    val box = line.boundingBox ?: return@mapNotNull null
                                    OcrReference(
                                        label = label,
                                        x = (box.centerX().toFloat() / bitmap.width).coerceIn(0f, 1f),
                                        y = (box.centerY().toFloat() / bitmap.height).coerceIn(0f, 1f)
                                    )
                                }
                                .distinctBy { it.label.lowercase(Locale.ROOT) }
                                .take(MAX_OCR_REFERENCES)

                            val trace = extractRouteTrace(bitmap)
                            ScanResult(references, trace, bitmap.width, bitmap.height)
                        }.onSuccess(onSuccess).onFailure(onError)
                    }
                }
                .addOnFailureListener(onError)
        }
    }

    fun geocodeCandidates(
        candidates: List<OcrReference>,
        biasLatitude: Double?,
        biasLongitude: Double?,
        onSuccess: (List<GeocodedStreet>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.execute {
            runCatching {
                val geocoder = Geocoder(context, Locale("pt", "BR"))
                val areaSuffix = resolveAreaSuffix(geocoder, biasLatitude, biasLongitude)
                val geocoded = mutableListOf<GeocodedStreet>()

                candidates.take(MAX_GEOCODE_QUERIES).forEach { candidate ->
                    val query = listOf(candidate.label, areaSuffix)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")

                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 4).orEmpty()
                    val chosen = addresses.minByOrNull { address ->
                        if (biasLatitude == null || biasLongitude == null) 0f
                        else distanceMeters(
                            biasLatitude,
                            biasLongitude,
                            address.latitude,
                            address.longitude
                        )
                    }

                    if (chosen != null) {
                        if (biasLatitude != null && biasLongitude != null) {
                            val distance = distanceMeters(
                                biasLatitude,
                                biasLongitude,
                                chosen.latitude,
                                chosen.longitude
                            )
                            if (distance > MAX_BIAS_DISTANCE_METERS) return@forEach
                        }

                        val item = GeocodedStreet(
                            label = candidate.label,
                            latitude = chosen.latitude,
                            longitude = chosen.longitude,
                            sourceX = candidate.x,
                            sourceY = candidate.y
                        )

                        val duplicate = geocoded.any {
                            distanceMeters(
                                it.latitude,
                                it.longitude,
                                item.latitude,
                                item.longitude
                            ) < MIN_DISTINCT_POINT_METERS
                        }
                        if (!duplicate) geocoded += item
                    }
                }
                geocoded
            }.onSuccess(onSuccess).onFailure(onError)
        }
    }

    /**
     * Reconstrói a linha do print, mas nunca devolve diretamente a geometria
     * aproximada da imagem. O traçado reconstruído é apenas uma pista inicial e
     * precisa passar pelo RoadMatcher antes de poder ser mostrado/salvo.
     *
     * Isso evita o comportamento antigo de ligar nomes de ruas ou pontos OCR em
     * linha reta, que fazia a rota atravessar quarteirões, canteiros e rotatórias.
     */
    fun reconstructRoute(scan: ScanResult, streets: List<GeocodedStreet>): ReconstructedRoute {
        if (scan.visualTrace.size < MIN_TRACE_POINTS || streets.size < MIN_ANCHORS_FOR_VISUAL_ROUTE) {
            return rejectedRoute()
        }

        val latFit = fitAffine(streets) { it.latitude }
        val lonFit = fitAffine(streets) { it.longitude }
        if (latFit == null || lonFit == null) return rejectedRoute()

        val anchorMinLat = streets.minOf { it.latitude }
        val anchorMaxLat = streets.maxOf { it.latitude }
        val anchorMinLon = streets.minOf { it.longitude }
        val anchorMaxLon = streets.maxOf { it.longitude }
        val latPad = max((anchorMaxLat - anchorMinLat) * 1.5, 0.004)
        val lonPad = max((anchorMaxLon - anchorMinLon) * 1.5, 0.004)

        val transformed = scan.visualTrace.mapNotNull { p ->
            val lat = latFit[0] + latFit[1] * p.x + latFit[2] * p.y
            val lon = lonFit[0] + lonFit[1] * p.x + lonFit[2] * p.y
            if (lat !in (anchorMinLat - latPad)..(anchorMaxLat + latPad)) return@mapNotNull null
            if (lon !in (anchorMinLon - lonPad)..(anchorMaxLon + lonPad)) return@mapNotNull null
            GeoPoint(lat, lon)
        }

        val cleaned = mutableListOf<GeoPoint>()
        transformed.forEach { point ->
            val previous = cleaned.lastOrNull()
            if (previous == null) {
                cleaned += point
            } else {
                val distance = distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    point.latitude,
                    point.longitude
                )
                if (distance in MIN_VISUAL_POINT_SPACING_METERS..MAX_VISUAL_JUMP_METERS) {
                    cleaned += point
                }
            }
        }

        if (cleaned.size < MIN_RECONSTRUCTED_POINTS) return rejectedRoute()

        val simplified = if (cleaned.size <= MAX_RECONSTRUCTED_POINTS) {
            cleaned
        } else {
            val step = (cleaned.size - 1).toFloat() / (MAX_RECONSTRUCTED_POINTS - 1)
            (0 until MAX_RECONSTRUCTED_POINTS).map { index ->
                cleaned[(index * step).toInt().coerceIn(0, cleaned.lastIndex)]
            }
        }

        val sourceRoutePoints = toRoutePoints(simplified)
        val matched = roadMatcher.match(sourceRoutePoints) ?: return rejectedRoute()
        if (matched.size < MIN_MATCHED_ROUTE_POINTS) return rejectedRoute()

        if (!isPlausibleRoadMatch(sourceRoutePoints, matched)) return rejectedRoute()

        val matchedGeo = matched.map { GeoPoint(it.latitude, it.longitude) }
        val baseConfidence = 68 + min(18, streets.size * 3) + min(8, scan.visualTrace.size / 18)
        val confidence = baseConfidence.coerceIn(72, 94)

        return ReconstructedRoute(
            points = matchedGeo,
            usedVisualTrace = true,
            confidence = confidence
        )
    }

    private fun rejectedRoute(): ReconstructedRoute =
        ReconstructedRoute(emptyList(), usedVisualTrace = false, confidence = 0)

    private fun toRoutePoints(points: List<GeoPoint>): List<RoutePoint> {
        val now = System.currentTimeMillis()
        return points.mapIndexed { index, point ->
            RoutePoint(
                latitude = point.latitude,
                longitude = point.longitude,
                timestamp = now + index * 1_000L,
                accuracy = IMPORTED_TRACE_ACCURACY_METERS,
                speed = 0f,
                bearing = 0f,
                altitude = null
            )
        }
    }

    /**
     * Proteção extra contra um matching tecnicamente válido, mas geograficamente
     * absurdo. A rota encaixada precisa permanecer próxima da linha reconstruída
     * e não pode explodir para uma distância muitas vezes maior que a candidata.
     */
    private fun isPlausibleRoadMatch(source: List<RoutePoint>, matched: List<RoutePoint>): Boolean {
        if (source.size < 2 || matched.size < 2) return false

        val rawDistance = routeDistance(source)
        val matchedDistance = routeDistance(matched)
        if (rawDistance <= 0f || matchedDistance <= 0f) return false
        if (matchedDistance > rawDistance * MAX_MATCH_DISTANCE_RATIO + MAX_MATCH_EXTRA_METERS) return false

        val sampleStep = max(1, source.size / MAX_PROXIMITY_SAMPLES)
        var checked = 0
        var totalNearest = 0f
        var index = 0

        while (index < source.size) {
            val point = source[index]
            var nearest = Float.MAX_VALUE
            matched.forEach { candidate ->
                val distance = distanceMeters(
                    point.latitude,
                    point.longitude,
                    candidate.latitude,
                    candidate.longitude
                )
                if (distance < nearest) nearest = distance
            }
            totalNearest += nearest
            checked += 1
            index += sampleStep
        }

        val averageNearest = if (checked > 0) totalNearest / checked else Float.MAX_VALUE
        return averageNearest <= MAX_AVERAGE_MATCH_OFFSET_METERS
    }

    private fun routeDistance(points: List<RoutePoint>): Float {
        var total = 0f
        for (index in 1 until points.size) {
            total += distanceMeters(
                points[index - 1].latitude,
                points[index - 1].longitude,
                points[index].latitude,
                points[index].longitude
            )
        }
        return total
    }

    private fun extractRouteTrace(bitmap: Bitmap): List<ImagePoint> {
        val grid = TRACE_GRID
        val active = HashSet<Int>()
        val stepX = max(1, bitmap.width / (grid * 2))
        val stepY = max(1, bitmap.height / (grid * 2))
        val hsv = FloatArray(3)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                val routeLike = hue in BLUE_HUE_MIN..BLUE_HUE_MAX &&
                    saturation >= BLUE_MIN_SATURATION &&
                    value >= BLUE_MIN_VALUE

                if (routeLike) {
                    val gx = ((x.toFloat() / bitmap.width) * grid).toInt().coerceIn(0, grid - 1)
                    val gy = ((y.toFloat() / bitmap.height) * grid).toInt().coerceIn(0, grid - 1)
                    active += gy * grid + gx
                }
                x += stepX
            }
            y += stepY
        }

        if (active.size < MIN_TRACE_CELLS) return emptyList()
        val largest = largestConnectedComponent(active, grid)
        if (largest.size < MIN_TRACE_CELLS) return emptyList()

        val ordered = orderComponent(largest, grid)
        if (ordered.size < MIN_TRACE_POINTS) return emptyList()

        val reduced = ordered.filterIndexed { index, _ ->
            index == 0 || index == ordered.lastIndex || index % TRACE_SIMPLIFY_STEP == 0
        }

        return reduced.map { cell ->
            val gx = cell % grid
            val gy = cell / grid
            ImagePoint(
                x = (gx + 0.5f) / grid,
                y = (gy + 0.5f) / grid
            )
        }
    }

    private fun largestConnectedComponent(active: Set<Int>, grid: Int): Set<Int> {
        val remaining = active.toMutableSet()
        var best: Set<Int> = emptySet()
        val queue = ArrayDeque<Int>()

        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            remaining.remove(start)
            queue.add(start)
            val component = LinkedHashSet<Int>()

            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                component += cell
                neighbors(cell, grid).forEach { neighbor ->
                    if (remaining.remove(neighbor)) queue.add(neighbor)
                }
            }
            if (component.size > best.size) best = component
        }
        return best
    }

    private fun orderComponent(component: Set<Int>, grid: Int): List<Int> {
        val endpoint = component
            .filter { cell -> neighbors(cell, grid).count(component::contains) <= 2 }
            .minByOrNull { it / grid }
            ?: component.minByOrNull { it / grid }
            ?: return emptyList()

        val visited = HashSet<Int>()
        val ordered = mutableListOf<Int>()
        var current = endpoint
        var previous: Int? = null

        while (visited.size < component.size) {
            visited += current
            ordered += current
            val candidates = neighbors(current, grid)
                .filter { it in component && it !in visited }
            if (candidates.isEmpty()) break

            current = if (previous == null || candidates.size == 1) {
                candidates.first()
            } else {
                val px = previous % grid
                val py = previous / grid
                val cx = current % grid
                val cy = current / grid
                val vx = cx - px
                val vy = cy - py
                candidates.maxByOrNull { next ->
                    val nx = next % grid
                    val ny = next / grid
                    vx * (nx - cx) + vy * (ny - cy)
                } ?: candidates.first()
            }
            previous = ordered.getOrNull(ordered.lastIndex - 1)
        }
        return ordered
    }

    private fun neighbors(cell: Int, grid: Int): List<Int> {
        val x = cell % grid
        val y = cell / grid
        val result = ArrayList<Int>(8)
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until grid && ny in 0 until grid) {
                    result += ny * grid + nx
                }
            }
        }
        return result
    }

    private fun fitAffine(
        streets: List<GeocodedStreet>,
        value: (GeocodedStreet) -> Double
    ): DoubleArray? {
        var s1 = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var sv = 0.0
        var sxv = 0.0
        var syv = 0.0

        streets.forEach { item ->
            val x = item.sourceX.toDouble()
            val y = item.sourceY.toDouble()
            val v = value(item)
            s1 += 1.0
            sx += x
            sy += y
            sxx += x * x
            syy += y * y
            sxy += x * y
            sv += v
            sxv += x * v
            syv += y * v
        }

        val matrix = arrayOf(
            doubleArrayOf(s1, sx, sy, sv),
            doubleArrayOf(sx, sxx, sxy, sxv),
            doubleArrayOf(sy, sxy, syy, syv)
        )
        return solve3x3(matrix)
    }

    private fun solve3x3(matrix: Array<DoubleArray>): DoubleArray? {
        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-9) return null

            if (pivot != column) {
                val temp = matrix[pivot]
                matrix[pivot] = matrix[column]
                matrix[column] = temp
            }

            val divisor = matrix[column][column]
            for (index in column..3) matrix[column][index] /= divisor

            for (row in 0..2) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (index in column..3) {
                    matrix[row][index] -= factor * matrix[column][index]
                }
            }
        }
        return doubleArrayOf(matrix[0][3], matrix[1][3], matrix[2][3])
    }

    private fun resolveAreaSuffix(
        geocoder: Geocoder,
        latitude: Double?,
        longitude: Double?
    ): String {
        if (latitude == null || longitude == null) return "Brasil"
        return runCatching {
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            listOfNotNull(
                address?.locality,
                address?.subAdminArea,
                address?.adminArea,
                address?.countryName
            ).distinct().joinToString(", ")
        }.getOrDefault("Brasil")
    }

    private fun normalizeCandidate(raw: String): String = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim(',', ';', '|', '•')

    private fun looksLikeLocationReference(text: String): Boolean {
        if (text.length !in 3..80 || text.none(Char::isLetter)) return false
        val lower = text.lowercase(Locale.ROOT)
        if (BLOCKED_UI_WORDS.any { lower == it || lower.startsWith("$it ") }) return false
        if (STREET_HINTS.any { lower.contains(it) }) return true
        val words = text.split(' ').filter { it.isNotBlank() }
        return words.size in 2..7 && text.count(Char::isLetter) >= 5
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    fun close() {
        recognizer.close()
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_OCR_REFERENCES = 28
        private const val MAX_GEOCODE_QUERIES = 18
        private const val MIN_DISTINCT_POINT_METERS = 35f
        private const val MAX_BIAS_DISTANCE_METERS = 55_000f

        private const val TRACE_GRID = 96
        private const val MIN_TRACE_CELLS = 9
        private const val MIN_TRACE_POINTS = 7
        private const val TRACE_SIMPLIFY_STEP = 2
        private const val BLUE_HUE_MIN = 175f
        private const val BLUE_HUE_MAX = 245f
        private const val BLUE_MIN_SATURATION = 0.42f
        private const val BLUE_MIN_VALUE = 0.42f

        private const val MIN_ANCHORS_FOR_VISUAL_ROUTE = 3
        private const val MIN_RECONSTRUCTED_POINTS = 6
        private const val MAX_RECONSTRUCTED_POINTS = 90
        private const val MIN_MATCHED_ROUTE_POINTS = 4
        private const val MIN_VISUAL_POINT_SPACING_METERS = 8f
        private const val MAX_VISUAL_JUMP_METERS = 700f
        private const val IMPORTED_TRACE_ACCURACY_METERS = 35f

        private const val MAX_MATCH_DISTANCE_RATIO = 2.8f
        private const val MAX_MATCH_EXTRA_METERS = 600f
        private const val MAX_AVERAGE_MATCH_OFFSET_METERS = 60f
        private const val MAX_PROXIMITY_SAMPLES = 20

        private val STREET_HINTS = listOf(
            "rua ", "r. ", "avenida ", "av. ", "rodovia ", "rod. ",
            "estrada ", "travessa ", "alameda ", "praça ", "pr-", "br-"
        )

        private val BLOCKED_UI_WORDS = listOf(
            "google", "maps", "min", "km", "rota", "iniciar", "chegada",
            "trânsito", "transito", "gps", "satélite", "satelite", "waze"
        )
    }
}
