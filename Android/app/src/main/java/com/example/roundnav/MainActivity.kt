package com.example.roundnav

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var liveStatus: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            val state = BridgeService.status
            liveStatus.text = "蓝牙：${state.bluetooth}\n定位：${state.location}\n道路：${state.roads}"
            statusHandler.postDelayed(this, 1_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        status.text = if (hasRequiredPermissions()) "蓝牙和精确定位权限已具备，可以启动 RoundNav" else "仍缺少蓝牙或精确定位权限，请在系统设置中允许"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveStatus = TextView(this).apply { textSize = 17f }
        content.addView(liveStatus, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply { text = "连接圆屏后自动加载附近道路，地图跟随手机位置。"; textSize = 14f }
        content.addView(status, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        addButton(content, "检查 / 请求权限") { requestNeededPermissions() }
        addButton(content, "启动蓝牙 + GPS + 自动跟随地图") {
            if (hasRequiredPermissions()) {
                val i = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_START)
                ContextCompat.startForegroundService(this, i)
                status.text = "正在扫描 RoundNav，并等待 GPS 定位……"
            } else requestNeededPermissions()
        }
        addButton(content, "停止 BLE + GPS") {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
            status.text = "已请求停止"
        }
        addButton(content, "发送导航测试：左转 140 米") {
            BridgeService.sendCommand(this, "L,140,0")
            status.text = "已加入发送队列：L,140,0"
        }
        addButton(content, "发送 GPS 测试点（成都天府广场附近）") {
            BridgeService.sendCommand(this, "G,30659500,104065700,90")
            status.text = "已加入测试 GPS 命令队列"
        }
        addButton(content, "手动刷新附近道路（自动跟随已启用）") {
            if (!hasRequiredPermissions()) {
                requestNeededPermissions()
                return@addButton
            }
            val i = Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_FETCH_ROADS)
            ContextCompat.startForegroundService(this, i)
            status.text = "已请求刷新；实际进度见上方道路状态"
        }
        addButton(content, "宁波固定坐标测试道路（非实时，启动按钮恢复）") {
            if (!hasRequiredPermissions()) {
                requestNeededPermissions()
                return@addButton
            }
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_TEST_ROADS))
            status.text = "测试位置：宁波 29.8683, 121.5440；用于验证下载与圆屏显示，点启动恢复真实定位。"
        }
        val destLat = EditText(this).apply {
            hint = "目的地纬度（例如 30.662）"
            setText("30.662")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val destLon = EditText(this).apply {
            hint = "目的地经度（例如 104.075）"
            setText("104.075")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        content.addView(destLat, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(destLon, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        addButton(content, "按当前位置规划摩托车路线并发送到圆屏") {
            val origin = BridgeService.latestLocation
            val targetLat = destLat.text.toString().toDoubleOrNull()
            val targetLon = destLon.text.toString().toDoubleOrNull()
            if (origin == null) {
                status.text = "尚未取得 GPS 定位，请先启动 BLE + 实时 GPS"
            } else if (targetLat == null || targetLon == null || targetLat !in -90.0..90.0 || targetLon !in -180.0..180.0) {
                status.text = "目的地经纬度格式不正确"
            } else {
                status.text = "正在请求 Valhalla 摩托车路线……"
                RouteApi.fetchMotorcycleRoute(origin.latitude, origin.longitude, targetLat, targetLon) { route, error ->
                    runOnUiThread {
                        if (error != null || route == null) {
                            status.text = "路线请求失败：${error?.message ?: "没有路线"}"
                        } else {
                            BridgeService.sendRoute(this, route)
                            status.text = "路线已加入 BLE 队列：${route.coordinates.size} 个坐标点"
                        }
                    }
                }
            }
        }
        val hint = TextView(this).apply {
            text = "\n使用步骤：授权后启动自动跟随。蓝牙就绪并取得有效定位后自动加载附近道路，无需反复点击刷新。屏幕显示半径 150 米，下载周边 300 米道路，移动约 75 米自动补加载；保持北向上，位置箭头位于中心。网络异常时保留已有地图。\n\n需要同时更新 ESP32 固件。GPS 测试点只用于静止调试，下一次真实定位会恢复当前位置。"
            textSize = 14f
        }
        content.addView(hint)
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        if (!hasRequiredPermissions()) requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(refreshStatus)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private fun addButton(parent: LinearLayout, title: String, action: () -> Unit) {
        val button = Button(this).apply { text = title; setOnClickListener { action() } }
        parent.addView(button, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 })
    }

    private fun requestedPermissions(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        val core = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            core += Manifest.permission.BLUETOOTH_SCAN
            core += Manifest.permission.BLUETOOTH_CONNECT
        }
        return core.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestNeededPermissions() {
        val missing = requestedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) status.text = "所需权限已具备" else permissionLauncher.launch(missing.toTypedArray())
    }
}
