# CarWithin 原生输出审计

审计上游提交 [3a0b73a](https://github.com/xmz28/CarWithin/tree/3a0b73a2360c459a60047b210a5d3b8aa0151444)。仓库包含 AGPL-3.0 许可、第三方许可及通知，开发副本保留这些文件。

- `CarPlayMediaSession` 将视频 channel 的帧取出并按协商 codec 通知 `onVideo`。`NativeReceiver` 的 `media.onVideo` 回调收到压缩 access unit，识别随机访问 NAL 并向外部输出分发，随后才跳转到主线程供 `ReceiverViewController` 更新本机画面。本机回调会因后台状态暂停，当前源码没有共享的解码后像素回调。RoundNav 应在 NativeReceiver 分发点接入。
- `ReceiverConfiguration` 缺省 H.264。浏览器可协商 H.265，`VideoOutputServer` 按 codec 收集参数集，切流或积压时等待随机访问帧。名为 `/video.h264` 的诊断端点实际输出当前 codec 的 Annex B。浏览器 WebSocket 走 AVCC/HVCC 或 fMP4。
- `TailingOutput` 在独立解码队列提取 SPS/PPS，等到 IDR 后构造 avcC，调用 `TailingH264Decoder` 的 VideoToolbox 会话获取 BGRA CVPixelBuffer。积压达到上限或解码异常会丢弃依赖帧并请求关键帧。VideoToolbox 解码器可直接复用。JPEG 队列复用 CIContext，以 Core Image 合成 480×272，`TailingJPEGEncoder` 提供 24 KiB 限幅，发送端最高 20 FPS。`TailingConnection` 等仪表拉帧后经 TCP 写入 PXC 负载，RoundNav 采用 DiPlay TCP 头和主动推送。
- `NinebotOutput` 在专用队列解码、等比合成和限速，H.264 模式通过 VideoToolbox 再编码。5 英寸模式为 848×480、30 FPS UDP，7 英寸为 1024×600、25 FPS KCP/UDP。拥塞后依靠关键帧恢复。4.3 英寸模式使用 480×272、最高 10 FPS JPEG/RTP。它的包格式、控制通道、关键帧调度和 DiPlay 不同。
