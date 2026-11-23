package com.example.roundnav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

internal data class RouteResult(
    val coordinates: List<Pair<Double, Double>>, // longitude, latitude
    val distanceKm: Double?,
    val durationSeconds: Double?
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
        val coordinates = when {
            direct != null -> List(direct.length()) { index ->
                val point = direct.getJSONArray(index)
                point.optDouble(0) to point.optDouble(1)
            }
            legs != null -> buildList {
                for (index in 0 until legs.length()) {
                    val points = decodePolyline6(legs.getJSONObject(index).getString("shape"))
                    addAll(if (lastOrNull() == points.first()) points.drop(1) else points)
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
        return RouteResult(coordinates, distanceKm, summary?.nonNegative("time"))
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

    fun displayCoordinates(route: RouteResult): List<Pair<Double, Double>> {
        val points = route.coordinates
        val limit = 500
        return if (points.size <= limit) points else {
            val step = (points.size - 1).toDouble() / (limit - 1).toDouble()
            (0 until limit).map { points[(it * step).roundToInt()] }
        }
    }

    fun toBleCommands(route: RouteResult): ArrayList<String> {
        val selected = displayCoordinates(route)
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
