# RoundNav Rebuild

现有 Android / BLE 导航项目见 [README_中文.md](README_中文.md) 和 [项目交接](PROJECT_STATUS.md)。

## RoundNav Direct

[CarWithin 开发副本](carwithin-fork/ROUNDNAV.md) 增加了「RoundNav ESP32」输出，读取 App 内部视频帧，通过 DiPlay JPEG/TCP v1 向 ESP32 推送。目标为 iPhone 经 ESP32 热点直连 360×360 ST77916 圆屏。

- [电脑中转源码审计](docs/CURRENT_PIPELINE.md)和[性能基准](docs/BASELINE.md)
- [CarWithin 原生输出审计](docs/CARWITHIN_ANALYSIS.md)和[实施方案](docs/DIRECT_STREAM_ARCHITECTURE.md)
- [DiPlay 视频协议](docs/VIDEO_PROTOCOL.md)和[验证记录](docs/TEST_REPORT.md)
