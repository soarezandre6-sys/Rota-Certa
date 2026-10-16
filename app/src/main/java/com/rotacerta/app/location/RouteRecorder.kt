package com.rotacerta.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rotacerta.app.model.RoutePoint
import java.util.concurrent.Executors

class RouteRecorder(context: Context) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val lock = Any()
    private val rawPoints = mutableListOf<RoutePoint>()
    private val roadMatcher = RoadMatcher()
    private val matcherExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var matchedPoints: List<RoutePoint> = emptyList()

    @Volatile
    private var matchedRawCount = 0

    @Volatile
    private var matchInFlight = false

    @Volatile
    private var running = false

    @Volatile
    private var onPoint: ((RoutePoint, Float) -> Unit)? = null

    @Volatile
    private var sessionId = 0L

    private var totalDistanceMeters = 0f
    private var lastMatchStartedAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::acceptLocation)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onPoint: (RoutePoint, Float) -> Unit) {
        if (running) client.removeLocationUpdates(callback)

        sessionId += 1L
        synchronized(lock) {
            rawPoints.clear()
            totalDistanceMeters = 0f
        }
        matchedPoints = emptyList()
        matchedRawCount = 0
        matchInFlight = false
        lastMatchStartedAt = 0L

        running = true
        this.onPoint = onPoint

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(2.0f)
            .setWaitForAccurateLocation(false)
            .build()

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop(): List<RoutePoint> {
        if (running) client.removeLocationUpdates(callback)
        running = false
        onPoint = null

        // Retorna a geometria ja encaixada nas ruas e, se o ultimo matching ainda
        // nao terminou, preserva tambem a pequena cauda GPS ainda nao processada.
        return snapshot()
    }

    fun isRunning(): Boolean = running

    fun currentDistanceMeters(): Float = synchronized(lock) { totalDistanceMeters }

    /**
     * A UI e o salvamento recebem preferencialmente a rota corrigida pela malha
     * viaria. Enquanto um novo lote esta sendo processado, os ultimos pontos GPS
     * sao mantidos no fim para que a linha nunca pare de crescer na tela.
     */
    fun snapshot(): List<RoutePoint> {
        val raw = synchronized(lock) { rawPoints.toList() }
        val matched = matchedPoints
        val consumed = matchedRawCount.coerceIn(0, raw.size)

        if (matched.size < 2 || consumed < 2) return raw
        if (consumed >= raw.size) return matched

        val combined = ArrayList<RoutePoint>(matched.size + raw.size - consumed)
        combined += matched
        raw.subList(consumed, raw.size).forEach { point ->
            val previous = combined.lastOrNull()
            if (previous == null || distanceMeters(previous, point) >= MIN_OUTPUT_SPACING_METERS) {
                combined += point
            }
        }
        return combined
    }

    private fun acceptLocation(location: Location) {
        if (!running) return
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return

        val previous = synchronized(lock) { rawPoints.lastOrNull() }
        if (previous != null) {
            val previousLocation = Location("rota-certa").apply {
                latitude = previous.latitude
                longitude = previous.longitude
            }
            val delta = previousLocation.distanceTo(location)
            val elapsedSeconds = ((location.time - previous.timestamp).coerceAtLeast(1L)) / 1_000f

            // Rejeita saltos de GPS incompatíveis com um veículo rodoviário normal.
            val plausibleDistance = maxOf(MIN_SPIKE_THRESHOLD_METERS, elapsedSeconds * MAX_PLAUSIBLE_SPEED_MPS)
            if (delta > plausibleDistance) return

            // Evita gravar dezenas de pontos praticamente idênticos quando o veículo está parado.
            if (delta < MIN_MOVEMENT_METERS && (!location.hasSpeed() || location.speed < 0.8f)) return

            synchronized(lock) { totalDistanceMeters += delta }
        }

        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else 0f,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            altitude = if (location.hasAltitude()) location.altitude else null
        )

        val distance = synchronized(lock) {
            rawPoints += point
            totalDistanceMeters
        }

        // Primeiro atualiza imediatamente a UI; em seguida o map matching troca
        // o trecho bruto pela geometria real da rua assim que a resposta chega.
        onPoint?.invoke(point, distance)
        scheduleRoadMatchIfNeeded()
    }

    private fun scheduleRoadMatchIfNeeded() {
        if (!running || matchInFlight) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastMatchStartedAt < MATCH_INTERVAL_MS) return

        val source = synchronized(lock) { rawPoints.toList() }
        if (source.size < MIN_POINTS_FOR_ROAD_MATCH) return
        if (source.size - matchedRawCount < MIN_NEW_POINTS_FOR_ROAD_MATCH && matchedRawCount > 0) return

        val requestedSession = sessionId
        matchInFlight = true
        lastMatchStartedAt = now

        matcherExecutor.execute {
            val corrected = roadMatcher.match(source)

            if (corrected != null && corrected.size >= 2 && requestedSession == sessionId) {
                matchedPoints = corrected
                matchedRawCount = source.size

                // Forca um novo snapshot para a tela substituir o segmento reto
                // pelo traçado encaixado nas ruas/rotatorias.
                if (running) {
                    onPoint?.invoke(source.last(), currentDistanceMeters())
                }
            }

            matchInFlight = false
        }
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    companion object {
        private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
        private const val MIN_MOVEMENT_METERS = 1.5f
        private const val MIN_SPIKE_THRESHOLD_METERS = 120f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 55f // ~198 km/h

        private const val MATCH_INTERVAL_MS = 2_000L
        private const val MIN_POINTS_FOR_ROAD_MATCH = 4
        private const val MIN_NEW_POINTS_FOR_ROAD_MATCH = 4
        private const val MIN_OUTPUT_SPACING_METERS = 0.8f
    }
}
