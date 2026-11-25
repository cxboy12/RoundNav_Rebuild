package com.example.roundnav

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

internal object RoadApi {
    private const val TAG = "RoundNavRoadApi"

    fun fetchNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        callback: (List<NearbyRoad>, Throwable?) -> Unit
    ) {
        Thread {
            if (BuildConfig.VIETMAP_ROADS_URL.isBlank()) {
                val roads = VietmapViewportRoads.nearby(latitude, longitude, radiusMeters)
                callback(roads, if (roads.isEmpty()) IllegalStateException("请打开 Vietmap 地图并显示当前位置附近道路") else null)
                return@Thread
            }
            var connection: HttpURLConnection? = null
            try {
                val url = VietnamMapConfig.serviceUrl(
                    BuildConfig.VIETMAP_ROADS_URL, "周边道路接口",
                    "lat" to latitude.toString(), "lon" to longitude.toString(), "radius" to radiusMeters.toString()
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                if (code !in 200..299) error("Vietmap 周边道路接口 HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                check(root.has("roads") || root.has("features")) { "道路接口缺少 roads/features 字段" }
                val roads = parseRoads(root)
                Log.i(TAG, "Nearby roads OK: ${roads.size}")
                callback(roads, null)
            } catch (t: Throwable) {
                callback(emptyList(), t)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun parseRoads(root: JSONObject): List<NearbyRoad> {
        val result = mutableListOf<NearbyRoad>()
        val roads = root.optJSONArray("roads") ?: root.optJSONArray("features") ?: JSONArray()
        for (i in 0 until roads.length()) {
            val item = roads.optJSONObject(i) ?: continue
            val properties = item.optJSONObject("properties") ?: item
            val geometry = item.optJSONObject("geometry")
            val rawType = properties.optString("type", properties.optString("highway", "road"))
            val name = properties.optString("name", "")
            val coordinateArray = item.optJSONArray("coordinates")
                ?: geometry?.optJSONArray("coordinates")
                ?: continue
            val points = mutableListOf<Pair<Double, Double>>()
            // Keep every disconnected part of a MultiLineString as a separate road.
            val first = coordinateArray.opt(0)
            val lines = if (first is JSONArray && first.opt(0) is JSONArray) {
                (0 until coordinateArray.length()).mapNotNull { coordinateArray.optJSONArray(it) }
            } else listOf(coordinateArray)
            for (line in lines) {
                points.clear()
                for (j in 0 until line.length()) {
                    val pair = line.optJSONArray(j) ?: continue
                    if (pair.length() < 2) continue
                    val lon = pair.optDouble(0, Double.NaN)
                    val lat = pair.optDouble(1, Double.NaN)
                    if (lon.isFinite() && lat.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        points.add(lon to lat)
                    }
                }
                if (points.size >= 2) result.add(NearbyRoad(name, rawType, points.toList()))
            }
        }
        return result
    }
}
