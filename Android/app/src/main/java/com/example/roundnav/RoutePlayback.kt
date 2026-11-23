package com.example.roundnav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

internal data class PlaybackPoint(val latitude: Double, val longitude: Double, val bearing: Int)

/** Interpolate by metres along the same polyline that is sent to the round display. */
internal class RoutePlayback(coordinates: List<Pair<Double, Double>>) {
    private data class Segment(
        val start: Pair<Double, Double>,
        val end: Pair<Double, Double>,
        val length: Double,
        val endMeters: Double,
        val bearing: Int
    )

    private val segments = buildList {
        var distance = 0.0
        for ((start, end) in coordinates.zipWithNext()) {
            val length = RoadMap.distanceMeters(start.second, start.first, end.second, end.first)
            if (length <= 0.0) continue
            val east = (end.first - start.first) * cos(Math.toRadians((start.second + end.second) / 2))
            val north = end.second - start.second
            val heading = Math.toDegrees(atan2(east, north)).roundToInt()
            distance += length
            add(Segment(start, end, length, distance, (heading + 360) % 360))
        }
    }

    val totalMeters = segments.lastOrNull()?.endMeters ?: 0.0

    fun pointAt(distanceMeters: Double): PlaybackPoint {
        val distance = distanceMeters.coerceIn(0.0, totalMeters)
        val index = segments.binarySearchBy(distance) { it.endMeters }
        val segment = segments[(if (index < 0) -index - 1 else index + 1).coerceAtMost(segments.lastIndex)]
        val fraction = (1.0 - (segment.endMeters - distance) / segment.length).coerceIn(0.0, 1.0)
        val (startLon, startLat) = segment.start
        val (endLon, endLat) = segment.end
        return PlaybackPoint(
            startLat + (endLat - startLat) * fraction,
            startLon + (endLon - startLon) * fraction,
            segment.bearing
        )
    }
}

internal enum class PlaybackPhase { UPLOADING, RUNNING, PAUSED, FINISHED, FAILED }

internal data class PlaybackStatus(
    val id: String,
    val phase: PlaybackPhase,
    val point: PlaybackPoint? = null,
    val traveledMeters: Double = 0.0,
    val totalMeters: Double = 0.0,
    val message: String = ""
)
