package com.example.roundnav

import kotlin.math.*

internal data class NearbyRoad(
    val name: String,
    val type: String,
    /** WGS84 coordinates in (longitude, latitude) order. */
    val points: List<Pair<Double, Double>>
)

/** The display shows 150 m; the extra downloaded area covers movement during refresh. */
internal object RoadMap {
    const val DOWNLOAD_RADIUS_M = 300.0
    const val REFRESH_DISTANCE_M = 45.0
    const val MIN_REQUEST_INTERVAL_MS = 2_500L
    const val LOOK_AHEAD_M = 65.0
    const val RETRY_INTERVAL_MS = 10_000L
    const val MAX_LOCATION_AGE_MS = 15_000L
    const val MAX_LOCATION_ACCURACY_M = 50f

    /** 5 m/s, 270 m north then back, repeated without a discontinuity at the loop. */
    fun simulationOffsetMeters(step: Long): Double {
        val phase = step % 108
        return (if (phase <= 54) phase else 108 - phase) * 5.0
    }

    fun simulationBearing(step: Long): Int = if (step % 108 < 54) 0 else 180

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = Math.toRadians(lat2 - lat1) * 6_378_137.0
        val dx = Math.toRadians(lon2 - lon1) * 6_378_137.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot(dx, dy)
    }

    fun needsRefresh(lat: Double, lon: Double, loadedLat: Double?, loadedLon: Double?): Boolean =
        loadedLat == null || loadedLon == null ||
            distanceMeters(lat, lon, loadedLat, loadedLon) >= REFRESH_DISTANCE_M

    /** Select nearby geometry, rather than the server's arbitrary first 40 entries. */
    fun selectRoads(roads: List<NearbyRoad>, lat: Double, lon: Double): List<NearbyRoad> {
        fun distance(road: NearbyRoad): Double {
            val cs = cos(Math.toRadians(lat))
            fun local(p: Pair<Double, Double>) =
                (Math.toRadians(p.first - lon) * 6_378_137.0 * cs) to
                    (Math.toRadians(p.second - lat) * 6_378_137.0)
            return road.points.zipWithNext().minOfOrNull { (a, b) ->
                val (ax, ay) = local(a)
                val (bx, by) = local(b)
                val dx = bx - ax
                val dy = by - ay
                val length2 = dx * dx + dy * dy
                val t = if (length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
                hypot(ax + t * dx, ay + t * dy)
            } ?: Double.POSITIVE_INFINITY
        }
        return roads.filter { it.points.size >= 2 }.sortedBy(::distance).take(40)
    }

    /** Keep corners when reducing a long road to the firmware's 80-point capacity. */
    fun simplify(points: List<Pair<Double, Double>>, limit: Int = 80): List<Pair<Double, Double>> {
        if (points.size <= limit) return points
        val selected = sortedSetOf(0, points.lastIndex)
        val cs = cos(Math.toRadians(points.first().second))
        while (selected.size < limit) {
            var bestIndex = -1
            var bestError = -1.0
            for ((start, end) in selected.toList().zipWithNext()) {
                val a = points[start]
                val b = points[end]
                val dx = (b.first - a.first) * cs
                val dy = b.second - a.second
                val length2 = dx * dx + dy * dy
                for (i in start + 1 until end) {
                    val x = (points[i].first - a.first) * cs
                    val y = points[i].second - a.second
                    val t = if (length2 == 0.0) 0.0 else ((x * dx + y * dy) / length2).coerceIn(0.0, 1.0)
                    val error = (x - t * dx).pow(2) + (y - t * dy).pow(2)
                    if (error > bestError) { bestError = error; bestIndex = i }
                }
            }
            if (bestIndex < 0) break
            selected.add(bestIndex)
        }
        return selected.map { points[it] }
    }

    fun commands(roads: List<NearbyRoad>, lat: Double, lon: Double, ahead: Pair<Double, Double>? = null): List<String> {
        val (buildings, streets) = roads.partition { it.type == "building" }
        val nearest = selectRoads(buildings, lat, lon)
        val nearbyBuildings = if (ahead == null) nearest.take(12) else {
            val (aheadLon, aheadLat) = ahead
            val forward = selectRoads(buildings, aheadLat, aheadLon)
            (nearest.take(8) + forward.take(8) + nearest).distinct().take(16)
        }
        val selected = nearbyBuildings + selectRoads(streets, lat, lon).take(minOf(24, 40 - nearbyBuildings.size))
        val lines = mutableListOf("RS,${selected.size}", "RC,${(lat * 1e6).roundToInt()},${(lon * 1e6).roundToInt()}")
        selected.forEachIndexed { id, road ->
            val building = road.type == "building"
            val points = simplify(road.points, if (building) 8 else 16)
            val roadClass = when (road.type.lowercase()) {
                "motorway", "motorway_link" -> 0
                "trunk", "trunk_link" -> 1
                "primary", "primary_link" -> 2
                "secondary", "secondary_link" -> 3
                else -> 4
            }
            lines.add("RB,$id,${points.size},$roadClass" + if (building) ",1" else "")
            points.forEachIndexed { index, p ->
                lines.add("RP,$id,$index,${(p.second * 1e6).roundToInt()},${(p.first * 1e6).roundToInt()}")
            }
            lines.add("RE,$id")
        }
        lines.add("RF")
        return lines
    }
}
