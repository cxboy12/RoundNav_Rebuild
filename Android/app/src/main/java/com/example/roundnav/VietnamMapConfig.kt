package com.example.roundnav

import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal object VietnamMapConfig {
    const val TEST_LAT = 10.77689
    const val TEST_LON = 106.70089
    const val DESTINATION_LAT = 10.77978
    const val DESTINATION_LON = 106.69902

    fun streetStyleUrl(): String? = BuildConfig.VIETMAP_TILEMAP_KEY.takeIf { it.isNotBlank() }?.let { key ->
        "https://maps.vietmap.vn/maps/styles/tm/style.json?apikey=${encode(key)}"
    }

    fun serviceUrl(endpoint: String, name: String, vararg parameters: Pair<String, String>): URL {
        require(endpoint.isNotBlank()) { "尚未配置 Vietmap $name" }
        val uri = URI(endpoint)
        val loopback = BuildConfig.DEBUG && uri.scheme == "http" && uri.host == "127.0.0.1"
        require((uri.scheme == "https" || loopback) && uri.host != null &&
            uri.userInfo == null && uri.fragment == null && uri.query == null) { "Vietmap $name 地址无效" }
        val query = parameters.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return URL("$endpoint?$query")
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
