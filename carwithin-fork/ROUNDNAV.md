# CarWithin 的 RoundNav 开发副本

本目录来自 CarWithin 上游提交 [3a0b73a](https://github.com/xmz28/CarWithin/tree/3a0b73a2360c459a60047b210a5d3b8aa0151444)，于 2026-11-24 增加 RoundNav 输出。上游许可和第三方声明见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。

1. 在 macOS 用 Xcode 打开 `TDSCarKit.xcodeproj`，按 [SOURCE-BUILD.md](SOURCE-BUILD.md) 配置签名、安装及 CarPlay 本机启动所需身份。
2. iPhone 加入 ESP32 热点，允许应用使用本地网络。在「设置 → 当前输出」选择「RoundNav ESP32」，在「输出设置」填写接收端 IPv4，默认 `192.168.4.1`，TCP 端口 7000。
3. 选择帧率上限、JPEG 初始质量、方向及裁切方式。默认 30 FPS、70、0° 和居中裁切。锁屏试验前按上游说明开启「锁定时允许 CarPlay」。
4. 启动本机 CarPlay。应用日志每 5 秒记录源帧率、TCP 写入、ESP32 提交、JPEG 大小、分段耗时以及 ACK 往返。诊断时同时核对接收端和实物画面。

首版源画面请求 960×544 H.264，输出边长按接收器的对齐和尺寸限制计算，上限 360。原 466×466、16 像素对齐接收器会收到 352×352，独立直连固件宣告 466×466、8 像素对齐，接收 360×360。参见[测试报告](../docs/TEST_REPORT.md)了解验收状态。
