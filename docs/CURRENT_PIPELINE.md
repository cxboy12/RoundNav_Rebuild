# 电脑中转源码审计

源码在 `D:/workspace/Diplay_project`。其中 `pc_sender/sender.py` 和 `start_carwithin.bat` 是发送入口，`esp32_receiver/main` 是接收入口。

- 批处理用 `--source window --open` 启动 Chrome 应用模式，Chrome 打开 iPhone 的 CarWithin 局域网接收页。网页经 WebSocket 接收，WebCodecs 分支用 AVCC/HVCC 样本，MSE 兼容分支用 fMP4。两组历史测试的分支和 CarWithin 版本未能确认。
- 默认窗口路径用 ctypes 调用 Win32 `PrintWindow(hwnd, memdc, 3)`，用 `GetDIBits` 取 32 位自顶向下的 DIB，Pillow 按 BGRX 转 RGB。代码处理最小化并统计黑帧。可选的 `mss` 路径按桌面物理像素抓区域，读取 BGRA 缓冲并按 BGRX 转 RGB。
- `Preparer` 可先应用 ROI。默认居中裁成正方形、双线性缩放并将圆外填黑，也支持等比留黑边和拉伸。缺省尺寸由握手计算，466 像素屏得到 464。Pillow 明确使用 JPEG、`subsampling=2`（4:2:0）、`progressive=False`、`optimize=False`，质量缺省 70，超出帧上限时每次降 10 并重编码。发送上限 30 FPS。
- ESP32 通过 `dp_wifi.c` 按 STA 凭据是否配置选择 STA 或 AP。TCP 连接和发现协议见 [VIDEO_PROTOCOL.md](VIDEO_PROTOCOL.md)。
- `dp_frame_server.c` 使用 `esp_new_jpeg` 分块解码。JPEG 入站使用三块 PSRAM 槽，每块 256 KiB。解码目标为内部 RAM RGB565，配置默认大端，每块最多 16 行。接收与显示分别运行，解码任务排空就绪队列并选择最新完整帧。
- `dp_display.c` 用 Waveshare AMOLED BSP 无 LVGL 接口和 CO5300 驱动刷 466×466 QSPI 面板。解码块复制到两块交替使用的内部 RAM DMA 缓冲，异步提交，颜色传输完成回调释放缓冲信号量。现有项目的 `ESP32/` 也是 466×466 版本，入口属于 BLE 导航。
- 按现有分块计算，三块 JPEG 槽共 768 KiB PSRAM，一个 RGB565 解码块和两个 DMA 块共 44,736 字节内部 RAM，另需任务栈、网络缓冲、驱动和解码器工作区。360×360 使用 16 行时三个 RGB565 块共 34,560 字节，实际峰值待板上测量。
