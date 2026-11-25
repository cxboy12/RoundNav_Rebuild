package com.example.roundnav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationCompat
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal data class BridgeStatus(
    val bluetooth: String = "未启动",
    val location: String = "等待定位",
    val roads: String = "连接后自动加载附近道路",
    val bluetoothReady: Boolean = false
)

class BridgeService : Service() {
    companion object {
        private const val TAG = "RoundNavBridge"
        private const val CHANNEL_ID = "roundnav_bridge"
        private const val NOTIFICATION_ID = 1701
        const val ACTION_START = "com.example.roundnav.START"
        const val ACTION_STOP = "com.example.roundnav.STOP"
        const val ACTION_SEND_COMMAND = "com.example.roundnav.SEND_COMMAND"
        const val ACTION_SEND_BATCH = "com.example.roundnav.SEND_BATCH"
        const val ACTION_FETCH_ROADS = "com.example.roundnav.FETCH_ROADS"
        const val ACTION_TEST_ROADS = "com.example.roundnav.TEST_ROADS"
        const val ACTION_SIMULATE = "com.example.roundnav.SIMULATE"
        const val ACTION_COARSE_MAP = "com.example.roundnav.COARSE_MAP"
        private const val ACTION_SEND_ROUTE = "com.example.roundnav.SEND_ROUTE"
        private const val EXTRA_INDOOR = "indoor"
        private const val EXTRA_ROUTE_STATUS = "routeStatus"
        private const val ACTION_START_PLAYBACK = "com.example.roundnav.START_PLAYBACK"
        internal const val ACTION_TOGGLE_PLAYBACK = "com.example.roundnav.TOGGLE_PLAYBACK"
        internal const val ACTION_PLAYBACK_SPEED = "com.example.roundnav.PLAYBACK_SPEED"
        internal const val ACTION_END_PLAYBACK = "com.example.roundnav.END_PLAYBACK"
        private const val EXTRA_PLAYBACK_ID = "playbackId"
        private const val EXTRA_PLAYBACK_POINTS = "playbackPoints"
        private const val EXTRA_PLAYBACK_TURNS = "playbackTurns"
        private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
        internal const val ROUTE_QUEUED = 1
        const val EXTRA_LINE = "line"
        const val EXTRA_LINES = "lines"
        @Volatile var latestLocation: Location? = null
        @Volatile internal var status = BridgeStatus()
            private set
        @Volatile internal var playbackStatus: PlaybackStatus? = null
            private set
        private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        internal fun isRouteLocation(location: Location): Boolean {
            val age = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000L
            return age in 0..RoadMap.MAX_LOCATION_AGE_MS &&
                location.hasAccuracy() && location.accuracy in 0f..RoadMap.MAX_LOCATION_ACCURACY_M &&
                !LocationCompat.isMock(location) &&
                location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
        }

        internal fun sendRoute(context: Context, route: RouteResult, indoor: Boolean, receiver: ResultReceiver) {
            context.startService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_SEND_ROUTE)
                .putStringArrayListExtra(EXTRA_LINES, RouteApi.toBleCommands(route))
                .putExtra(EXTRA_INDOOR, indoor)
                .putExtra(EXTRA_ROUTE_STATUS, receiver))
        }

        internal fun startPlayback(context: Context, id: String, route: RouteResult, speed: Int) {
            val points = route.coordinates.flatMap { (lon, lat) -> listOf(lon, lat) }.toDoubleArray()
            context.startService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_START_PLAYBACK)
                .putExtra(EXTRA_PLAYBACK_ID, id)
                .putExtra(EXTRA_PLAYBACK_POINTS, points)
                .putExtra(EXTRA_PLAYBACK_TURNS, RouteTurn.encode(route.turns))
                .putExtra(EXTRA_PLAYBACK_SPEED, speed))
        }

        internal fun controlPlayback(context: Context, id: String, action: String, speed: Int = 0) {
            context.startService(Intent(context, BridgeService::class.java)
                .setAction(action)
                .putExtra(EXTRA_PLAYBACK_ID, id)
                .putExtra(EXTRA_PLAYBACK_SPEED, speed))
        }
        fun sendCommand(context: Context, line: String) {
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_SEND_COMMAND).putExtra(EXTRA_LINE, line))
        }
        fun sendBatch(context: Context, lines: ArrayList<String>) {
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_SEND_BATCH).putStringArrayListExtra(EXTRA_LINES, lines))
        }
    }

    // GATT callbacks and HTTP results are marshalled to the main looper.
    private val handler = Handler(Looper.getMainLooper())
    private class Write(val line: String, val onSent: (() -> Unit)? = null) {
        val payload = (line.trimEnd('\r', '\n') + "\n").toByteArray(Charsets.UTF_8)
    }
    private val pendingWrites = ArrayDeque<Write>()
    private var pendingGps: Write? = null
    private var pendingPlaybackNavigation: Write? = null
    private var lastPlaybackNavigation: String? = null
    private var inFlight: Write? = null
    private var routeReceiver: ResultReceiver? = null
    private var writeAttempts = 0
    private var writeTimeout: Runnable? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var discovering = false
    private var payloadLimit = 20
    private var running = false
    private var locationManager: LocationManager? = null
    private var previousLocation: Location? = null
    private var lastBearing = 0
    private var lastGpsSentElapsedMs = 0L
    private var loadedCenter: Location? = null
    private var fetchingRoads = false
    private var sendingRoads = false
    private var forceRoadRefresh = false
    private var requestGeneration = 0
    private var nextRoadRequestMs = 0L
    private var lastObservedLocation: Location? = null
    private var testMapLocation: Location? = null
    private enum class MapMode { LIVE, COARSE, FIXED, SIMULATION, ROUTE_PLAYBACK }
    private var mapMode = MapMode.LIVE
    private var simulationStep = 0L
    private class Playback(val id: String, val path: RoutePlayback, var speed: Int) {
        var traveled = 0.0
        var lastTick: Long? = null
    }
    private var playback: Playback? = null
    private val locationAgeLimit: Long get() = if (mapMode == MapMode.COARSE) 60_000L else RoadMap.MAX_LOCATION_AGE_MS
    private val locationAccuracyLimit: Float get() = if (mapMode == MapMode.COARSE) 1_000f else RoadMap.MAX_LOCATION_ACCURACY_M
    private var lastRoadWaitReason: String? = null
    private val reconnect = Runnable { if (running) startScan() }
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            status = status.copy(bluetooth = "未发现 RoundNav，稍后重试")
            handler.postDelayed(reconnect, 3_000)
        }
    }
    private val connectionTimeout = Runnable {
        if (running && txCharacteristic == null && gatt != null) resetConnection("连接超时，重新扫描")
    }
    private val followTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (mapMode == MapMode.SIMULATION && txCharacteristic != null) {
                simulationStep++
                testMapLocation = Location("vietnam-simulation").apply {
                    latitude = VietnamMapConfig.TEST_LAT + RoadMap.simulationOffsetMeters(simulationStep) / 111_319.49
                    longitude = VietnamMapConfig.TEST_LON
                }
                lastBearing = RoadMap.simulationBearing(simulationStep)
                queueGps(testMapLocation!!)
                status = status.copy(location = "模拟移动 · 5米/秒南北往返 · 非手机位置")
            }
            playback?.let { advancePlayback(it, SystemClock.uptimeMillis()) }
            val location = latestLocation
            if (testMapLocation == null && location != null && !isFresh(location)) {
                status = status.copy(location = "${locationDetails(location)}；等待新定位")
            }
            maybeFetchRoads()
            handler.postDelayed(this, 1_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!running || !scanning || gatt != null) return@post
                val name = try { result.device.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
                if (name == "RoundNav") { stopScan(); connect(result.device) }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post {
                if (!running) return@post
                scanning = false
                handler.removeCallbacks(scanTimeout)
                status = status.copy(bluetooth = "扫描失败 $errorCode，稍后重试")
                handler.postDelayed(reconnect, 3_000)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, result: Int, newState: Int) {
            handler.post {
                if (!running || gatt !== g) return@post
                if (result == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    status = status.copy(bluetooth = "已连接，正在准备数据通道")
                    try {
                        if (!g.requestMtu(128)) resetConnection("无法协商蓝牙数据长度，重新连接")
                    } catch (_: SecurityException) { resetConnection("蓝牙权限不足") }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || result != BluetoothGatt.GATT_SUCCESS) {
                    resetConnection("连接已断开 ($result)，重新连接")
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, result: Int) {
            handler.post {
                if (running && gatt === g) {
                    if (result != BluetoothGatt.GATT_SUCCESS || mtu < 64) resetConnection("蓝牙数据长度不足，重新连接")
                    else {
                        payloadLimit = (mtu - 3).coerceAtMost(240)
                        discoverServices(g)
                    }
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, result: Int) {
            handler.post {
                if (!running || gatt !== g) return@post
                val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
                if (result != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                    resetConnection("未找到圆屏数据通道，重新连接")
                    return@post
                }
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                txCharacteristic = characteristic
                handler.removeCallbacks(connectionTimeout)
                status = status.copy(bluetooth = "RoundNav 已就绪", roads = "等待有效定位后自动加载道路", bluetoothReady = true)
                Log.i(TAG, "BLE READY payloadLimit=$payloadLimit")
                currentMapLocation()?.let { queueGps(it) }
                drainQueue()
                maybeFetchRoads()
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
            handler.post {
                if (!running || gatt !== g || characteristic.uuid != RX_UUID) return@post
                val sent = inFlight ?: return@post
                cancelWriteTimeout()
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    inFlight = null
                    writeAttempts = 0
                    sent.onSent?.invoke()
                    drainQueue()
                } else retryWrite(sent, "写入失败 $result")
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = processLocation(location)
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
        override fun onProviderEnabled(provider: String) { }
        override fun onProviderDisabled(provider: String) {
            status = status.copy(location = "$provider 定位已关闭")
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RoundNav Bridge", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_ROUTE) {
            IntentCompat.getParcelableExtra(intent, EXTRA_ROUTE_STATUS, ResultReceiver::class.java)?.let { receiver ->
                sendPreview(intent.getStringArrayListExtra(EXTRA_LINES).orEmpty(),
                    intent.getBooleanExtra(EXTRA_INDOOR, false), receiver)
            }
            if (!running) stopSelf(startId)
            return if (running) START_STICKY else START_NOT_STICKY
        }
        if (intent != null && intent.hasExtra(EXTRA_PLAYBACK_ID)) {
            val id = intent.getStringExtra(EXTRA_PLAYBACK_ID)
            val speed = intent.getIntExtra(EXTRA_PLAYBACK_SPEED, 0)
            if (running && id != null) {
                when (intent.action) {
                    ACTION_START_PLAYBACK -> beginPlayback(id, intent.getDoubleArrayExtra(EXTRA_PLAYBACK_POINTS),
                        RouteTurn.decode(intent.getIntArrayExtra(EXTRA_PLAYBACK_TURNS)), speed)
                    ACTION_TOGGLE_PLAYBACK -> playback?.takeIf { it.id == id }?.let(::togglePlayback)
                    ACTION_PLAYBACK_SPEED -> playback?.takeIf { it.id == id && speed in 5..15 }?.let {
                        advancePlayback(it, SystemClock.uptimeMillis())
                        it.speed = speed
                    }
                    ACTION_END_PLAYBACK -> if (playbackStatus?.id == id) {
                        if (playbackStatus?.phase == PlaybackPhase.UPLOADING) {
                            // Discard the partially queued route as well as the playback.
                            switchMapLocation(testMapLocation, MapMode.ROUTE_PLAYBACK)
                        } else {
                            playback = null
                            playbackStatus = null
                            clearPlaybackNavigation()
                            status = status.copy(location = "沿线模拟已结束 · 停留在模拟位置")
                        }
                    }
                }
            }
            if (!running) stopSelf(startId)
            return if (running) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission() || (Build.VERSION.SDK_INT >= 31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED))) {
            status = status.copy(bluetooth = "缺少权限，请先授权")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle("RoundNav 正在运行")
            .setContentText("实时定位 · 附近道路自动跟随").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
        if (!running) {
            running = true
            startGps()
            startScan()
            handler.post(followTick)
        }
        when (intent?.action) {
            ACTION_START -> {
                switchMapLocation(null, MapMode.LIVE)
                startGps()
            }
            ACTION_COARSE_MAP -> {
                switchMapLocation(null, MapMode.COARSE)
                startGps()
            }
            ACTION_SIMULATE -> {
                simulationStep = 0
                switchMapLocation(Location("vietnam-simulation").apply {
                    latitude = VietnamMapConfig.TEST_LAT
                    longitude = VietnamMapConfig.TEST_LON
                }, MapMode.SIMULATION)
            }
            ACTION_TEST_ROADS -> {
                val point = Location("vietnam-test").apply {
                    latitude = VietnamMapConfig.TEST_LAT
                    longitude = VietnamMapConfig.TEST_LON
                }
                switchMapLocation(point)
            }
            ACTION_SEND_COMMAND -> intent.getStringExtra(EXTRA_LINE)?.let { enqueueLine(it) }
            ACTION_SEND_BATCH -> intent.getStringArrayListExtra(EXTRA_LINES)?.let { enqueueBatch(it) }
            ACTION_FETCH_ROADS -> {
                forceRoadRefresh = true
                status = status.copy(roads = "已请求刷新，等待蓝牙和有效定位")
                maybeFetchRoads()
            }
        }
        return START_STICKY
    }

    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        if (!running || scanning || gatt != null) return
        try {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                status = status.copy(bluetooth = "请打开手机蓝牙")
                handler.postDelayed(reconnect, 3_000)
                return
            }
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(listOf(ScanFilter.Builder().setDeviceName("RoundNav").build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback)
                ?: error("蓝牙扫描不可用")
            scanning = true
            status = status.copy(bluetooth = "正在扫描 RoundNav")
            handler.postDelayed(scanTimeout, 15_000)
        } catch (e: Exception) {
            status = status.copy(bluetooth = "扫描失败：${e.message}")
            handler.postDelayed(reconnect, 3_000)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanning = false
    }

    private fun connect(device: BluetoothDevice) {
        try {
            discovering = false
            status = status.copy(bluetooth = "正在连接 RoundNav")
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(connectionTimeout, 20_000)
        } catch (e: Exception) { resetConnection("连接失败：${e.message}") }
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        try { if (!g.discoverServices()) resetConnection("无法发现设备服务") }
        catch (_: SecurityException) { resetConnection("蓝牙权限不足") }
    }

    private fun resetConnection(message: String) {
        finishRouteSend("路线发送中断：$message。连接就绪后可重发")
        val interruptedPlayback = playback
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(reconnect)
        cancelWriteTimeout()
        val previousGatt = gatt
        gatt = null
        txCharacteristic = null
        discovering = false
        payloadLimit = 20
        inFlight = null
        pendingWrites.clear()
        pendingGps = null
        pendingPlaybackNavigation = null
        lastPlaybackNavigation = null
        interruptedPlayback?.let { failPlayback(it.id, "蓝牙已断开，连接就绪后可从起点重播") }
        writeAttempts = 0
        requestGeneration++
        fetchingRoads = false
        sendingRoads = false
        loadedCenter = null
        nextRoadRequestMs = 0
        try { previousGatt?.disconnect(); previousGatt?.close() } catch (_: Exception) { }
        status = status.copy(bluetooth = message, roads = "重连后重新同步附近道路", bluetoothReady = false)
        if (running) handler.postDelayed(reconnect, 1_500)
    }

    private fun enqueueLine(raw: String) {
        if (raw.isBlank()) return
        if (raw.startsWith("G,")) { pendingGps = Write(raw); drainQueue() }
        else enqueueBatch(listOf(raw))
    }

    private fun sendPreview(lines: List<String>, indoor: Boolean, receiver: ResultReceiver) {
        if (!running || txCharacteristic == null) {
            receiver.routeStatus(0, "请在首页启动蓝牙并等待 RoundNav 就绪，再发送路线")
            return
        }
        if (routeReceiver != null) {
            receiver.routeStatus(0, "已有路线正在发送，请稍后重试")
            return
        }
        if (indoor) {
            if (mapMode != MapMode.FIXED) {
                switchMapLocation(Location("vietnam-test").apply {
                    latitude = VietnamMapConfig.TEST_LAT
                    longitude = VietnamMapConfig.TEST_LON
                })
            } else {
                testMapLocation?.let(::queueGps)
            }
        } else {
            if (latestLocation?.let(::isRouteLocation) != true) {
                receiver.routeStatus(0, "圆屏服务正在等待精确定位，请在首页启用实时模式后重试")
                return
            }
            if (mapMode != MapMode.LIVE) switchMapLocation(null, MapMode.LIVE)
        }
        routeReceiver = receiver
        receiver.routeStatus(ROUTE_QUEUED, "路线已排队，等待蓝牙写入")
        val queued = enqueueBatch(lines) {
            if (routeReceiver === receiver) {
                Log.i(TAG, "Route ATT writes complete: points=${lines.size - 2}")
                finishRouteSend("蓝牙写入完成，请查看圆屏")
            }
        }
        if (!queued) finishRouteSend("蓝牙队列繁忙或数据长度不足，请重试")
    }

    private fun beginPlayback(id: String, points: DoubleArray?, turns: List<RouteTurn>, speed: Int) {
        if (txCharacteristic == null || routeReceiver != null) {
            failPlayback(id, "请等待蓝牙就绪及路线发送完成，再开始模拟")
            return
        }
        if (points == null || points.size !in 4..1000 || points.size % 2 != 0 || speed !in 5..15) {
            failPlayback(id, "无法模拟，路线数据无效")
            return
        }
        val coordinates = List(points.size / 2) { points[it * 2] to points[it * 2 + 1] }
        if (coordinates.any { (lon, lat) -> lat !in -90.0..90.0 || lon !in -180.0..180.0 }) {
            failPlayback(id, "无法模拟，路线坐标无效")
            return
        }
        val path = RoutePlayback(coordinates, turns)
        if (path.totalMeters <= 0.0) {
            failPlayback(id, "起点和终点重合，请选择其他目的地")
            return
        }
        switchMapLocation(Location("vietnam-route-origin").apply {
            latitude = VietnamMapConfig.TEST_LAT
            longitude = VietnamMapConfig.TEST_LON
        }, MapMode.ROUTE_PLAYBACK)
        if (txCharacteristic == null) {
            failPlayback(id, "蓝牙已断开，连接就绪后可从起点重播")
            return
        }
        val active = Playback(id, path, speed)
        playback = active
        playbackStatus = PlaybackStatus(id, PlaybackPhase.UPLOADING, totalMeters = path.totalMeters)
        status = status.copy(location = "正在准备沿线模拟 · 非手机位置")
        val commands = RouteApi.toBleCommands(RouteResult(coordinates, null, null))
        val queued = enqueueBatch(commands) {
            if (playback === active) {
                Log.i(TAG, "Playback route ATT writes complete: points=${coordinates.size}")
                active.lastTick = SystemClock.uptimeMillis()
                publishPlayback(active, PlaybackPhase.RUNNING)
            }
        }
        if (!queued) failPlayback(id, "蓝牙队列繁忙，请重试")
    }

    private fun togglePlayback(active: Playback) {
        val now = SystemClock.uptimeMillis()
        when (playbackStatus?.phase) {
            PlaybackPhase.RUNNING -> {
                advancePlayback(active, now)
                if (playback === active) {
                    active.lastTick = null
                    publishPlayback(active, PlaybackPhase.PAUSED)
                }
            }
            PlaybackPhase.PAUSED -> {
                active.lastTick = now
                publishPlayback(active, PlaybackPhase.RUNNING)
            }
            else -> Unit
        }
    }

    private fun advancePlayback(active: Playback, now: Long) {
        val last = active.lastTick ?: return
        if (now <= last) return
        active.lastTick = now
        active.traveled = (active.traveled + (now - last) * active.speed / 1000.0).coerceAtMost(active.path.totalMeters)
        if (active.traveled >= active.path.totalMeters) {
            playback = null
            publishPlayback(active, PlaybackPhase.FINISHED)
            Log.i(TAG, "Route playback finished: meters=${active.traveled.roundToInt()}")
        } else {
            publishPlayback(active, PlaybackPhase.RUNNING)
        }
    }

    private fun publishPlayback(active: Playback, phase: PlaybackPhase) {
        val point = active.path.pointAt(active.traveled)
        val location = Location("vietnam-route-playback").apply {
            latitude = point.latitude
            longitude = point.longitude
        }
        testMapLocation = location
        lastBearing = point.bearing
        val guidance = active.path.guidanceAt(active.traveled)
        playbackStatus = PlaybackStatus(active.id, phase, point, active.traveled, active.path.totalMeters, guidance = guidance)
        val progress = "${active.traveled.roundToInt()} / ${active.path.totalMeters.roundToInt()} 米"
        val action = when (phase) {
            PlaybackPhase.PAUSED -> "已暂停"
            PlaybackPhase.FINISHED -> "已到达"
            else -> "进行中"
        }
        status = status.copy(location = "沿线模拟$action · $progress")
        queueGps(location)
        queuePlaybackNavigation(guidance.bleLine())
    }

    private fun failPlayback(id: String, message: String) {
        val previous = playbackStatus?.takeIf { it.id == id }
        playback = null
        playbackStatus = previous?.copy(phase = PlaybackPhase.FAILED, message = message, guidance = null)
            ?: PlaybackStatus(id, PlaybackPhase.FAILED, message = message)
        status = status.copy(location = "沿线模拟停止 · $message")
        clearPlaybackNavigation()
    }

    private fun queuePlaybackNavigation(line: String) {
        if (txCharacteristic == null || line == lastPlaybackNavigation) return
        lastPlaybackNavigation = line
        pendingPlaybackNavigation = Write(line)
        drainQueue()
    }

    private fun clearPlaybackNavigation() {
        if (lastPlaybackNavigation != null) queuePlaybackNavigation("S,-1,0")
        lastPlaybackNavigation = null
    }

    private fun ResultReceiver.routeStatus(code: Int, message: String) =
        send(code, Bundle().apply { putString("message", message) })

    private fun finishRouteSend(message: String) {
        val receiver = routeReceiver ?: return
        routeReceiver = null
        receiver.routeStatus(0, message)
    }

    private fun enqueueBatch(lines: List<String>, onSent: (() -> Unit)? = null): Boolean {
        if (lines.isEmpty() || lines.size + pendingWrites.size > 4_000) {
            status = status.copy(roads = "发送队列繁忙，稍后重试")
            return false
        }
        // Keep complete protocol lines together and pack several into each ATT write.
        // This avoids thousands of one-point round trips while preserving firmware limits.
        val packets = mutableListOf<String>()
        val packet = StringBuilder()
        var packetBytes = 0
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8).size
            if (bytes > payloadLimit) {
                status = status.copy(roads = "蓝牙数据长度不足，无法发送道路")
                return false
            }
            if (packetBytes + bytes > payloadLimit) {
                packets.add(packet.toString())
                packet.setLength(0)
                packetBytes = 0
            }
            packet.append(line).append('\n')
            packetBytes += bytes
        }
        if (packet.isNotEmpty()) packets.add(packet.toString())
        packets.forEachIndexed { index, packetText ->
            pendingWrites.addLast(Write(packetText, if (index == packets.lastIndex) onSent else null))
        }
        drainQueue()
        return true
    }

    private fun drainQueue() {
        if (!running || gatt == null || txCharacteristic == null || inFlight != null) return
        val next = pendingGps?.also { pendingGps = null }
            ?: pendingPlaybackNavigation?.also { pendingPlaybackNavigation = null }
            ?: pendingWrites.pollFirst() ?: return
        inFlight = next
        writeAttempts = 0
        writeCurrent(next)
    }

    private fun writeCurrent(write: Write) {
        val currentGatt = gatt ?: return
        val characteristic = txCharacteristic ?: return
        if (inFlight !== write) return
        writeAttempts++
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(characteristic, write.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = write.payload
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }
            if (!accepted) { retryWrite(write, "蓝牙写入忙"); return }
            val timeout = Runnable {
                if (gatt === currentGatt && inFlight === write) resetConnection("写入超时，重新同步")
            }
            writeTimeout = timeout
            handler.postDelayed(timeout, 5_000)
        } catch (e: Exception) { resetConnection("蓝牙发送失败：${e.message}") }
    }

    private fun retryWrite(write: Write, reason: String) {
        if (writeAttempts >= 3) { resetConnection("$reason，重新同步"); return }
        Log.w(TAG, "$reason; retry=$writeAttempts line=${write.line}")
        handler.postDelayed({ if (running && inFlight === write) writeCurrent(write) }, 200)
    }

    private fun cancelWriteTimeout() {
        writeTimeout?.let(handler::removeCallbacks)
        writeTimeout = null
    }

    private fun isFresh(location: Location): Boolean {
        val age = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000L
        return age in 0..locationAgeLimit && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy >= 0 && location.accuracy <= locationAccuracyLimit
    }

    private fun locationDetails(location: Location): String {
        val ageMs = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000L
        val accuracy = if (location.hasAccuracy()) "${location.accuracy.roundToInt()}米" else "未知"
        return "${location.provider} · 精度 $accuracy · ${(ageMs / 1000).coerceAtLeast(0)}秒前"
    }

    private fun currentMapLocation(): Location? = testMapLocation ?: latestLocation?.takeIf(::isFresh)

    private fun switchMapLocation(testPoint: Location?, mode: MapMode = if (testPoint == null) MapMode.LIVE else MapMode.FIXED) {
        finishRouteSend("定位模式已切换，路线发送中断，可重新发送")
        playback = null
        playbackStatus = null
        mapMode = mode
        testMapLocation = testPoint
        lastBearing = 0
        requestGeneration++
        fetchingRoads = false
        sendingRoads = false
        pendingWrites.clear()
        pendingGps = null
        clearPlaybackNavigation()
        loadedCenter = null
        nextRoadRequestMs = 0
        forceRoadRefresh = true
        currentMapLocation()?.let(::queueGps)
        Log.i(TAG, "MAP MODE $mode")
        maybeFetchRoads()
    }

    private fun roadWait(reason: String) {
        status = status.copy(roads = reason)
        if (lastRoadWaitReason != reason) {
            Log.i(TAG, "ROAD WAIT $reason")
            lastRoadWaitReason = reason
        }
    }

    private fun startGps() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(locationListener)
            var registered = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1_000L, 0f, locationListener, Looper.getMainLooper())
                    registered = true
                }
                Log.i(TAG, "LOCATION PROVIDER $provider enabled=${lm.isProviderEnabled(provider)}")
            }
            status = status.copy(location = if (registered) "等待定位：精度≤${locationAccuracyLimit.toInt()}米，${locationAgeLimit / 1000}秒内" else "请打开手机定位")
            listOfNotNull(lm.getLastKnownLocation(LocationManager.GPS_PROVIDER), lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                .sortedBy { it.elapsedRealtimeNanos }.forEach(::processLocation)
        } catch (e: Exception) {
            Log.e(TAG, "LOCATION START failed", e)
            status = status.copy(location = "定位启动失败：${e.message}")
        }
    }

    private fun processLocation(location: Location) {
        if (!running) return
        lastObservedLocation = Location(location)
        Log.i(TAG, "LOCATION RX ${locationDetails(location)} accepted=${isFresh(location)}")
        if (testMapLocation != null) return
        if (!isFresh(location)) {
            if (latestLocation?.let(::isFresh) != true) {
                status = status.copy(location = "${locationDetails(location)}；需${locationAgeLimit / 1000}秒内、精度≤${locationAccuracyLimit.toInt()}米")
            }
            return
        }
        val last = latestLocation
        if (last != null && location.elapsedRealtimeNanos <= last.elapsedRealtimeNanos) return
        if (location.provider != LocationManager.GPS_PROVIDER && last?.provider == LocationManager.GPS_PROVIDER &&
            isFresh(last) && last.accuracy < location.accuracy) return
        latestLocation = Location(location)
        val prefix = if (mapMode == MapMode.COARSE) "粗略预览（位置可能偏差）：" else ""
        status = status.copy(location = prefix + java.lang.String.format(java.util.Locale.US,
            "%.6f, %.6f · 精度 %.0f 米", location.latitude, location.longitude, location.accuracy))
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpsSentElapsedMs < 700) return
        val previous = previousLocation
        var bearing = lastBearing
        if (location.hasBearing() && location.hasSpeed() && location.speed > 1.5f) {
            bearing = location.bearing.roundToInt()
        } else if (previous != null && previous.distanceTo(location) > max(5f, location.accuracy)) {
            bearing = previous.bearingTo(location).roundToInt()
        }
        lastBearing = ((bearing % 360) + 360) % 360
        previousLocation = Location(location)
        lastGpsSentElapsedMs = now
        if (testMapLocation != null) return
        queueGps(location)
        maybeFetchRoads()
    }

    private fun queueGps(location: Location) {
        pendingGps = Write("G,${(location.latitude * 1e6).roundToInt()},${(location.longitude * 1e6).roundToInt()},$lastBearing")
        Log.d(TAG, "GPS TX queued provider=${location.provider}")
        drainQueue()
    }

    private fun maybeFetchRoads() {
        if (!running) return
        if (txCharacteristic == null) { roadWait("等待蓝牙数据通道就绪"); return }
        if (fetchingRoads || sendingRoads) return
        val current = currentMapLocation()
        if (current == null) {
            roadWait(if (lastObservedLocation == null) "尚未收到定位；室内可选模拟移动" else "等待${locationAgeLimit / 1000}秒内、精度≤${locationAccuracyLimit.toInt()}米定位；室内可选模拟移动")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextRoadRequestMs) return
        val center = loadedCenter
        if (!forceRoadRefresh && !RoadMap.needsRefresh(current.latitude, current.longitude, center?.latitude, center?.longitude)) return
        fetchingRoads = true
        forceRoadRefresh = false
        val generation = ++requestGeneration
        val origin = Location(current)
        nextRoadRequestMs = now + RoadMap.MIN_REQUEST_INTERVAL_MS
        status = status.copy(roads = "正在获取附近 ${RoadMap.DOWNLOAD_RADIUS_M.toInt()} 米道路")
        lastRoadWaitReason = null
        val testMode = testMapLocation != null
        val requestMode = mapMode
        Log.i(TAG, "ROAD REQUEST mode=${if (testMode) "vietnam-test" else "live"} radius=${RoadMap.DOWNLOAD_RADIUS_M}")
        RoadApi.fetchNearby(origin.latitude, origin.longitude, RoadMap.DOWNLOAD_RADIUS_M) { roads, error ->
            handler.post {
                if (!running || generation != requestGeneration) return@post
                fetchingRoads = false
                if (error != null || roads.isEmpty()) {
                    Log.w(TAG, "Nearby roads request failed/empty", error)
                    status = status.copy(roads = "${error?.message ?: "当前位置未返回道路"}；10 秒后重试，保留已有地图")
                    nextRoadRequestMs = SystemClock.elapsedRealtime() + RoadMap.RETRY_INTERVAL_MS
                    forceRoadRefresh = true
                    return@post
                }
                val latest = currentMapLocation()
                if (latest == null || origin.distanceTo(latest) > 150f) {
                    forceRoadRefresh = true
                    status = status.copy(roads = "位置已变化，重新获取附近道路")
                    return@post
                }
                sendingRoads = true
                val commands = RoadMap.commands(roads, origin.latitude, origin.longitude)
                val count = commands.first().substringAfter(',').toInt()
                status = status.copy(roads = "正在发送 $count 条道路；GPS 持续跟随")
                if (!enqueueBatch(commands) onSent@{
                        if (generation != requestGeneration) return@onSent
                        sendingRoads = false
                        loadedCenter = origin
                        val modeLabel = when (requestMode) {
                            MapMode.SIMULATION -> "模拟移动（非当前位置）"
                            MapMode.ROUTE_PLAYBACK -> "沿线模拟"
                            MapMode.FIXED -> "胡志明市固定点（非当前位置）"
                            MapMode.COARSE -> "粗略预览（位置可能偏差）"
                            MapMode.LIVE -> "实时地图"
                        }
                        status = status.copy(roads = "$modeLabel：已发送 $count 条道路 · 移动约75米补加载")
                        Log.i(TAG, "Road snapshot sent: roads=$count commands=${commands.size}")
                        maybeFetchRoads()
                    }) {
                    sendingRoads = false
                    forceRoadRefresh = true
                    nextRoadRequestMs = SystemClock.elapsedRealtime() + RoadMap.RETRY_INTERVAL_MS
                }
            }
        }
    }

    private fun stopSession() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
        resetConnection("已停止")
        previousLocation = null
        latestLocation = null
        lastObservedLocation = null
        testMapLocation = null
        mapMode = MapMode.LIVE
        simulationStep = 0
        playback = null
        playbackStatus = null
        lastRoadWaitReason = null
        status = BridgeStatus(bluetooth = "已停止", location = "已停止", roads = "已停止自动地图")
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopSession(); super.onDestroy() }
}
