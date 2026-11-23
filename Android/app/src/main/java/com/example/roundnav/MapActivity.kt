package com.example.roundnav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.tileprovider.MapTileProviderBase
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

class MapActivity : ComponentActivity() {
    companion object {
        internal const val EXTRA_ROUTE_PREVIEW = "routePreview"
    }

    private data class Preview(val route: RouteResult, val origin: GeoPoint, val indoor: Boolean)

    private lateinit var map: MapView
    private lateinit var locationStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var tileStatus: TextView
    private var confirm: Button? = null
    private lateinit var position: Marker
    private lateinit var destination: Marker
    private lateinit var accuracy: Polygon
    private lateinit var originMarker: Marker
    private lateinit var routeLine: Polyline
    private lateinit var routeStatus: TextView
    private lateinit var sendStatus: TextView
    private lateinit var sendButton: Button
    private var selected: GeoPoint? = null
    private var phoneLocation: Location? = null
    private var routeLocation: Location? = null
    private var routePreviewMode = false
    private var preview: Preview? = null
    private var requestId = 0
    private var followPhone = false
    private var referenceView = false
    private val tileMessages = Handler(Looper.getMainLooper()) { message ->
        tileStatus.text = when (message.what) {
            MapTileProviderBase.MAPTILE_SUCCESS_ID -> "长按地图选择目的地 · 蓝色为手机位置，红色为目的地"
            MapTileProviderBase.MAPTILE_FAIL_ID -> "部分底图加载失败，请检查网络或代理后重新打开地图"
            else -> tileStatus.text
        }
        true
    }
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = showLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            locationStatus.text = "定位服务已关闭，可使用宁波室内参考点"
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) startLocations()
        else locationStatus.text = "未获得定位权限，可浏览地图并选择目的地"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routePreviewMode = intent.getBooleanExtra(EXTRA_ROUTE_PREVIEW, false)
        Configuration.getInstance().apply {
            userAgentValue = "RoundNav/" + BuildConfig.VERSION_NAME + " (+https://github.com/cxboy12/RoundNav_Rebuild)"
            osmdroidBasePath = File(filesDir, "maps")
            osmdroidTileCache = File(filesDir, "maps/tiles")
            expirationExtendedDuration = 7L * 24 * 60 * 60 * 1000
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this)
        toolbar.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply {
            text = if (routePreviewMode) "摩托车路线预览" else "地图选点"
            textSize = 21f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(toolbar)
        locationStatus = TextView(this).apply { text = "当前显示宁波参考区域，等待手机定位"; textSize = 14f }
        root.addView(locationStatus)
        tileStatus = TextView(this).apply { text = "底图加载中…"; textSize = 13f }
        root.addView(tileStatus)
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "回到当前位置"
            setOnClickListener {
                followPhone = true
                referenceView = false
                if (!hasLocationPermission()) {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    val current = phoneLocation
                    if (current != null && isRecent(current)) showLocation(current)
                    else {
                        locationStatus.text = "正在等待手机定位，可使用宁波室内参考点"
                        startLocations()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "宁波室内参考点"
            setOnClickListener {
                followPhone = false
                referenceView = true
                map.controller.setZoom(16.0)
                map.controller.animateTo(ningboOrigin())
                locationStatus.text = "宁波室内参考点 · 非手机位置"
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(savedInstanceState?.getDouble("zoom") ?: 16.0)
            controller.setCenter(GeoPoint(
                savedInstanceState?.getDouble("centerLat") ?: BridgeService.NINGBO_TEST_LAT,
                savedInstanceState?.getDouble("centerLon") ?: BridgeService.NINGBO_TEST_LON
            ))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) followPhone = false
                false
            }
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean { select(p); return true }
        }))
        map.tileProvider.tileRequestCompleteHandlers.add(tileMessages)
        accuracy = Polygon(map).apply {
            fillPaint.color = 0x223588D9
            outlinePaint.color = 0x663588D9
            outlinePaint.strokeWidth = dp(1).toFloat()
        }
        map.overlays.add(accuracy)
        if (routePreviewMode) {
            routeLine = Polyline(map).apply {
                outlinePaint.color = Color.rgb(105, 55, 196)
                outlinePaint.strokeWidth = dp(5).toFloat()
                isEnabled = false
            }
            map.overlays.add(routeLine)
            originMarker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = getDrawable(android.R.drawable.ic_menu_mylocation)?.mutate()
                    ?.apply { setTint(Color.rgb(28, 128, 82)) }
                isEnabled = false
            }
            map.overlays.add(originMarker)
        }
        position = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isEnabled = false
            title = "手机位置"
            icon = getDrawable(android.R.drawable.ic_menu_mylocation)?.mutate()?.apply { setTint(Color.rgb(35, 113, 200)) }
        }
        destination = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isEnabled = false
            title = "目的地"
            icon = getDrawable(android.R.drawable.ic_menu_mylocation)?.mutate()?.apply { setTint(Color.rgb(204, 55, 45)) }
        }
        map.overlays.add(position)
        map.overlays.add(destination)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = HtmlCompat.fromHtml("© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>", HtmlCompat.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            gravity = Gravity.END
        })
        selectionStatus = TextView(this).apply { text = "长按地图选择目的地"; textSize = 16f }
        root.addView(selectionStatus)
        if (routePreviewMode) {
            addRouteControls(root)
        } else {
            val confirmButton = Button(this).apply {
                text = "使用此目的地"
                isEnabled = false
                setOnClickListener {
                    selected?.let {
                        DestinationStore.save(this@MapActivity, it.latitude, it.longitude)
                        setResult(RESULT_OK, Intent().putExtra("latitude", it.latitude).putExtra("longitude", it.longitude))
                        finish()
                    }
                }
            }
            confirm = confirmButton
            root.addView(confirmButton)
        }
        setContentView(root)
        val saved = DestinationStore.load(this)
        if (savedInstanceState?.containsKey("selectedLat") == true) {
            select(GeoPoint(savedInstanceState.getDouble("selectedLat"), savedInstanceState.getDouble("selectedLon")))
        } else if (routePreviewMode) {
            select(GeoPoint(intent.getDoubleExtra("latitude", 29.875), intent.getDoubleExtra("longitude", 121.55)))
        } else if (saved != null) select(GeoPoint(saved.latitude, saved.longitude))
        followPhone = savedInstanceState?.getBoolean("followPhone") ?: !routePreviewMode
        referenceView = savedInstanceState?.getBoolean("referenceView") ?: false
        if (referenceView) locationStatus.text = "宁波室内参考点 · 非手机位置"
        savedInstanceState?.getDoubleArray("routeCoordinates")?.let { coordinates ->
            val route = RouteResult(
                coordinates.toList().chunked(2).map { (lon, lat) -> lon to lat },
                savedInstanceState.getDouble("routeDistance", Double.NaN).takeIf { it.isFinite() },
                savedInstanceState.getDouble("routeDuration", Double.NaN).takeIf { it.isFinite() }
            )
            val origin = GeoPoint(savedInstanceState.getDouble("routeOriginLat"), savedInstanceState.getDouble("routeOriginLon"))
            showPreview(Preview(route, origin, savedInstanceState.getBoolean("routeIndoor")), fit = false)
        }
    }

    private fun select(point: GeoPoint) {
        selected = GeoPoint(point.latitude, point.longitude)
        destination.position = selected
        destination.isEnabled = true
        selectionStatus.text = String.format(Locale.CHINA, "目的地  %.6f, %.6f", point.latitude, point.longitude)
        confirm?.isEnabled = true
        if (routePreviewMode) clearPreview("请选择路线起点")
        map.invalidate()
    }

    private fun addRouteControls(root: LinearLayout) {
        val starts = LinearLayout(this)
        starts.addView(Button(this).apply {
            text = "从真实定位规划"
            setOnClickListener { planRoute(indoor = false) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        starts.addView(Button(this).apply {
            text = "从宁波测试点规划"
            setOnClickListener { planRoute(indoor = true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(starts)
        routeStatus = TextView(this)
        root.addView(routeStatus)
        sendStatus = TextView(this)
        root.addView(sendStatus)
        sendButton = Button(this).apply {
            text = "发送预览路线到圆屏"
            isEnabled = false
            setOnClickListener { sendPreview() }
        }
        root.addView(sendButton)
    }

    private fun ningboOrigin() = GeoPoint(BridgeService.NINGBO_TEST_LAT, BridgeService.NINGBO_TEST_LON)

    private fun clearPreview(message: String) {
        requestId++
        preview = null
        originMarker.isEnabled = false
        routeLine.isEnabled = false
        sendButton.isEnabled = false
        routeStatus.text = message
        sendStatus.text = ""
        map.invalidate()
    }

    private fun planRoute(indoor: Boolean) {
        val target = selected ?: return
        val source = if (indoor) "宁波固定测试起点 · 非实时" else "真实手机定位起点"
        clearPreview("$source · 正在规划…")
        val origin = if (indoor) ningboOrigin() else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                routeStatus.text = "真实起点需要精确定位权限，授权后请重新规划"
                return
            }
            val fix = routeLocation?.takeIf(BridgeService::isRouteLocation)
            if (fix == null) {
                routeStatus.text = "等待 15 秒内、精度 50 米以内的真实手机定位"
                startLocations()
                return
            }
            GeoPoint(fix.latitude, fix.longitude)
        }
        followPhone = false
        val request = requestId
        RouteApi.fetchMotorcycleRoute(origin.latitude, origin.longitude, target.latitude, target.longitude) { route, error ->
            runOnUiThread {
                if (isDestroyed || isFinishing || request != requestId) return@runOnUiThread
                if (route == null) routeStatus.text = "$source · 规划失败：${error?.message ?: "没有路线"}"
                else showPreview(Preview(route.copy(coordinates = RouteApi.displayCoordinates(route)), origin, indoor), fit = true)
            }
        }
    }

    private fun showPreview(value: Preview, fit: Boolean) {
        preview = value
        val points = value.route.coordinates.map { (lon, lat) -> GeoPoint(lat, lon) }
        routeLine.setPoints(points)
        routeLine.isEnabled = true
        originMarker.position = value.origin
        originMarker.title = if (value.indoor) "宁波固定测试起点 · 非实时" else "真实手机定位起点"
        originMarker.isEnabled = true
        val distance = value.route.distanceKm?.let { String.format(Locale.CHINA, "%.2f 公里", it) } ?: "距离未知"
        val duration = value.route.durationSeconds?.roundToLong()?.let {
            "预计 ${it / 60} 分 ${it % 60} 秒"
        } ?: "时间未知"
        routeStatus.text = String.format(Locale.CHINA, "%s  %.6f, %.6f\n%s · %s",
            originMarker.title, value.origin.latitude, value.origin.longitude, distance, duration)
        sendButton.isEnabled = true
        if (fit) {
            followPhone = false
            val bounds = BoundingBox.fromGeoPoints(points + value.origin + listOfNotNull(selected))
            map.doOnLayout {
                if (preview !== value) return@doOnLayout
                if (bounds.latitudeSpan == 0.0) bounds.latSouth = (bounds.latSouth - 0.0001).coerceAtLeast(-90.0)
                if (bounds.longitudeSpanWithDateLine == 0.0) bounds.lonWest = (bounds.lonWest - 0.0001).coerceAtLeast(-180.0)
                map.zoomToBoundingBox(bounds, false, dp(28))
            }
        }
        map.invalidate()
    }

    private fun sendPreview() {
        val value = preview ?: return
        sendButton.isEnabled = false
        sendStatus.text = "正在提交到蓝牙服务…"
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (isDestroyed || isFinishing || preview !== value) return
                sendStatus.text = resultData?.getString("message")
                sendButton.isEnabled = resultCode != BridgeService.ROUTE_QUEUED
            }
        }
        try {
            BridgeService.sendRoute(this, value.route, value.indoor, receiver)
        } catch (_: IllegalStateException) {
            sendStatus.text = "无法启动蓝牙服务，请在首页启动后重试"
            sendButton.isEnabled = true
        } catch (_: SecurityException) {
            sendStatus.text = "缺少蓝牙或定位权限，请在首页授权后重试"
            sendButton.isEnabled = true
        }
    }

    private fun hasLocationPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun isRecent(location: Location) =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) in 0..60_000_000_000L

    private fun showLocation(location: Location) {
        if (BridgeService.isRouteLocation(location) &&
            location.elapsedRealtimeNanos >= (routeLocation?.elapsedRealtimeNanos ?: 0L)) {
            routeLocation = Location(location)
        }
        if (!isRecent(location) || !location.hasAccuracy() || location.accuracy > 1000f) return
        val old = phoneLocation
        if (old != null && isRecent(old) && location.elapsedRealtimeNanos < old.elapsedRealtimeNanos) return
        phoneLocation = Location(location)
        val point = GeoPoint(location.latitude, location.longitude)
        position.position = point
        position.isEnabled = true
        accuracy.points = Polygon.pointsAsCircle(point, location.accuracy.toDouble())
        if (!referenceView) {
            locationStatus.text = "手机位置 · 精度约 " + location.accuracy.toInt() + " 米" +
                if (location.accuracy > 50) " · 粗略位置" else ""
        }
        if (followPhone) map.controller.animateTo(point)
        map.invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun startLocations() {
        if (!hasLocationPermission()) return
        BridgeService.latestLocation?.let(::showLocation)
        locations.removeUpdates(listener)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locations.isProviderEnabled(provider)) {
                    locations.getLastKnownLocation(provider)?.let(::showLocation)
                    locations.requestLocationUpdates(provider, 1000L, 0f, listener, mainLooper)
                }
            } catch (_: IllegalArgumentException) {
                locationStatus.text = "部分定位服务不可用，可使用宁波室内参考点"
            } catch (_: SecurityException) {
                locationStatus.text = "定位权限已变更，请重新授权"
            }
        }
    }

    override fun onResume() { super.onResume(); map.onResume(); startLocations() }
    override fun onPause() { locations.removeUpdates(listener); map.onPause(); super.onPause() }
    override fun onDestroy() {
        map.tileProvider.tileRequestCompleteHandlers.remove(tileMessages)
        tileMessages.removeCallbacksAndMessages(null)
        map.onDetach()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putDouble("centerLat", map.mapCenter.latitude)
        out.putDouble("centerLon", map.mapCenter.longitude)
        out.putDouble("zoom", map.zoomLevelDouble)
        out.putBoolean("followPhone", followPhone)
        out.putBoolean("referenceView", referenceView)
        selected?.let { out.putDouble("selectedLat", it.latitude); out.putDouble("selectedLon", it.longitude) }
        preview?.let { value ->
            out.putDoubleArray("routeCoordinates", value.route.coordinates.flatMap { listOf(it.first, it.second) }.toDoubleArray())
            out.putDouble("routeDistance", value.route.distanceKm ?: Double.NaN)
            out.putDouble("routeDuration", value.route.durationSeconds ?: Double.NaN)
            out.putDouble("routeOriginLat", value.origin.latitude)
            out.putDouble("routeOriginLon", value.origin.longitude)
            out.putBoolean("routeIndoor", value.indoor)
        }
        super.onSaveInstanceState(out)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
