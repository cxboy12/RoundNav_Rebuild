# RoundNav 重建源码包（ESP32 + Android）

显示传输优化（2026-11-22）：

- 根据实机日志，每帧约 297 毫秒、16 次分批传输，地图位置计算约 0.5 毫秒。本轮将 BSP 的绘图缓冲区从 20 行增至 320 行，同时匹配 SPI 最大传输尺寸，启用 PSRAM 直接 DMA，并将传输队列深度设为 1。
- `ESP32/cmake/roundnav_display.cmake` 在构建目录生成 BSP 的适配副本。依赖下载目录保持原始内容，BSP 源码变化导致适配失效时，配置阶段会明确报错。
- 烧录后应看到 `RoundNav display: rows=320, queue=1, direct PSRAM DMA`。运行室内模拟移动 30 秒，对比 `DISPLAY PERF` 中的 `render_ms`、`flushes_per_frame` 和新增的 `submit_ms`（一帧各次传输提交回调耗时之和，包含回调内可能的等待）。
- 本轮固件仍需实机确认显示、蓝牙连接和实际耗时，不能以编译成功推断帧率已经改善。

刷新性能检查（2026-11-22）：

- 本轮固件将地图动画绑定到 LVGL 绘制前事件，统一使用 33 毫秒周期。可见像素未变化时跳过地图重画，动画继续推进。
- 烧录 ESP32 后，在 VS Code 的 ESP-IDF Monitor 中查看串口日志，手机连接后运行“室内模拟移动”至少 30 秒，保留包含 `DISPLAY PERF` 的日志。
- 移动期间约每 5 秒输出一次。关注 `render_ms/max_ms`（LVGL 绘制与提交的平均/最大耗时）、`wait_ms`（LVGL 等待传输的时间）、`flushes_per_frame`（每帧分批数）和 `rebuild_max_ms`（底图重画的最长耗时）。
- `lvgl_fps` 统计实际触发绘制的次数，不是面板扫描帧率。低速移动时只在跨过像素边界后重画，因此该值可能低于动画更新频率。最后一次异步 DMA 可能在绘制事件结束后完成。
- 日志用于确定后续缓冲区与传输调整的效果，当前硬件流畅度仍需实测。

圆屏移动平滑优化（2026-11-22）：

- 需要重新编译并烧录 **ESP32 固件**，Android App 可继续使用当前版本。
- 之前每次收到 GPS 直接跳到新位置；现在按 40 毫秒定时器在两次位置间插值，目标约 25 次位置更新/秒，实际帧率取决于屏幕渲染负载。
- 过渡时间根据接收间隔设定为 200–1500 毫秒，因此显示位置会有相应的跟随延迟；不预测尚未收到的位置。首次定位、蓝牙重连和超过 150 米的位置跳变直接切换。
- 箭头按最短角度旋转；移动期间复用道路样式并缓存投影比例，减少重复计算。道路获取和蓝牙协议保持兼容。
- 室内验收：烧录后连接 App，点“室内模拟移动”，等道路显示，再观察南北往返和超过 75 米补图时的连续性；断开蓝牙后画面应停止移动。

室内测试新增两种模式（点普通“启动”按钮恢复精确实时模式，点“停止”结束）：

- **室内模拟移动**：不依赖手机定位，以宁波固定点为起点，5米/秒向北移动270米后返回并循环；持续发送模拟位置，超过75米自动补加载道路。用于验证平移、箭头方向和补图，不是实际导航或实际路线。
- **室内粗略位置预览**：显式允许60秒内、报告精度≤1000米的手机定位，界面标注位置可能偏差。可预览估计位置附近道路，但无法验证真实行驶精度。普通实时模式仍要求15秒内、精度≤50米。

## 当前改动：附近道路自动跟随（2026-11-22）

本轮目标为手机 BLE 连接圆屏后，自动显示当前位置附近道路，并随手机位置移动。
**需要同时更新 Android APK 与 ESP32 固件**，原来的路线与转向测试按钮仍然保留。

