package com.rotacerta.app.importer

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors

class ImageRouteImporter(private val context: Context) {

    data class GeocodedStreet(
        val label: String,
        val latitude: Double,
        val longitude: Double
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()

    fun readStreetCandidates(
        uri: Uri,
        onSuccess: (List<String>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                onError(it)
                return
            }

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val candidates = result.textBlocks
                    .flatMap { block -> block.lines }
                    .map { normalizeCandidate(it.text) }
                    .filter(::looksLikeLocationReference)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(MAX_OCR_REFERENCES)
                onSuccess(candidates)
            }
            .addOnFailureListener(onError)
    }

    fun geocodeCandidates(
        candidates: List<String>,
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
                    val query = listOf(candidate, areaSuffix)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")

                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 3).orEmpty()
                    val chosen = addresses.minByOrNull { address ->
                        if (biasLatitude == null || biasLongitude == null) {
                            0f
                        } else {
                            distanceMeters(
                                biasLatitude,
                                biasLongitude,
                                address.latitude,
                                address.longitude
                            )
                        }
                    }

                    if (chosen != null) {
                        val item = GeocodedStreet(
                            label = candidate,
                            latitude = chosen.latitude,
                            longitude = chosen.longitude
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
        private const val MAX_OCR_REFERENCES = 24
        private const val MAX_GEOCODE_QUERIES = 16
        private const val MIN_DISTINCT_POINT_METERS = 45f

        private val STREET_HINTS = listOf(
            "rua ", "r. ", "avenida ", "av. ", "rodovia ", "rod. ",
            "estrada ", "travessa ", "alameda ", "praça ", "pr-", "br-"
        )

        private val BLOCKED_UI_WORDS = listOf(
            "google", "maps", "min", "km", "rota", "iniciar", "chegada",
            "trânsito", "transito", "gps", "satélite", "satelite"
        )
    }
}
