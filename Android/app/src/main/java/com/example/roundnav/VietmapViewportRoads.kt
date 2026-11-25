package com.example.roundnav

import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import vn.vietmap.vietmapsdk.geometry.LatLng
import vn.vietmap.vietmapsdk.geometry.LatLngBounds
import kotlin.math.cos

/** Roads exposed by the SDK for the loaded street-map viewport. */
internal object VietmapViewportRoads {
    private data class Snapshot(val bounds: LatLngBounds, val roads: List<NearbyRoad>)
    private val excludedClasses = setOf("rail", "transit", "ferry", "aerialway")

    @Volatile private var snapshot: Snapshot? = null

    fun publish(bounds: LatLngBounds, features: List<Feature>) {
        val roads = buildList {
            for (feature in features) {
                val type = feature.getStringProperty("class") ?: "road"
                if (type in excludedClasses) continue
                val lines = when (val geometry = feature.geometry()) {
                    is LineString -> listOf(geometry.coordinates())
                    is MultiLineString -> geometry.coordinates()
                    else -> continue
                }
                val name = feature.getStringProperty("name").orEmpty()
                for (points in lines) {
                    if (points.size >= 2) add(NearbyRoad(name, type, points.map { it.longitude() to it.latitude() }))
                }
            }
        }.distinct()
        if (roads.isNotEmpty()) snapshot = Snapshot(bounds, roads)
    }

    fun nearby(lat: Double, lon: Double, radius: Double): List<NearbyRoad> {
        val current = snapshot ?: return emptyList()
        // Require the whole visible round-screen area. Extra roads may come from tile buffers.
        val screenRadius = radius / 2
        val latMargin = screenRadius / 111_319.49
        val eastScale = 111_319.49 * cos(Math.toRadians(lat))
        if (eastScale < 1) return emptyList()
        val lonMargin = screenRadius / eastScale
        if (!current.bounds.contains(LatLng(lat - latMargin, lon - lonMargin)) ||
            !current.bounds.contains(LatLng(lat + latMargin, lon + lonMargin))) return emptyList()
        return RoadMap.selectRoads(current.roads, lat, lon)
    }
}