- 在 Android 中授权并点“启动蓝牙 + GPS + 自动跟随地图”，取得新鲜且精度在 50 米以内的定位后自动加载道路。
- 圆屏视野半径保持 150 米、北向上；下载半径 300 米的道路作为移动缓冲，离上次加载中心约 75 米自动补加载。
- GPS 更新优先发送，仅保留最新待发位置；地图平移由 ESP32 根据 GPS 重投影已有道路，不依赖每次重新下载。
- 道路请求串行、至少相隔 5 秒；失败或空数据 10 秒后重试，保留旧地图。重连时重新请求并下发完整道路。
- 道路使用 `RS,count` 开始整批接收，沿用 `RC/RB/RP/RE`，以 `RF` 结束。固件检查每条道路点位完整性后一次替换画面；中断或缺点时保留旧道路。
- `RC` 表示道路覆盖中心，不覆盖已经收到的实时 GPS 中心。旧版逐条道路命令仍受支持。
- 根据实际 MTU 将多条完整命令合并为一次写入（最多 240 字节）；写入失败重试，超时重连，避免逐点写入拖慢摩托车移动时的地图更新。
- 安卓首页显示真实 BLE、定位、道路请求与发送状态；“已发送”表示 ATT 写入完成，不代表已经通过圆屏视觉验收。

建议实测顺序：

1. 烧录本轮固件并安装本轮 APK，停止旧服务后重新启动。
2. 在宁波室外等待有效定位，检查道路状态从“获取”变为“发送”并显示道路数量。
3. 先步行移动 20–30 米，确认箭头保持中心、道路向反方向平移；无需点击刷新。
4. 继续移动超过 75 米，确认自动补加载，新图切换时没有清屏或跳回旧位置。
5. 临时断开网络，检查已有道路保留；恢复网络后检查能否继续补加载。
6. 断开再连接 BLE，检查重新同步道路；结束后点击停止，确认不会自行重连。

宁波公开测试坐标 `29.8683,121.5440` 的接口检查：150 米返回 15 条道路、300 米返回 35 条道路（检查当次结果）。未访问手机当前坐标。
本轮已在本机编译 Android 调试 APK 与 ESP32 固件，尚未进行手机/圆屏实机验收。
`tests/RoadMapChecks.java` 提供无 Android 运行环境的几何与协议容量检查，运行方法见 `tests/run_map_checks.ps1`。

以下为原工程说明，其中手动请求道路的步骤已由上述自动流程替代。

这是一套按目前已经确认的 RoundNav 硬件、协议和功能重新整理的可维护基线工程。它不是丢失文件的逐字恢复版；聊天记录没有包含所有旧文件的完整源码，所以本包重新搭建了相互匹配的 BLE、GPS、地图绘制和 API 桥接部分。

## 1. 工程内容

```text
RoundNav_Rebuild/
├─ ESP32/
│  ├─ CMakeLists.txt
│  ├─ sdkconfig.defaults
│  ├─ partitions.csv
│  └─ main/
│     ├─ CMakeLists.txt
│     ├─ idf_component.yml
│     ├─ app_main.c
│     ├─ roundnav_events.h
│     ├─ roundnav_ble.c
│     ├─ roundnav_ble.h
│     ├─ nav_ui.c
│     └─ nav_ui.h
└─ Android/
   ├─ settings.gradle.kts
   ├─ build.gradle.kts
   ├─ gradle.properties
   └─ app/
      ├─ build.gradle.kts
      └─ src/main/
         ├─ AndroidManifest.xml
         └─ java/com/example/roundnav/
            ├─ MainActivity.kt
            ├─ BridgeService.kt
            ├─ RoadApi.kt
            └─ RouteApi.kt
```

## 2. 已知硬件和软件目标

- 开发板：Waveshare ESP32-S3-Touch-AMOLED-1.75，466×466 CO5300 AMOLED、CST9217 触摸。
- ESP-IDF：5.5.5；芯片目标：`esp32s3`；PSRAM：8 MB Octal。
- Android：包名 `com.example.roundnav`，最低 Android 8.0（API 26），目标 SDK 35。
- BLE 名称：`RoundNav`。
- 地图服务：保留 150 米附近道路半径；每批最多 40 条道路、每条道路最多 80 个点。

## 3. ESP32 工程编译

先在 Windows 解压整个文件夹。打开已配置好 ESP-IDF 5.5.5 的 PowerShell，进入 `ESP32` 目录：

```powershell
cd <解压目录>\RoundNav_Rebuild\ESP32
idf.py set-target esp32s3
idf.py reconfigure
idf.py build
```

烧录到 COM3 并打开日志：

```powershell
idf.py -p COM3 flash monitor
```

首次编译需要联网下载 `main/idf_component.yml` 中的 Waveshare managed component。工程调用官方 BSP 的 `bsp_display_start()`、`bsp_display_lock()` 和 `bsp_display_unlock()`，屏幕不是通用 SPI 屏幕驱动。

如果组件管理器提示组件名或版本无法解析，先不要替换屏幕驱动；把完整错误日志发回来再按你安装的 Waveshare 组件版本调整。

## 4. Android 工程编译

