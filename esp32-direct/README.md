# RoundNav Direct 接收固件

硬件为微雪 ESP32-S3-Touch-AMOLED-1.75，CO5300 466×466，16 MB Flash、8 MB PSRAM。固件来自电脑中转接收端，使用 DiPlay TCP v1、基线 JPEG 分块解码和双 DMA 缓冲。 [微雪开发板资料](https://docs.waveshare.com/ESP32-S3-Touch-AMOLED-1.75)

在本目录使用 ESP-IDF 5.5.6 执行 `idf.py build`，生成 `build/roundnav_direct.bin`。独立固件用于直连测试板。当前项目与基准固件都使用单个 factory 分区，更新同一块板会替换其原有应用。

默认热点名为 `RoundNav-XXXX`，后四位取 AP MAC 地址末尾，接收地址为 `192.168.4.1:7000`。在 `idf.py menuconfig` 的「RoundNav Direct 圆屏接收端」可配置热点密码、信道和开机诊断图。本地 `sdkconfig` 不入库。iPhone 端参见 [RoundNav 输出设置](../carwithin-fork/ROUNDNAV.md)。

启动后清屏为黑色。正常推流在 `(52,52)` 显示 360×360 JPEG，面板要求 x/y 偶数。串口分开报告完整接收和显示提交，刷屏提交耗时包含等待、复制和 DMA 提交。实测记录见 [TEST_REPORT.md](../docs/TEST_REPORT.md)。
