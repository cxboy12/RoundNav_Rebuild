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
    const val MIN_REQUEST_INTERVAL_MS = 1_500L
    const val LOOK_AHEAD_M = 65.0
    const val RETRY_INTERVAL_MS = 10_000L
    const val MAX_LOCATION_AGE_MS = 15_000L
    const val MAX_LOCATION_ACCURACY_M = 50f

    /** Display radius (150 m) plus one refresh step and a margin; nothing farther is sent. */
    const val KEEP_RADIUS_M = 210.0
    /** Must match ROAD_MAX_COUNT in ESP32/main/road_store.h. */
    const val MAX_ENTRIES = 160
    const val MAX_STREET_ENTRIES = 64
    const val MAX_BUILDINGS = MAX_ENTRIES - MAX_STREET_ENTRIES
    /** Firmware stores up to 32 points per street piece; leave room for chunk overlap. */
    const val STREET_POINTS = 24
    /** Street vertices closer than this to the simplified line are not sent. */
    const val STREET_TOLERANCE_M = 0.8
    const val BUILDING_POINTS = 8
    const val MIN_BUILDING_AREA_M2 = 12.0
    /** 6378137 m * pi / 180 / 1e6, shared with the firmware decoder. */
    const val METERS_PER_E6 = 0.1113194907932736
    /** Firmware road classes: 0 motorway .. 4 local street, 5 building, 6 service/alley. */
    const val CLASS_SERVICE = 6

    private val excludedTypes = setOf(
        "path", "footway", "cycleway", "steps", "pedestrian", "bridleway",
        "corridor", "raceway", "busway", "bus_guideway"
    )

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

    /** Firmware class for a map feature type, or null for footpaths the rider cannot use. */
    fun roadClass(type: String): Int? = when (type.lowercase()) {
        in excludedTypes -> null
        "motorway", "motorway_link" -> 0
        "trunk", "trunk_link" -> 1
        "primary", "primary_link" -> 2
        "secondary", "secondary_link", "tertiary", "tertiary_link" -> 3
        "service", "track", "alley", "driveway" -> CLASS_SERVICE
        else -> 4
    }

    /** Local metres around the snapshot centre, using the same rounding as the RC command. */
    private class Frame(lat: Double, lon: Double) {
        val latE6 = (lat * 1e6).roundToInt()
        val lonE6 = (lon * 1e6).roundToInt()
        private val east = METERS_PER_E6 * cos(Math.toRadians(latE6 / 1e6))
        fun x(p: Pair<Double, Double>) = (p.first * 1e6 - lonE6) * east
        fun y(p: Pair<Double, Double>) = (p.second * 1e6 - latE6) * METERS_PER_E6
    }

    private fun segmentDistance(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx - px, ay + t * dy - py)
    }

    private fun distance(points: List<Pair<Double, Double>>, frame: Frame, px: Double, py: Double): Double =
        points.zipWithNext().minOfOrNull { (a, b) ->
            segmentDistance(frame.x(a), frame.y(a), frame.x(b), frame.y(b), px, py)
        } ?: Double.POSITIVE_INFINITY

    /** Select nearby geometry, rather than the server's arbitrary first 40 entries. */
    fun selectRoads(roads: List<NearbyRoad>, lat: Double, lon: Double): List<NearbyRoad> {
        val frame = Frame(lat, lon)
        return roads.filter { it.points.size >= 2 }.sortedBy { distance(it.points, frame, 0.0, 0.0) }.take(40)
    }

    /**
     * Keep corners when reducing a long road to the firmware's point capacity. With a positive
     * [toleranceMeters], vertices that lie that close to the simplified line are also dropped,
     * so a straight street becomes one segment instead of a row of rounded, offset points.
     */
    @JvmOverloads
    fun simplify(points: List<Pair<Double, Double>>, limit: Int = 80, toleranceMeters: Double = 0.0): List<Pair<Double, Double>> {
        if (points.size <= 2 || (toleranceMeters <= 0.0 && points.size <= limit)) return points
        val tolerance = toleranceMeters / 111_319.49
        val tolerance2 = tolerance * tolerance
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
            if (bestIndex < 0 || bestError <= tolerance2) break
            selected.add(bestIndex)
        }
        return selected.map { points[it] }
    }

    /** Parts of a street whose segments come within [radius] of the centre. */
    private fun clip(points: List<Pair<Double, Double>>, frame: Frame, radius: Double): List<List<Pair<Double, Double>>> {
        val runs = mutableListOf<List<Pair<Double, Double>>>()
        var run = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (segmentDistance(frame.x(a), frame.y(a), frame.x(b), frame.y(b), 0.0, 0.0) <= radius) {
                if (run.isEmpty()) run.add(a)
                run.add(b)
            } else if (run.isNotEmpty()) {
                runs.add(run)
                run = mutableListOf()
            }
        }
        if (run.size >= 2) runs.add(run)
        return runs
    }

    /** First point relative to RC, then each point relative to the previous one, in metres. */
    private fun encode(prefix: String, points: List<Pair<Double, Double>>, frame: Frame): String {
        val line = StringBuilder(prefix)
        var lastX = 0
        var lastY = 0
        for (p in points) {
            val x = frame.x(p).roundToInt()
            val y = frame.y(p).roundToInt()
            line.append(',').append(x - lastX).append(',').append(y - lastY)
            lastX = x
            lastY = y
        }
        return line.toString()
    }

    private fun fits(line: String, payloadBytes: Int) = line.toByteArray(Charsets.UTF_8).size + 1 <= payloadBytes

    private fun area(points: List<Pair<Double, Double>>, frame: Frame): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += frame.x(a) * frame.y(b) - frame.x(b) * frame.y(a)
        }
        return abs(sum) / 2
    }

    private class Shape(val roadClass: Int, val points: List<Pair<Double, Double>>, val score: Double)

    /**
     * One map snapshot for the round screen: RS,n · RC,lat,lon · RQ buildings · RL streets · RF.
     * Every line plus its newline fits [payloadBytes], so one ATT write never splits a command.
     */
    @JvmOverloads
    fun commands(
        roads: List<NearbyRoad>,
        lat: Double,
        lon: Double,
        ahead: Pair<Double, Double>? = null,
        payloadBytes: Int = 61
    ): List<String> {
        val frame = Frame(lat, lon)
        val aheadX = ahead?.let { frame.x(it) }
        val aheadY = ahead?.let { frame.y(it) }
        // While moving, rank geometry ahead of the rider above geometry already passed.
        fun score(points: List<Pair<Double, Double>>): Double {
            val here = distance(points, frame, 0.0, 0.0)
            return if (aheadX == null || aheadY == null) here
            else (here + distance(points, frame, aheadX, aheadY)) / 2
        }

        val (footprints, streets) = roads.partition { it.type == "building" }

        val buildings = footprints.mapNotNull { building ->
            var ring = building.points
            if (ring.size >= 5 && ring.first() == ring.last()) ring = ring.dropLast(1)
            if (ring.size < 4 || area(ring, frame) < MIN_BUILDING_AREA_M2) return@mapNotNull null
            val closed = ring + ring.first()
            if (distance(closed, frame, 0.0, 0.0) > KEEP_RADIUS_M) return@mapNotNull null
            val shape = (BUILDING_POINTS downTo 4).asSequence()
                .map { simplify(ring, it) }
                .firstOrNull { it.size >= 4 && fits(encode("RQ,999", it, frame), payloadBytes) }
                ?: return@mapNotNull null
            Shape(5, shape, score(closed))
        }.sortedBy { it.score }.take(MAX_BUILDINGS)

        val streetPieces = buildList<Shape> {
            for (street in streets) {
                val roadClass = roadClass(street.type) ?: continue
                val bonus = when (roadClass) { 0 -> 80.0; 1 -> 60.0; 2 -> 45.0; 3 -> 25.0; CLASS_SERVICE -> -30.0; else -> 0.0 }
                for (run in clip(street.points, frame, KEEP_RADIUS_M)) {
                    val points = simplify(run, STREET_POINTS, STREET_TOLERANCE_M)
                    // Split long pieces so each command fits; neighbouring chunks share a point.
                    var start = 0
                    while (start < points.lastIndex) {
                        var end = start + 1
                        while (end < points.lastIndex &&
                            fits(encode("RL,999,$roadClass", points.subList(start, end + 2), frame), payloadBytes)) end++
                        val chunk = points.subList(start, end + 1)
                        add(Shape(roadClass, chunk, score(chunk) - bonus))
                        start = end
                    }
                }
            }
        }.distinctBy { it.roadClass to it.points }
            .sortedBy { it.score }
            .take(MAX_STREET_ENTRIES)
            // Draw alleys first and major roads last, so major roads stay on top.
            .sortedBy { when (it.roadClass) { CLASS_SERVICE -> 0; 4 -> 1; 3 -> 2; 2 -> 3; 1 -> 4; else -> 5 } }

        val lines = mutableListOf("RS,${buildings.size + streetPieces.size}", "RC,${frame.latE6},${frame.lonE6}")
        buildings.forEachIndexed { id, shape -> lines.add(encode("RQ,$id", shape.points, frame)) }
        streetPieces.forEachIndexed { index, shape ->
            lines.add(encode("RL,${buildings.size + index},${shape.roadClass}", shape.points, frame))
        }
        lines.add("RF")
        return lines
    }

    /** Mirrors the firmware decoder for RL/RQ lines; used by the host-side map checks. */
    fun decodeShape(line: String, centerLatE6: Int, centerLonE6: Int): List<Pair<Double, Double>> {
        val fields = line.split(',')
        val header = if (fields[0] == "RQ") 2 else 3
        val east = METERS_PER_E6 * cos(Math.toRadians(centerLatE6 / 1e6))
        var x = 0L
        var y = 0L
        return (header until fields.size step 2).map { i ->
            x += fields[i].toLong()
            y += fields[i + 1].toLong()
            val pointLon = (centerLonE6 + (x / east).roundToLong()) / 1e6
            val pointLat = (centerLatE6 + (y / METERS_PER_E6).roundToLong()) / 1e6
            pointLon to pointLat
        }
    }
}
