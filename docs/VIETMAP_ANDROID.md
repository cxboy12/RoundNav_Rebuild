# RoundNav Android 越南版

手机使用 Vietmap Android SDK 加载标准街道矢量图。摩托车路线由 Vietmap Route v4 返回，Android 解析 polyline5 和转向索引，再以原 BLE 协议发送到圆屏。

## 本机验证

在 `Android/local.properties` 中配置 `VIETMAP_TILEMAP_KEY` 和 `VIETMAP_SERVICES_KEY`。此文件已被 Git 忽略。Tilemap key 用于手机地图，可从 APK 中读取，应在 Vietmap 控制台限制用途和额度。Services key 只由本地代理读取。

在项目根目录启动 `python -B tools/vietmap_local.py`，再用 `adb reverse tcp:8765 tcp:8765` 把 USB 测试手机连到本机代理。构建并安装 `Android/gradlew.bat :app:assembleDebug` 的 APK。调试版路线地址默认指向 `http://127.0.0.1:8765/route`，代理只监听本机。打开路线预览，点「从胡志明市测试点规划」。默认测试起点为 `10.77689,106.70089`，终点为 `10.77978,106.69902`。

手机直连 Vietmap 底图超时时，可用 `:app:assembleDebug -ProundnavVietmapUsbProxy=true` 构建本机测试版。底图请求同样经 USB 送到本机代理，代理仅将 HTTPS 通道转给 `maps.vietmap.vn`，TLS 由手机和 Vietmap 端处理。发行构建关闭该选项。

## 发布配置

`VIETMAP_ROUTE_URL` 是 RoundNav 的 HTTPS 路线代理地址，接收两个 `point=纬度,经度` 参数，返回 Vietmap Route v4 的 polyline5 响应。部署时把 Services key 配在服务端，并给代理接入产品的鉴权和额度控制。

路线代理向 Vietmap 请求 `annotations=congestion,congestion_distance`。Android 根据实际返回的 `annotations.congestion` 起止点索引标记路线分段。绿、橙、红分别表示畅通、缓行和拥堵，蓝色表示没有对应数据。发布用代理也需要请求并透传这部分响应。手机显示按路线形状计算的分段距离，路况随重新规划获取。

Android 默认从官方 SDK 已加载的视野读取 `road` 和 `building` 图层。路线模拟和前台定位跟随会在当前位置接近补图范围边缘时提前移动手机视野，并保持建筑所需的缩放级。圆屏在新瓦片准备好之前继续使用上一批底图。默认模式需要手机地图保持前台。`VIETMAP_ROADS_URL` 可配置授权的 Vietmap 周边道路服务，接收 `lat`、`lon` 和 `radius`（米），返回包含 `roads` 或 `features` 的 JSON，道路线坐标为经纬度顺序。使用该接口可独立于手机地图视野补图。Vietmap 返回的测试瓦片不能由普通 MVT 解码器读取，SDK 负责处理原始瓦片。

手机在较近缩放级显示 Vietmap 建筑平面。行驶约 45 米补图，最短间隔 1.5 秒；沿线模拟时手机视野提前移到前方。每批最多 64 段街道和 96 栋建筑，只取 210 米内的几何，移动时优先前方约 65 米附近。街道每段最多 24 点，建筑最多 8 点。圆屏使用紧凑命令：`RL,id,class,x0,y0,dx1,dy1,...` 为街道，`RQ,id,x0,y0,...` 为建筑，坐标是相对 `RC` 的整米东/北偏移，第二个点起相对前一点。道路等级 0–4 同原协议，6 为小巷和服务道路。旧 `RB/RP/RE` 命令仍被固件接受。BLE 允许时每次写入最多 240 字节。

`P,index,lat,lon[,traffic[,turn]]` 的可选字段分别是从该点出发的路况和该点转向。路况数字 `0` 至 `3` 表示未知、畅通、缓行、拥堵，转向为 `L`、`R`、`U`。`G,lat,lon,bearing[,progress_dm]` 的可选字段表示已走路线长度，单位分米。模拟直接产生路线进度，实时 GPS 匹配到路线附近后给出进度。旧固件仍读取原有字段，手机继续发送旧格式转向提示。新版圆屏沿已提交路线绘制位置和转向提示，经过部分恢复蓝色，前方保留路况色。低内存矢量回退使用建筑描边和统一路线颜色。

目前可识别 Route v4 的普通、轻微及急转左右方向。当返回的终点指令索引与路线点数不符，且指令累计长度与路线几何长度吻合时，按累计里程把转向定位到实际折线。分岔、环岛和掉头的代码需要用 Vietmap 对应样例复核后接入圆屏提示。真实 GPS 路线跟踪仍按项目计划验收。

当前圆屏未显示路口信号灯。已核对当前 Tilemap 样式和 Route v4 注解，现有接口未确认有可用于绘制红绿灯的坐标。Vietmap 导航 SDK 另有信号灯提醒，坐标输出方式和授权待确认。

[Vietmap 开通与密钥说明](https://maps.vietmap.vn/docs/map-api/quickstart/) · [Route v4](https://maps.vietmap.vn/docs/map-api/route-version/route-v4/) · [Tilemap](https://maps.vietmap.vn/docs/map-api/tilemap/)
