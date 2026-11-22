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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.tileprovider.MapTileProviderBase
import java.io.File
import java.util.Locale

class MapActivity : ComponentActivity() {
    private lateinit var map: MapView
    private lateinit var locationStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var tileStatus: TextView
    private lateinit var confirm: Button
    private lateinit var position: Marker
    private lateinit var destination: Marker
    private lateinit var accuracy: Polygon
    private var selected: GeoPoint? = null
    private var phoneLocation: Location? = null
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
        if (hasLocationPermission()) { followPhone = true; startLocations() }
        else locationStatus.text = "未获得定位权限，可浏览地图并选择目的地"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "地图选点"
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
                map.controller.animateTo(GeoPoint(29.8683, 121.5440))
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
                savedInstanceState?.getDouble("centerLat") ?: 29.8683,
                savedInstanceState?.getDouble("centerLon") ?: 121.5440
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
        confirm = Button(this).apply {
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
        root.addView(confirm)
        setContentView(root)
        val saved = DestinationStore.load(this)
        if (savedInstanceState?.containsKey("selectedLat") == true) {
            select(GeoPoint(savedInstanceState.getDouble("selectedLat"), savedInstanceState.getDouble("selectedLon")))
        } else if (saved != null) select(GeoPoint(saved.latitude, saved.longitude))
        followPhone = savedInstanceState?.getBoolean("followPhone") ?: true
        referenceView = savedInstanceState?.getBoolean("referenceView") ?: false
        if (referenceView) locationStatus.text = "宁波室内参考点 · 非手机位置"
    }

    private fun select(point: GeoPoint) {
        selected = GeoPoint(point.latitude, point.longitude)
        destination.position = selected
        destination.isEnabled = true
        selectionStatus.text = String.format(Locale.CHINA, "目的地  %.6f, %.6f", point.latitude, point.longitude)
        confirm.isEnabled = true
        map.invalidate()
    }

    private fun hasLocationPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun isRecent(location: Location) =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) in 0..60_000_000_000L

    private fun showLocation(location: Location) {
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
        super.onSaveInstanceState(out)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
