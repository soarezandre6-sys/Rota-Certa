package com.rotacerta.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rotacerta.app.model.RoutePoint

class RouteRecorder(context: Context) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val points = mutableListOf<RoutePoint>()
    private var totalDistanceMeters = 0f
    private var running = false
    private var onPoint: ((RoutePoint, Float) -> Unit)? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::acceptLocation)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onPoint: (RoutePoint, Float) -> Unit) {
        stop()
        points.clear()
        totalDistanceMeters = 0f
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
        if (running) {
            client.removeLocationUpdates(callback)
        }
        running = false
        onPoint = null
        return points.toList()
    }

    fun isRunning(): Boolean = running

    fun currentDistanceMeters(): Float = totalDistanceMeters

    fun snapshot(): List<RoutePoint> = points.toList()

    private fun acceptLocation(location: Location) {
        if (!running) return
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return

        val previous = points.lastOrNull()
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

            totalDistanceMeters += delta
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
        points += point
        onPoint?.invoke(point, totalDistanceMeters)
    }

    companion object {
        private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
        private const val MIN_MOVEMENT_METERS = 1.5f
        private const val MIN_SPIKE_THRESHOLD_METERS = 120f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 55f // ~198 km/h
    }
}
