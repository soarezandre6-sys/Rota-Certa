package com.rotacerta.app.model

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val altitude: Double?
)

data class RecordedRoute(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val finishedAt: Long,
    val distanceMeters: Float,
    val points: List<RoutePoint>
)
