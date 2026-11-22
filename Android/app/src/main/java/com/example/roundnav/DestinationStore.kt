package com.example.roundnav

import android.content.Context

internal object DestinationStore {
    data class Point(val latitude: Double, val longitude: Double)

    fun load(context: Context): Point? {
        val prefs = context.getSharedPreferences("destination", Context.MODE_PRIVATE)
        val lat = prefs.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("longitude", null)?.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) Point(lat, lon) else null
    }

    fun save(context: Context, latitude: Double, longitude: Double) {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        context.getSharedPreferences("destination", Context.MODE_PRIVATE).edit()
            .putString("latitude", latitude.toString())
            .putString("longitude", longitude.toString()).apply()
    }
}
