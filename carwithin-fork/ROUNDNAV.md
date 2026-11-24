# CarWithin 的 RoundNav 开发副本

本目录来自 CarWithin 上游提交 [3a0b73a](https://github.com/xmz28/CarWithin/tree/3a0b73a2360c459a60047b210a5d3b8aa0151444)，于 2026-11-24 增加 RoundNav 输出。上游许可和第三方声明见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。

## 用手机上已能启动 CarPlay 的原版转发

1. iPhone 加入微雪板当前的热点。原 DiPlay 固件使用 `DiPlay-Round`，直连固件使用 `RoundNav-XXXX`。在原版 CarWithin 选择「浏览器」，开启局域网接收页，画面固定为 H.264，启动 CarPlay 并让原版进入画中画。
2. 在「CarWithin RoundNav」首页打开「原版视频转发」，也可从设置进入。若原版使用 HTTP 端口 9888，原版地址可以留空。否则填原版显示的完整 HTTP 地址。圆屏地址默认 `192.168.4.1`。
3. 保持默认 464×464、10 FPS，开启「切换 App 后继续转发」再点「开始转发」。确认圆屏出画后再切到另一个 App，原版 CarWithin 继续保持后台运行。RoundNav 转发复用音频后台保持，切回后会尝试恢复连接。原版通过局域网提供 H.264，并负责 CarPlay 认证。
4. 测试息屏前，在原版设置开启「锁屏与后台继续运行」，并在 iPhone「设置 → 通用 → CarPlay」为原版对应的 CarPlay 记录开启「锁定时允许 CarPlay」。

## 在 RoundNav 内启动本机 CarPlay

1. 在 [GitHub Actions](https://github.com/cxboy12/RoundNav_Rebuild/actions/workflows/roundnav-ios.yml) 运行「Build RoundNav iPhone IPA」，从完成任务的 Artifacts 下载 `CarWithin-RoundNav-unsigned` 并解压。Windows 用 Sideloadly 签名安装其中的 IPA，手机上的名称为「CarWithin RoundNav」。本机 CarPlay 启动所需的配件认证身份按 [SOURCE-BUILD.md](SOURCE-BUILD.md) 在本地配置。也可以在 macOS 用 Xcode 打开 `TDSCarKit.xcodeproj` 构建和签名。
2. iPhone 加入 ESP32 热点，允许应用使用本地网络。在「设置 → 当前输出」选择「RoundNav ESP32」，在「输出设置」填写接收端 IPv4，默认 `192.168.4.1`，TCP 端口 7000。
3. 选择画面边长、帧率上限、JPEG 初始质量、方向及裁切方式。默认 464×464、30 FPS、70、0° 和居中裁切。也可选 448×448 或 360×360。锁屏试验前按上游说明开启「锁定时允许 CarPlay」。
4. 启动本机 CarPlay。应用日志每 5 秒记录源帧率、TCP 写入、ESP32 提交、JPEG 大小、分段耗时以及 ACK 往返。诊断时同时核对接收端和实物画面。

首版源画面请求 960×544 H.264。输出边长取设置值与面板尺寸的较小值，并按接收器声明的对齐值向下取整。微雪 466×466 面板下，默认 464×464 从 `(0,0)` 开始绘制。参见[测试报告](../docs/TEST_REPORT.md)了解验收状态。