用 Android Studio 打开 `Android` 文件夹（不要只打开 `app` 文件夹），等待 Gradle Sync，然后执行 Build > Make Project。调试 APK 默认生成在：

```text
Android\app\build\outputs\apk\debug\app-debug.apk
```

手机安装后，先授权蓝牙扫描、蓝牙连接和精确定位，再点“启动 BLE + 实时 GPS”。Android 端使用系统 `LocationManager`，不依赖 Google Play Services / GBox。服务以前台服务形式运行，GPS 更新会按约 700 毫秒以上的间隔限制发送，避免 BLE 队列被定位数据淹没。

## 5. BLE 协议（ESP32 和 Android 已统一）

所有命令以 UTF-8 文本行发送，每条命令以换行符结束。BLE 自定义服务和 RX 特征 UUID：

```text
Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
RX:      6e400002-b5a3-f393-e0a9-e50e24dcca9e
```

| 命令 | 格式 | 用途 |
|---|---|---|
| 导航提示 | `L,140,0` / `R,140,0` / `S,0,0` / `U,30,0` | 左转、右转、直行、掉头；第二字段为米数 |
| 路线开始 | `B,count` | 开始一条路线，点数 2–500 |
| 路线点 | `P,index,lat_e6,lon_e6` | 路线经纬度，单位为度×1,000,000 |
| 路线结束 | `E` | 完成路线 |
| 道路中心 | `RC,lat_e6,lon_e6` | 设置附近道路数据的中心 |
| 道路开始 | `RB,road_id,count,road_class` | 开始道路，ID 0–39，点数 2–80，类别 0–4 |
| 道路点 | `RP,road_id,index,lat_e6,lon_e6` | 道路坐标点 |
| 道路结束 | `RE,road_id` | 完成道路 |
| 实时 GPS | `G,lat_e6,lon_e6,bearing_deg` | 手机位置与方向角；方向角 0–359 度，正北为 0，顺时针增加 |

Android `LocationManager` 提供位置；运动时优先用 GPS bearing，低速时用相邻定位点估算方向。静止时方向角会保持最近一次有效值。

## 6. 后端接口约定

Android 的服务器地址在 `Android/app/build.gradle.kts` 中：

```kotlin
buildConfigField("String", "ROUNDNAV_BASE_URL", "\"http://111.229.191.7\"")
```

服务器迁移后只需要修改这里。

附近道路模块按下面格式解析 `/nearby-roads`：

```json
{
  "roads": [
    {
      "name": "人民中路一段",
      "type": "primary",
      "coordinates": [[104.0647, 30.6588], [104.0649, 30.6606]]
    }
  ]
}
```

也兼容 GeoJSON `features[].geometry.coordinates`。道路坐标数组顺序是 `[longitude, latitude]`。

路线模块向 `/route` 发送 Valhalla 原生 POST JSON（`costing=motorcycle`，`shape_format=polyline6`），读取 `trip.legs[0].shape`。如果你服务器现有 `/route` 是自定义 GET 参数或自定义 JSON 包装，需要只调整 `RouteApi.kt` 的请求/响应映射；不要修改 BLE 点位协议。

## 7. 当前版本边界

1. 本包重建了设备显示、BLE 收包、道路/路线绘制、手机原生 GPS、附近道路拉取和 Valhalla 路线几何发送。
2. 没有恢复旧版 Google Maps 通知解析器；它此前本身不稳定，本版以手机系统 GPS 为位置来源。
3. 当前 UI 显示转向文字与距离，路线几何绘制已包含；基于 Valhalla maneuver 的自动逐步转向指令还需要按服务器实际返回的 maneuvers 数据结构单独接入。
4. 我当前运行环境没有 ESP-IDF 工具链和 Android SDK，因此这个包已做源码结构与协议核对，但**没有在这里实际执行 `idf.py build` 或 Android Gradle 编译**。第一次在你的电脑编译时，若出现错误，把第一段完整错误输出贴过来，我们再逐个修复。不要一次性改多个文件。

## 8. 推荐首次验证顺序

1. 先只编译并烧录 ESP32，确认屏幕显示 RoundNav 圆形底图且 BLE 名称可扫描。
2. 安装 Android APK，授权权限，启动 BLE + 实时 GPS，确认日志出现 `BLE connected` 和 `GPS TX G,...`。
3. 点“发送 GPS 测试点”验证方向三角形旋转；测试后再次启动真实 GPS。
4. 请求 150 米附近道路，确认道路背景出现。
5. 输入目的地经纬度，测试 Valhalla 路线几何能否发送并绘制。

注意：`G,30659500,104065700,90` 是成都测试坐标，测试完请切回真实 GPS，避免地图中心留在测试位置。
