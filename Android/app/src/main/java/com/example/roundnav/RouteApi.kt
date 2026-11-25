package com.example.roundnav

import org.json.JSONObject
import java.net.HttpURLConnection
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class RouteResult @JvmOverloads constructor(
    val coordinates: List<Pair<Double, Double>>, // longitude, latitude
    val distanceKm: Double?,
    val durationSeconds: Double?,
    val turns: List<RouteTurn> = emptyList(),
    val traffic: List<RouteTraffic> = emptyList()
)

internal enum class RouteTraffic(val rgb: String, val label: String) {
    UNKNOWN("#4783DD", "无数据"), CLEAR("#16A477", "畅通"),
    SLOW("#DF8414", "缓行"), BUSY("#D84454", "拥堵");

    companion object {
        fun decode(codes: IntArray?) = codes?.map { entries.getOrElse(it) { UNKNOWN } }.orEmpty()
    }
}

/** The configured server proxies Vietmap Route v4 and owns the Services key. */
internal object RouteApi {
    fun fetchMotorcycleRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        callback: (RouteResult?, Throwable?) -> Unit
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = VietnamMapConfig.serviceUrl(
                    BuildConfig.VIETMAP_ROUTE_URL, "路线接口",
                    "point" to "$fromLat,$fromLon", "point" to "$toLat,$toLon"
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                if (code !in 200..299) {
                    stream?.close()
                    error("路线接口 HTTP $code")
                }
                val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                callback(parseResponse(body), null)
            } catch (t: Exception) {
                callback(null, t)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun parseResponse(body: String): RouteResult {
        val json = JSONObject(body)
        check(json.optString("code") == "OK") { "Vietmap 未返回可用路线" }
        val path = json.optJSONArray("paths")?.optJSONObject(0) ?: error("Vietmap 未返回可用路线")
        val coordinates = decodePolyline5(path.getString("points"))
        require(coordinates.size >= 2 && coordinates.all { (lon, lat) ->
            lat in -90.0..90.0 && lon in -180.0..180.0
        }) { "Vietmap 路线坐标无效" }
        val instructions = path.optJSONArray("instructions")
        val steps = List(instructions?.length() ?: 0) { instructions?.optJSONObject(it) }
        val shape = RoutePlayback(coordinates)
        val stepDistances = steps.map { it?.nonNegative("distance") }
        val instructionTotal = stepDistances.filterNotNull().sum()
        val finishIndex = steps.lastOrNull { it?.optInt("sign") == 4 }?.optJSONArray("interval")?.optInt(0, -1)
        // Some Route v4 responses expand the polyline without updating instruction indices.
        // Re-anchor those instructions by accumulated metres when the two total lengths agree.
        val useStepDistances = finishIndex != null && finishIndex != coordinates.lastIndex &&
            stepDistances.all { it != null } && instructionTotal > 0 &&
            kotlin.math.abs(instructionTotal - shape.totalMeters) <= maxOf(15.0, shape.totalMeters * 0.02)
        val turns = buildList {
            var traveled = 0.0
            for ((index, instruction) in steps.withIndex()) {
                if (instruction == null) continue
                val start = if (useStepDistances) shape.nearestVertex(traveled * shape.totalMeters / instructionTotal)
                    else instruction.optJSONArray("interval")?.optInt(0, -1) ?: -1
                traveled += stepDistances[index] ?: 0.0
                if (start !in 1 until coordinates.lastIndex) continue
                val direction = when (instruction.optInt("sign")) {
                    in -3..-1 -> TurnDirection.LEFT
                    in 1..3 -> TurnDirection.RIGHT
                    else -> continue
                }
                add(RouteTurn(start, direction))
            }
        }.distinctBy { it.shapeIndex }
        val congestion = path.optJSONObject("annotations")?.optJSONArray("congestion")
        val traffic = if (congestion == null) emptyList() else {
            val edges = MutableList(coordinates.lastIndex) { RouteTraffic.UNKNOWN }
            for (index in 0 until congestion.length()) {
                val range = congestion.optJSONObject(index) ?: continue
                val first = range.optInt("first", -1)
                val last = range.optInt("last", -1)
                if (first < 0 || last <= first || last > edges.size) continue
                val level = when (range.optString("value")) {
                    "low" -> RouteTraffic.CLEAR
                    "moderate" -> RouteTraffic.SLOW
                    "heavy", "severe" -> RouteTraffic.BUSY
                    else -> RouteTraffic.UNKNOWN
                }
                for (edge in first until last) edges[edge] = level
            }
            edges
        }
        return RouteResult(coordinates, path.nonNegative("distance")?.div(1000),
            path.nonNegative("time")?.div(1000), turns, traffic)
    }

    private fun JSONObject.nonNegative(key: String) = optDouble(key).takeIf { it.isFinite() && it >= 0 }

    private fun decodePolyline5(encoded: String): List<Pair<Double, Double>> {
        require(encoded.isNotEmpty()) { "路线形状为空" }
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        fun readDelta(): Int {
            var result = 0
            var shift = 0
            while (index < encoded.length && shift <= 25) {
                val value = encoded[index++].code - 63
                if (value !in 0..63) break
                result = result or ((value and 0x1f) shl shift)
                if (value < 32) return if (result and 1 == 0) result shr 1 else (result shr 1).inv()
                shift += 5
            }
            error("路线 polyline5 数据不完整或格式无效")
        }
        while (index < encoded.length) {
            lat += readDelta()
            lon += readDelta()
            points.add((lon / 100_000.0) to (lat / 100_000.0))
        }
        return points
    }

    /** Firmware route capacity. */
    private const val ROUTE_POINT_LIMIT = 500
    /** Vertices within this distance of the line through their kept neighbours are dropped. */
    private const val STRAIGHTEN_TOLERANCE_M = 1.0

    /** Douglas-Peucker between consecutive required vertices, measured in local metres. */
    private fun straightenedIndices(points: List<Pair<Double, Double>>, required: Set<Int>, toleranceM: Double): MutableSet<Int> {
        val eastScale = 111_319.49 * cos(Math.toRadians(points.first().second))
        fun x(i: Int) = (points[i].first - points.first().first) * eastScale
        fun y(i: Int) = (points[i].second - points.first().second) * 111_319.49
        val kept = required.toSortedSet()
        val pending = ArrayDeque(kept.toList().zipWithNext())
        while (pending.isNotEmpty()) {
            val (a, b) = pending.removeLast()
            if (b - a < 2) continue
            val ax = x(a)
            val ay = y(a)
            val dx = x(b) - ax
            val dy = y(b) - ay
            val length2 = dx * dx + dy * dy
            var worst = -1
            var worstDistance = toleranceM
            for (i in a + 1 until b) {
                val px = x(i) - ax
                val py = y(i) - ay
                val t = if (length2 == 0.0) 0.0 else ((px * dx + py * dy) / length2).coerceIn(0.0, 1.0)
                val distance = hypot(px - t * dx, py - t * dy)
                if (distance > worstDistance) { worstDistance = distance; worst = i }
            }
            if (worst < 0) continue
            kept.add(worst)
            pending.addLast(a to worst)
            pending.addLast(worst to b)
        }
        return kept
    }

    /**
     * The route shape shared by the phone preview, playback, live matching and the round screen.
     * Route v4 describes a straight street with many slightly offset vertices; each one rounds to
     * its own pixel on the round screen and shows as a step, so those are dropped first. Turns,
     * endpoints and congestion boundaries are always kept. The result is then capped at the
     * firmware capacity. [straighten] = false only applies the cap, so an already displayed
     * route passes through unchanged and the firmware gets exactly the playback vertices.
     */
    @JvmOverloads
    fun displayRoute(route: RouteResult, straighten: Boolean = true): RouteResult {
        val points = route.coordinates
        if (points.size <= 2) return route
        val limit = ROUTE_POINT_LIMIT
        val endpoints = setOf(0, points.lastIndex)
        val turnIndices = route.turns.map { it.shapeIndex }.filter { it in points.indices }.toSet()
        val keepTurns = (endpoints + turnIndices).size <= limit
        val required = (if (keepTurns) endpoints + turnIndices else endpoints).toMutableSet()
        val boundaries = (1 until route.traffic.size).filter { route.traffic[it - 1] != route.traffic[it] }
        if ((required + boundaries).size <= limit) required.addAll(boundaries)

        val selected = if (straighten) straightenedIndices(points, required, STRAIGHTEN_TOLERANCE_M)
            else points.indices.toMutableSet()
        if (selected.size > limit) {
            val candidates = selected.filterNot { it in required }.sorted()
            val available = limit - required.size
            selected.retainAll(required)
            for (index in 0 until available) {
                // Pick the midpoint of each equal-sized bucket of remaining points.
                selected.add(candidates[((index + 0.5) * candidates.size / available).toInt()])
            }
        }
        if (selected.size == points.size) return route
        val ordered = selected.sorted()
        return route.copy(
            coordinates = ordered.map { points[it] },
            turns = if (keepTurns) route.turns.map { it.copy(shapeIndex = ordered.binarySearch(it.shapeIndex)) }
                else emptyList(),
            traffic = if (route.traffic.isEmpty()) emptyList() else ordered.zipWithNext { start, end ->
                val first = route.traffic.getOrElse(start) { RouteTraffic.UNKNOWN }
                if ((start until end).all { route.traffic.getOrNull(it) == first }) first else RouteTraffic.UNKNOWN
            }
        )
    }

    fun toBleCommands(route: RouteResult): ArrayList<String> {
        // Callers pass the displayed route; only enforce the capacity here.
        val drawable = displayRoute(route, straighten = false)
        val selected = drawable.coordinates
        val turns = drawable.turns.associateBy { it.shapeIndex }
        val commands = arrayListOf("B,${selected.size}")
        selected.forEachIndexed { index, p ->
            val lon = p.first
            val lat = p.second
            val traffic = drawable.traffic.getOrNull(index)?.takeUnless { it == RouteTraffic.UNKNOWN }
            val point = "P,$index,${(lat * 1e6).roundToInt()},${(lon * 1e6).roundToInt()}"
            val turn = turns[index]?.direction?.command
            commands.add(when {
                turn != null -> "$point,${traffic?.ordinal ?: 0},$turn"
                traffic != null -> "$point,${traffic.ordinal}"
                else -> point
            })
        }
        commands.add("E")
        return commands
    }
}
