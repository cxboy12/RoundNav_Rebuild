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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import vn.vietmap.vietmapsdk.Vietmap
import vn.vietmap.vietmapsdk.camera.CameraUpdateFactory
import vn.vietmap.vietmapsdk.geometry.LatLng
import vn.vietmap.vietmapsdk.geometry.LatLngBounds
import vn.vietmap.vietmapsdk.maps.VietMapGL
import vn.vietmap.vietmapsdk.maps.MapView
import vn.vietmap.vietmapsdk.maps.Style
import vn.vietmap.vietmapsdk.module.http.HttpRequestUtil
import vn.vietmap.vietmapsdk.style.layers.CircleLayer
import vn.vietmap.vietmapsdk.style.layers.FillLayer
import vn.vietmap.vietmapsdk.style.layers.LineLayer
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.*
import vn.vietmap.vietmapsdk.style.expressions.Expression
import vn.vietmap.vietmapsdk.style.sources.GeoJsonSource
import vn.vietmap.vietmapsdk.style.sources.VectorSource
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class MapActivity : ComponentActivity() {
    companion object {
        internal const val EXTRA_ROUTE_PREVIEW = "routePreview"
    }

    private data class Preview(
        val route: RouteResult,
        val origin: LatLng,
        val indoor: Boolean,
        val id: String = UUID.randomUUID().toString()
    )

    private lateinit var map: MapView
    private var mapController: VietMapGL? = null
    private var mapStyle: Style? = null
    private var playbackPoint: LatLng? = null
    private var roadViewport: LatLngBounds? = null
    private lateinit var locationStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var tileStatus: TextView
    private var confirm: Button? = null
    private lateinit var routeStatus: TextView
    private lateinit var sendStatus: TextView
    private lateinit var sendButton: Button
    private lateinit var playbackControls: LinearLayout
    private lateinit var playbackButton: Button
    private lateinit var speedButton: Button
    private lateinit var playbackInfo: TextView
    private var playbackSpeed = 10
    private var isSending = false
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackTick = object : Runnable {
        override fun run() {
            showPlayback()
            playbackHandler.postDelayed(this, 500)
        }
    }
    private var selected: LatLng? = null
    private var phoneLocation: Location? = null
    private var routeLocation: Location? = null
    private var routePreviewMode = false
    private var preview: Preview? = null
    private var requestId = 0
    private var followPhone = false
    private var keepRouteInView = false
    private var referenceView = false
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = showLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            locationStatus.text = "定位服务已关闭，可使用胡志明市室内参考点"
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) startLocations()
        else locationStatus.text = "未获得定位权限，可浏览地图并选择目的地"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routePreviewMode = intent.getBooleanExtra(EXTRA_ROUTE_PREVIEW, false)
        playbackSpeed = savedInstanceState?.getInt("playbackSpeed", 10) ?: 10
        Vietmap.getInstance(this)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        if (BuildConfig.VIETMAP_USB_TILE_PROXY) {
            HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8765))).build())
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
        locationStatus = TextView(this).apply { text = "当前显示胡志明市参考区域，等待手机定位"; textSize = 14f }
        root.addView(locationStatus)
        tileStatus = TextView(this).apply { text = "底图加载中…"; textSize = 13f }
        root.addView(tileStatus)
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "回到当前位置"
            setOnClickListener {
                followPhone = true
                keepRouteInView = false
                referenceView = false
                if (!hasLocationPermission()) {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    val current = phoneLocation
                    if (current != null && isRecent(current)) showLocation(current)
                    else {
                        locationStatus.text = "正在等待手机定位，可使用胡志明市室内参考点"
                        startLocations()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "胡志明市室内参考点"
            setOnClickListener {
                followPhone = false
                keepRouteInView = false
                referenceView = true
                mapController?.animateCamera(CameraUpdateFactory.newLatLngZoom(testOrigin(), 16.0))
                locationStatus.text = "胡志明市室内参考点 · 非手机位置"
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        map = MapView(this)
        map.onCreate(savedInstanceState)
        map.addOnDidFailLoadingMapListener {
            tileStatus.text = "Vietmap 底图加载失败，请检查网络和 Tilemap 授权"
        }
        map.addOnDidBecomeIdleListener { publishViewportRoads() }
        map.getMapAsync { controller ->
            mapController = controller
            controller.setMinZoomPreference(3.0)
            controller.setMaxZoomPreference(19.0)
            controller.setPrefetchZoomDelta(0)
            val savedCenter = LatLng(
                savedInstanceState?.getDouble("centerLat") ?: VietnamMapConfig.TEST_LAT,
                savedInstanceState?.getDouble("centerLon") ?: VietnamMapConfig.TEST_LON
            )
            val center = if (followPhone) phoneLocation?.let { LatLng(it.latitude, it.longitude) } ?: savedCenter
                else savedCenter
            controller.moveCamera(CameraUpdateFactory.newLatLngZoom(center, savedInstanceState?.getDouble("zoom") ?: 16.0))
            controller.addOnCameraMoveStartedListener { reason ->
                if (reason == VietMapGL.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followPhone = false
                    keepRouteInView = false
                }
            }
            controller.addOnMapLongClickListener { point -> select(point); true }
            val styleUrl = VietnamMapConfig.streetStyleUrl()
            if (styleUrl == null) {
                tileStatus.text = "未配置 Vietmap Tilemap Key"
            } else {
                controller.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    installOverlays(style)
                    mapStyle = style
                    updateOverlays()
                    tileStatus.text = "长按地图选择目的地 · 蓝色为手机位置，红色为目的地"
                    fitVisibleRoute()
                }
            }
        }
        map.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitVisibleRoute()
        }
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "© VIETMAP"
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
            select(LatLng(savedInstanceState.getDouble("selectedLat"), savedInstanceState.getDouble("selectedLon")))
        } else if (routePreviewMode) {
            select(LatLng(intent.getDoubleExtra("latitude", VietnamMapConfig.DESTINATION_LAT), intent.getDoubleExtra("longitude", VietnamMapConfig.DESTINATION_LON)))
        } else if (saved != null) select(LatLng(saved.latitude, saved.longitude))
        followPhone = savedInstanceState?.getBoolean("followPhone") ?: !routePreviewMode
        keepRouteInView = savedInstanceState?.getBoolean("keepRouteInView") ?: false
        referenceView = savedInstanceState?.getBoolean("referenceView") ?: false
        if (referenceView) locationStatus.text = "胡志明市室内参考点 · 非手机位置"
        savedInstanceState?.getDoubleArray("routeCoordinates")?.let { coordinates ->
            val route = RouteResult(
                coordinates.toList().chunked(2).map { (lon, lat) -> lon to lat },
                savedInstanceState.getDouble("routeDistance", Double.NaN).takeIf { it.isFinite() },
                savedInstanceState.getDouble("routeDuration", Double.NaN).takeIf { it.isFinite() },
                RouteTurn.decode(savedInstanceState.getIntArray("routeTurns")),
                RouteTraffic.decode(savedInstanceState.getIntArray("routeTraffic"))
            )
            val origin = LatLng(savedInstanceState.getDouble("routeOriginLat"), savedInstanceState.getDouble("routeOriginLon"))
            showPreview(Preview(route, origin, savedInstanceState.getBoolean("routeIndoor"),
                savedInstanceState.getString("previewId") ?: UUID.randomUUID().toString()), fit = false)
        }
    }

    private fun select(point: LatLng) {
        selected = LatLng(point.latitude, point.longitude)
        selectionStatus.text = String.format(Locale.CHINA, "目的地  %.6f, %.6f", point.latitude, point.longitude)
        confirm?.isEnabled = true
        if (routePreviewMode) clearPreview("请选择路线起点")
        updateOverlays()
    }

    private fun addRouteControls(root: LinearLayout) {
        val starts = LinearLayout(this)
        starts.addView(Button(this).apply {
            text = "从真实定位规划"
            setOnClickListener { planRoute(indoor = false) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        starts.addView(Button(this).apply {
            text = "从胡志明市测试点规划"
            setOnClickListener { planRoute(indoor = true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(starts)
        routeStatus = TextView(this)
        routeStatus.setPadding(dp(14), dp(10), dp(14), dp(10))
        routeStatus.setTextColor(Color.rgb(35, 49, 69))
        routeStatus.setBackgroundColor(Color.rgb(236, 243, 248))
        root.addView(routeStatus)
        sendStatus = TextView(this)
        root.addView(sendStatus)
        sendButton = Button(this).apply {
            text = "发送预览路线到圆屏"
            isEnabled = false
            setOnClickListener { sendPreview() }
        }
        root.addView(sendButton)
        playbackInfo = TextView(this).apply { visibility = View.GONE }
        root.addView(playbackInfo)
        playbackControls = LinearLayout(this).apply { visibility = View.GONE }
        playbackButton = Button(this).apply { setOnClickListener { togglePlayback() } }
        speedButton = Button(this).apply {
            setOnClickListener {
                playbackSpeed = if (playbackSpeed == 15) 5 else playbackSpeed + 5
                val current = preview ?: return@setOnClickListener
                val phase = BridgeService.playbackStatus?.takeIf { it.id == current.id }?.phase
                if (phase == PlaybackPhase.RUNNING || phase == PlaybackPhase.PAUSED) {
                    BridgeService.controlPlayback(this@MapActivity, current.id, BridgeService.ACTION_PLAYBACK_SPEED, playbackSpeed)
                }
                showPlayback()
            }
        }
        playbackControls.addView(playbackButton, LinearLayout.LayoutParams(0, -2, 1f))
        playbackControls.addView(speedButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(playbackControls)
    }

    private fun testOrigin() = LatLng(VietnamMapConfig.TEST_LAT, VietnamMapConfig.TEST_LON)

    private fun clearPreview(message: String) {
        requestId++
        endPlayback()
        preview = null
        keepRouteInView = false
        isSending = false
        sendButton.isEnabled = false
        routeStatus.text = message
        sendStatus.text = ""
        showPlayback()
        updateOverlays()
    }

    private fun planRoute(indoor: Boolean) {
        val target = selected ?: return
        val source = if (indoor) "胡志明市固定测试起点 · 非实时" else "真实手机定位起点"
        clearPreview("$source · 正在规划…")
        val origin = if (indoor) testOrigin() else {
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
            LatLng(fix.latitude, fix.longitude)
        }
        followPhone = false
        val request = requestId
        RouteApi.fetchMotorcycleRoute(origin.latitude, origin.longitude, target.latitude, target.longitude) { route, error ->
            runOnUiThread {
                if (isDestroyed || isFinishing || request != requestId) return@runOnUiThread
                if (route == null) routeStatus.text = "$source · 规划失败：${error?.message ?: "没有路线"}"
                else showPreview(Preview(RouteApi.displayRoute(route), origin, indoor), fit = true)
            }
        }
    }

    private fun showPreview(value: Preview, fit: Boolean) {
        preview = value
        val originLabel = if (value.indoor) "胡志明市固定测试起点 · 非实时" else "真实手机定位起点"
        val distance = value.route.distanceKm?.let { String.format(Locale.CHINA, "%.2f 公里", it) } ?: "距离未知"
        val duration = value.route.durationSeconds?.roundToLong()?.let {
            "预计 ${it / 60} 分 ${it % 60} 秒"
        } ?: "时间未知"
        routeStatus.text = String.format(Locale.CHINA, "%s  %.6f, %.6f\n%s · %s",
            originLabel, value.origin.latitude, value.origin.longitude, distance, duration)
        val traffic = value.route.traffic
        val totals = DoubleArray(RouteTraffic.entries.size)
        for ((index, level) in traffic.withIndex()) {
            val (aLon, aLat) = value.route.coordinates[index]
            val (bLon, bLat) = value.route.coordinates[index + 1]
            totals[level.ordinal] += RoadMap.distanceMeters(aLat, aLon, bLat, bLon)
        }
        val known = RouteTraffic.entries.drop(1).filter { totals[it.ordinal] > 0 }
        if (known.isEmpty()) routeStatus.append("\n路况数据暂不可用")
        else routeStatus.append("\n" + known.joinToString("  ·  ") { "${it.label} ${totals[it.ordinal].roundToLong()} 米" })
        showPlayback()
        if (fit) {
            followPhone = false
            keepRouteInView = true
            map.post { fitVisibleRoute() }
        }
        updateOverlays()
    }

    private fun fitVisibleRoute() {
        val value = preview?.takeIf { keepRouteInView } ?: return
        val padding = dp(28)
        if (map.height <= 2 * padding || map.width <= 2 * padding) return
        val points = value.route.coordinates.map { (lon, lat) -> LatLng(lat, lon) }
        val bounds = LatLngBounds.Builder().includes(points + value.origin + listOfNotNull(selected)).build()
        mapController?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    private fun sendPreview() {
        val value = preview ?: return
        isSending = true
        showPlayback()
        sendStatus.text = "正在提交到蓝牙服务…"
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (isDestroyed || isFinishing || preview !== value) return
                sendStatus.text = resultData?.getString("message")
                isSending = resultCode == BridgeService.ROUTE_QUEUED
                showPlayback()
            }
        }
        try {
            BridgeService.sendRoute(this, value.route, value.indoor, receiver)
        } catch (_: IllegalStateException) {
            sendStatus.text = "无法启动蓝牙服务，请在首页启动后重试"
            isSending = false
            showPlayback()
        } catch (_: SecurityException) {
            sendStatus.text = "缺少蓝牙或定位权限，请在首页授权后重试"
            isSending = false
            showPlayback()
        }
    }

    private fun togglePlayback() {
        val value = preview?.takeIf { it.indoor } ?: return
        if (!BridgeService.status.bluetoothReady) {
            sendStatus.text = "请先在首页连接圆屏，等待 RoundNav 就绪"
            return
        }
        sendStatus.text = ""
        when (BridgeService.playbackStatus?.takeIf { it.id == value.id }?.phase) {
            PlaybackPhase.RUNNING, PlaybackPhase.PAUSED ->
                BridgeService.controlPlayback(this, value.id, BridgeService.ACTION_TOGGLE_PLAYBACK)
            PlaybackPhase.UPLOADING -> return
            else -> BridgeService.startPlayback(this, value.id, value.route, playbackSpeed)
        }
        followPhone = false
        playbackButton.isEnabled = false
    }

    private fun endPlayback() {
        val current = preview ?: return
        if (BridgeService.playbackStatus?.id == current.id) {
            BridgeService.controlPlayback(this, current.id, BridgeService.ACTION_END_PLAYBACK)
        }
    }

    private fun showPlayback() {
        val current = preview
        val replay = BridgeService.playbackStatus?.takeIf { current?.id == it.id }
        val moving = replay?.phase == PlaybackPhase.RUNNING || replay?.phase == PlaybackPhase.UPLOADING
        val active = moving || replay?.phase == PlaybackPhase.PAUSED
        playbackControls.visibility = if (current?.indoor == true) View.VISIBLE else View.GONE
        sendButton.isEnabled = current != null && !isSending && !active
        playbackButton.isEnabled = !isSending && replay?.phase != PlaybackPhase.UPLOADING
        speedButton.isEnabled = replay?.phase != PlaybackPhase.UPLOADING
        val speedLabel = "${(playbackSpeed * 3.6).toInt()} 公里/小时"
        if (speedButton.text != speedLabel) speedButton.text = speedLabel
        val buttonLabel = when (replay?.phase) {
            PlaybackPhase.UPLOADING -> "正在发送路线…"
            PlaybackPhase.RUNNING -> "暂停模拟"
            PlaybackPhase.PAUSED -> "继续模拟"
            PlaybackPhase.FINISHED, PlaybackPhase.FAILED -> "从起点重播"
            null -> "开始沿线模拟"
        }
        if (playbackButton.text != buttonLabel) playbackButton.text = buttonLabel
        val phaseText = when (replay?.phase) {
            PlaybackPhase.UPLOADING -> "发送预览路线后开始模拟"
            PlaybackPhase.RUNNING -> "模拟行驶"
            PlaybackPhase.PAUSED -> "模拟暂停"
            PlaybackPhase.FINISHED -> "模拟已到达"
            PlaybackPhase.FAILED -> replay.message
            null -> ""
        }
        playbackInfo.visibility = if (replay == null) View.GONE else View.VISIBLE
        playbackInfo.keepScreenOn = moving
        val point = replay?.point
        val details = if (point == null) phaseText else {
            val progress = "${replay.traveledMeters.roundToLong()} / ${replay.totalMeters.roundToLong()} 米"
            listOfNotNull("$phaseText · $progress", replay.guidance?.label, BridgeService.status.roads).joinToString("\n")
        }
        if (playbackInfo.text != details) playbackInfo.text = details
        val next = point?.let { LatLng(it.latitude, it.longitude) }
        if (playbackPoint != next) {
            playbackPoint = next
            updateOverlays()
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
        val point = LatLng(location.latitude, location.longitude)
        if (!referenceView) {
            locationStatus.text = "手机位置 · 精度约 " + location.accuracy.toInt() + " 米" +
                if (location.accuracy > 50) " · 粗略位置" else ""
        }
        if (followPhone) mapController?.animateCamera(CameraUpdateFactory.newLatLng(point))
        updateOverlays()
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
                locationStatus.text = "部分定位服务不可用，可使用胡志明市室内参考点"
            } catch (_: SecurityException) {
                locationStatus.text = "定位权限已变更，请重新授权"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLocations()
        if (routePreviewMode) playbackHandler.post(playbackTick)
    }
    override fun onPause() {
        playbackHandler.removeCallbacks(playbackTick)
        locations.removeUpdates(listener)
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        if (isFinishing) endPlayback()
        mapStyle = null
        map.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        map.onSaveInstanceState(out)
        mapController?.cameraPosition?.let { camera ->
            camera.target?.let { target ->
                out.putDouble("centerLat", target.latitude)
                out.putDouble("centerLon", target.longitude)
            }
            out.putDouble("zoom", camera.zoom)
        }
        out.putBoolean("followPhone", followPhone)
        out.putBoolean("keepRouteInView", keepRouteInView)
        out.putBoolean("referenceView", referenceView)
        out.putInt("playbackSpeed", playbackSpeed)
        selected?.let { out.putDouble("selectedLat", it.latitude); out.putDouble("selectedLon", it.longitude) }
        preview?.let { value ->
            out.putDoubleArray("routeCoordinates", value.route.coordinates.flatMap { listOf(it.first, it.second) }.toDoubleArray())
            out.putIntArray("routeTurns", RouteTurn.encode(value.route.turns))
            out.putIntArray("routeTraffic", value.route.traffic.map { it.ordinal }.toIntArray())
            out.putDouble("routeDistance", value.route.distanceKm ?: Double.NaN)
            out.putDouble("routeDuration", value.route.durationSeconds ?: Double.NaN)
            out.putDouble("routeOriginLat", value.origin.latitude)
            out.putDouble("routeOriginLon", value.origin.longitude)
            out.putBoolean("routeIndoor", value.indoor)
            out.putString("previewId", value.id)
        }
        super.onSaveInstanceState(out)
    }
    override fun onStart() {
        super.onStart()
        map.onStart()
    }

    override fun onStop() {
        map.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        map.onLowMemory()
    }

    private fun installOverlays(style: Style) {
        (style.getLayer("building") as? FillLayer)?.minZoom = 15f
        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
        for (name in listOf("accuracy", "route", "origin", "position", "destination", "playback")) {
            style.addSource(GeoJsonSource("roundnav-$name", empty))
        }
        style.addLayer(FillLayer("roundnav-accuracy", "roundnav-accuracy").withProperties(
            fillColor(Color.rgb(53, 136, 217)), fillOpacity(0.15f)
        ))
        style.addLayer(LineLayer("roundnav-route", "roundnav-route").withProperties(
            lineColor(Expression.get("color")), lineWidth(5f)
        ))
        val markerColors = mapOf("origin" to 0xFF1C8052.toInt(), "position" to 0xFF2371C8.toInt(),
            "destination" to 0xFFCC372D.toInt(), "playback" to 0xFFE27000.toInt())
        for ((name, color) in markerColors) {
            style.addLayer(CircleLayer("roundnav-$name", "roundnav-$name").withProperties(
                circleColor(color), circleRadius(7f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)
            ))
        }
    }

    private fun publishViewportRoads() {
        if (BuildConfig.VIETMAP_ROADS_URL.isNotBlank()) return
        val controller = mapController ?: return
        if (controller.cameraPosition.zoom < 14) return
        val bounds = controller.projection.visibleRegion.latLngBounds
        if (roadViewport == bounds) return
        val source = mapStyle?.getSourceAs<VectorSource>("openmaptiles") ?: return
        val roads = source.querySourceFeatures(arrayOf("road"), null)
        if (roads.isEmpty()) return
        val buildings = source.querySourceFeatures(arrayOf("building"), null)
        VietmapViewportRoads.publish(bounds, roads, buildings)
        roadViewport = bounds
        android.util.Log.i("RoundNavVietmap", "SDK features loaded: roads=${roads.size} buildings=${buildings.size}")
    }

    private fun updateOverlays() {
        val style = mapStyle ?: return
        fun update(name: String, geometry: Geometry?) {
            val features = geometry?.let { arrayOf(Feature.fromGeometry(it)) } ?: emptyArray()
            style.getSourceAs<GeoJsonSource>("roundnav-$name")?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
        fun LatLng.geoJson() = Point.fromLngLat(longitude, latitude)
        val current = phoneLocation
        val accuracyRing = current?.let { location ->
            val latScale = 111_319.49
            val lonScale = latScale * kotlin.math.cos(Math.toRadians(location.latitude))
            if (lonScale < 1.0) null else (0..36).map { step ->
                val angle = step % 36 * Math.PI / 18
                Point.fromLngLat(location.longitude + location.accuracy * kotlin.math.sin(angle) / lonScale,
                    location.latitude + location.accuracy * kotlin.math.cos(angle) / latScale)
            }
        }
        update("accuracy", accuracyRing?.let { Polygon.fromLngLats(listOf(it)) })
        update("position", current?.let { Point.fromLngLat(it.longitude, it.latitude) })
        update("destination", selected?.geoJson())
        update("origin", preview?.origin?.geoJson())
        update("playback", playbackPoint?.geoJson())
        val segments = preview?.route?.let { route ->
            buildList {
                var start = 0
                while (start < route.coordinates.lastIndex) {
                    val level = route.traffic.getOrElse(start) { RouteTraffic.UNKNOWN }
                    var end = start + 1
                    while (end < route.coordinates.lastIndex &&
                        route.traffic.getOrElse(end) { RouteTraffic.UNKNOWN } == level) end++
                    val points = route.coordinates.subList(start, end + 1).map { (lon, lat) -> Point.fromLngLat(lon, lat) }
                    add(Feature.fromGeometry(LineString.fromLngLats(points)).apply { addStringProperty("color", level.rgb) })
                    start = end
                }
            }
        }.orEmpty()
        style.getSourceAs<GeoJsonSource>("roundnav-route")?.setGeoJson(FeatureCollection.fromFeatures(segments))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
