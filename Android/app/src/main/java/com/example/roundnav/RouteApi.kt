package com.example.roundnav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

internal data class RouteResult(
    val coordinates: List<Pair<Double, Double>>, // longitude, latitude
    val distanceKm: Double?,
    val durationSeconds: Double?,
    val turns: List<RouteTurn> = emptyList()
)

/** Basic Valhalla motorcycle route request. The server's /ningbo/route endpoint must proxy Valhalla's native JSON API. */
internal object RouteApi {
    fun fetchMotorcycleRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        callback: (RouteResult?, Throwable?) -> Unit
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val request = JSONObject().apply {
                    put("locations", JSONArray().put(
                        JSONObject().put("lat", fromLat).put("lon", fromLon).put("type", "break")
                    ).put(
                        JSONObject().put("lat", toLat).put("lon", toLon).put("type", "break")
                    ))
                    put("costing", "motorcycle")
                    put("units", "kilometers")
                    put("shape_format", "polyline6")
                }
                connection = (URL("${BuildConfig.ROUNDNAV_BASE_URL}/ningbo/route").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
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
        val trip = json.optJSONObject("trip")
        val legs = trip?.optJSONArray("legs") ?: json.optJSONArray("trip")
        val direct = json.optJSONArray("coordinates")
        val turns = mutableListOf<RouteTurn>()
        val coordinates = when {
            direct != null -> List(direct.length()) { index ->
                val point = direct.getJSONArray(index)
                point.optDouble(0) to point.optDouble(1)
            }
            legs != null -> buildList {
                for (index in 0 until legs.length()) {
                    val leg = legs.getJSONObject(index)
                    val points = decodePolyline6(leg.getString("shape"))
                    val sharedEndpoint = lastOrNull() == points.first()
                    val offset = if (sharedEndpoint) size - 1 else size
                    addAll(if (sharedEndpoint) points.drop(1) else points)
                    val maneuvers = leg.optJSONArray("maneuvers") ?: continue
                    for (i in 0 until maneuvers.length()) {
                        val maneuver = maneuvers.optJSONObject(i) ?: continue
                        val shapeIndex = maneuver.optInt("begin_shape_index", -1)
                        if (shapeIndex !in points.indices) continue
                        // Valhalla maneuver types for slight, normal and sharp turns, and U-turns.
                        val direction = when (maneuver.optInt("type")) {
                            in 9..11 -> TurnDirection.RIGHT
                            12, 13 -> TurnDirection.U_TURN
                            in 14..16 -> TurnDirection.LEFT
                            else -> continue
                        }
                        turns.add(RouteTurn(offset + shapeIndex, direction))
                    }
                }
            }
            else -> emptyList()
        }
        require(coordinates.size >= 2 && coordinates.all { (lon, lat) ->
            lat in -90.0..90.0 && lon in -180.0..180.0
        }) { "路线接口未返回有效坐标" }
        val summary = trip?.optJSONObject("summary") ?: json.optJSONObject("summary")
        val length = summary?.nonNegative("length")
        val distanceKm = when (trip?.optString("units", "kilometers") ?: json.optString("units", "kilometers")) {
            "kilometers" -> length
            "miles" -> length?.times(1.609344)
            else -> null
        }
        return RouteResult(coordinates, distanceKm, summary?.nonNegative("time"), turns)
    }

    private fun JSONObject.nonNegative(key: String) = optDouble(key).takeIf { it.isFinite() && it >= 0 }

    private fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
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
            error("路线 polyline6 数据不完整或格式无效")
        }
        while (index < encoded.length) {
            lat += readDelta()
            lon += readDelta()
            points.add((lon / 1_000_000.0) to (lat / 1_000_000.0))
        }
        return points
    }

    fun displayRoute(route: RouteResult): RouteResult {
        val points = route.coordinates
        val limit = 500
        if (points.size <= limit) return route

        val endpoints = setOf(0, points.lastIndex)
        val turnIndices = route.turns.map { it.shapeIndex }.toSet()
        val keepTurns = (endpoints + turnIndices).size <= limit
        val required = if (keepTurns) endpoints + turnIndices else endpoints
        val candidates = points.indices.filterNot { it in required }
        val available = limit - required.size
        val selected = required.toMutableSet()
        for (index in 0 until available) {
            // Pick the midpoint of each equal-sized bucket of remaining points.
            selected.add(candidates[((index + 0.5) * candidates.size / available).toInt()])
        }
        val ordered = selected.sorted()
        return route.copy(
            coordinates = ordered.map { points[it] },
            turns = if (keepTurns) route.turns.map { it.copy(shapeIndex = ordered.binarySearch(it.shapeIndex)) }
                else emptyList()
        )
    }

    fun toBleCommands(route: RouteResult): ArrayList<String> {
        val selected = displayRoute(route).coordinates
        val commands = arrayListOf("B,${selected.size}")
        selected.forEachIndexed { index, p ->
            val lon = p.first
            val lat = p.second
            commands.add("P,$index,${(lat * 1e6).roundToInt()},${(lon * 1e6).roundToInt()}")
        }
        commands.add("E")
        return commands
    }
}
