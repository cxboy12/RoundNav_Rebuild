package com.example.roundnav

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

private const val TURN_NOTICE_METERS = 500

internal data class PlaybackPoint(val latitude: Double, val longitude: Double, val bearing: Int)

internal enum class TurnDirection(val command: Char, val label: String) {
    LEFT('L', "左转"), RIGHT('R', "右转"), U_TURN('U', "掉头")
}

internal data class RouteTurn(val shapeIndex: Int, val direction: TurnDirection) {
    companion object {
        fun encode(turns: List<RouteTurn>) = turns.flatMap { listOf(it.shapeIndex, it.direction.command.code) }.toIntArray()

        fun decode(data: IntArray?): List<RouteTurn> {
            if (data == null || data.size % 2 != 0) return emptyList()
            return (data.indices step 2).mapNotNull { index ->
                TurnDirection.entries.firstOrNull { it.command.code == data[index + 1] }
                    ?.let { RouteTurn(data[index], it) }
            }
        }
    }
}

internal data class PlaybackGuidance(val direction: TurnDirection?, val meters: Int) {
    val label: String get() = when {
        meters == 0 -> "已到达终点"
        direction == null -> "直行 · $meters 米"
        else -> "${meters} 米后${direction.label}"
    }

    fun bleLine() = "${direction?.command ?: 'S'},${meters.coerceAtMost(1_000_000)},0"
}

/** Interpolate by metres along the same polyline that is sent to the round display. */
internal class RoutePlayback(coordinates: List<Pair<Double, Double>>, turns: List<RouteTurn> = emptyList()) {
    private data class Segment(
        val start: Pair<Double, Double>,
        val end: Pair<Double, Double>,
        val length: Double,
        val endMeters: Double,
        val bearing: Int
    )

    private val shapeDistances = DoubleArray(coordinates.size)
    private val segments = buildList {
        for (index in 1 until coordinates.size) {
            val start = coordinates[index - 1]
            val end = coordinates[index]
            val length = RoadMap.distanceMeters(start.second, start.first, end.second, end.first)
            shapeDistances[index] = shapeDistances[index - 1] + length
            if (length <= 0.0) continue
            val east = (end.first - start.first) * cos(Math.toRadians((start.second + end.second) / 2))
            val north = end.second - start.second
            val heading = Math.toDegrees(atan2(east, north)).roundToInt()
            add(Segment(start, end, length, shapeDistances[index], (heading + 360) % 360))
        }
    }

    val totalMeters = segments.lastOrNull()?.endMeters ?: 0.0
    private val turnDistances = turns.sortedBy { it.shapeIndex }.mapNotNull { turn ->
        shapeDistances.getOrNull(turn.shapeIndex)?.takeIf { it > 0.0 && it < totalMeters }
            ?.let { it to turn.direction }
    }

    fun guidanceAt(distanceMeters: Double): PlaybackGuidance {
        val distance = distanceMeters.coerceIn(0.0, totalMeters)
        val next = turnDistances.firstOrNull { (at, _) -> at > distance }
        val remaining = ceil((next?.first ?: totalMeters) - distance).toInt()
        val direction = next?.second?.takeIf { remaining <= TURN_NOTICE_METERS }
        return PlaybackGuidance(direction, remaining)
    }

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
    val message: String = "",
    val guidance: PlaybackGuidance? = null
)
