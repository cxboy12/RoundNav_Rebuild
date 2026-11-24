# CarWithin 的 RoundNav 开发副本

本目录来自 CarWithin 上游提交 [3a0b73a](https://github.com/xmz28/CarWithin/tree/3a0b73a2360c459a60047b210a5d3b8aa0151444)，于 2026-11-24 增加 RoundNav 输出。上游许可和第三方声明见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。

1. 在 [GitHub Actions](https://github.com/cxboy12/RoundNav_Rebuild/actions/workflows/roundnav-ios.yml) 运行「Build RoundNav iPhone IPA」，从完成任务的 Artifacts 下载 `CarWithin-RoundNav-unsigned` 并解压。Windows 用 Sideloadly 签名安装其中的 IPA，手机上的名称为「CarWithin RoundNav」。本机 CarPlay 启动所需的配件认证身份按 [SOURCE-BUILD.md](SOURCE-BUILD.md) 在本地配置。也可以在 macOS 用 Xcode 打开 `TDSCarKit.xcodeproj` 构建和签名。
2. iPhone 加入 ESP32 热点，允许应用使用本地网络。在「设置 → 当前输出」选择「RoundNav ESP32」，在「输出设置」填写接收端 IPv4，默认 `192.168.4.1`，TCP 端口 7000。
3. 选择画面边长、帧率上限、JPEG 初始质量、方向及裁切方式。默认 464×464、30 FPS、70、0° 和居中裁切。也可选 448×448 或 360×360。锁屏试验前按上游说明开启「锁定时允许 CarPlay」。
4. 启动本机 CarPlay。应用日志每 5 秒记录源帧率、TCP 写入、ESP32 提交、JPEG 大小、分段耗时以及 ACK 往返。诊断时同时核对接收端和实物画面。

首版源画面请求 960×544 H.264。输出边长取设置值与面板尺寸的较小值，并按接收器声明的对齐值向下取整。微雪 466×466 面板下，默认 464×464 从 `(0,0)` 开始绘制。参见[测试报告](../docs/TEST_REPORT.md)了解验收状态。
