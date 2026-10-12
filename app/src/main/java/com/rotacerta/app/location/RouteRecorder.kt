package com.rotacerta.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rotacerta.app.model.RoutePoint

class RouteRecorder(context: Context) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

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
            .setMinUpdateDistanceMeters(2.5f)
            .build()

        client.requestLocationUpdates(request, callback, contextMainLooper(client))
    }

    fun stop(): List<RoutePoint> {
        if (running) {
            client.removeLocationUpdates(callback)
        }
        running = false
        onPoint = null
        return points.toList()
    }

    fun currentDistanceMeters(): Float = totalDistanceMeters

    fun snapshot(): List<RoutePoint> = points.toList()

    private fun acceptLocation(location: Location) {
        if (!running) return
        if (location.accuracy > 60f) return

        val previous = points.lastOrNull()
        if (previous != null) {
            val previousLocation = Location("rota-certa").apply {
                latitude = previous.latitude
                longitude = previous.longitude
            }
            val delta = previousLocation.distanceTo(location)
            if (delta < 1.5f && location.speed < 0.8f) return
            totalDistanceMeters += delta
        }

        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            accuracy = location.accuracy,
            speed = location.speed,
            bearing = location.bearing,
            altitude = if (location.hasAltitude()) location.altitude else null
        )
        points += point
        onPoint?.invoke(point, totalDistanceMeters)
    }

    private fun contextMainLooper(client: FusedLocationProviderClient) = android.os.Looper.getMainLooper()
}
