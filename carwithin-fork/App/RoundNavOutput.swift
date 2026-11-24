import AVFoundation
import CoreImage
import Foundation
import ImageIO
import Network
import UniformTypeIdentifiers

final class RoundNavOutput {
    struct Settings {
        let host: String
        let fps: Int
        let quality: Int
        let orientation: CGImagePropertyOrientation
        let fit: Bool
    }

    private let settings: Settings
    private let log: (String) -> Void
    private let requestKeyframe: () -> Void
    private let decodeQueue = DispatchQueue(label: "CarWithin.roundnav.decode", qos: .userInitiated)
    private let imageQueue = DispatchQueue(label: "CarWithin.roundnav.jpeg", qos: .userInitiated)
    private let sendQueue = DispatchQueue(label: "CarWithin.roundnav.tcp", qos: .userInitiated)
    private let lock = NSLock()
    private var pending = 0
    private var interrupted = false
    private var decoderFailed = false
    private var closed = false
    private var inputCount = 0
    private var decodeCount = 0
    private var decodeTime = 0.0
    private var latest: CVPixelBuffer?

    // Accessed on the decode queue.
    private var parameters = VideoParameterSets(codec: .h264)
    private var decoder: TailingH264Decoder?
    private var needsIDR = true

    // Accessed on the image queue.
    private lazy var context = CIContext(options: [.useSoftwareRenderer: true, .cacheIntermediates: false])

    // Accessed on the TCP queue.
    private var timer: DispatchSourceTimer?
    private var connection: NWConnection?
    private var endpoint: Geometry?
    private var connectedAt = 0.0
    private var retryAt = 0.0
    private var encoding = false
    private var writing = false
    private var frameID: UInt32 = 0
    private var inFlight: [UInt32: Double] = [:]
    private var sent = 0
    private var bytes = 0
    private var accepted = 0
    private var rejected = 0
    private var rtt = 0.0
    private var espDecode = 0.0
    private var espFrame = 0.0
    private var writeTime = 0.0
    private var imageCount = 0
    private var scaleTime = 0.0
    private var jpegTime = 0.0
    private var reportAt = 0.0
    private var lastJPEGError = -Double.infinity

    init(settings: Settings, log: @escaping (String) -> Void, requestKeyframe: @escaping () -> Void) {
        self.settings = settings
        self.log = log
        self.requestKeyframe = requestKeyframe
    }

    func start() {
        sendQueue.async { [self] in
            guard !isClosed else { return }
            reportAt = Self.now
            let timer = DispatchSource.makeTimerSource(queue: sendQueue)
            timer.schedule(deadline: .now(), repeating: .nanoseconds(1_000_000_000 / settings.fps))
            timer.setEventHandler { [weak self] in self?.tick() }
            self.timer = timer
            timer.resume()
        }
    }

    func stop() {
        lock.lock()
        closed = true
        latest = nil
        lock.unlock()
        sendQueue.async { [self] in
            timer?.cancel()
            timer = nil
            connection?.cancel()
            connection = nil
        }
        decodeQueue.async { [self] in
            decoder?.invalidate()
            decoder = nil
        }
    }

    func append(_ data: Data, codec: VideoCodec, keyframe: Bool) {
        guard codec == .h264 else { return }
        lock.lock()
        guard !closed else { lock.unlock(); return }
        inputCount += 1
        guard pending < 4, !interrupted || keyframe else {
            let firstDrop = !interrupted
            interrupted = true
            lock.unlock()
            if firstDrop { requestKeyframe() }
            return
        }
        let gap = interrupted
        interrupted = false
        pending += 1
        lock.unlock()
        decodeQueue.async { [self] in
            defer {
                lock.lock()
                pending -= 1
                lock.unlock()
            }
            guard !isClosed else { return }
            decode(data, keyframe: keyframe, gap: gap)
        }
    }

    private var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    private func decode(_ data: Data, keyframe: Bool, gap: Bool) {
        lock.lock()
        let failed = decoderFailed
        if keyframe { decoderFailed = false }
        lock.unlock()
        needsIDR = needsIDR || gap || failed
        do {
            var picture: [Data] = []
            for unit in try AnnexB.units(in: data) {
                if VideoCodec.h264.parameterSet(unit) != nil {
                    if parameters.absorb(unit) { needsIDR = true }
                } else {
                    picture.append(unit)
                }
            }
            guard !picture.isEmpty else { return }
            if needsIDR {
                guard keyframe, parameters.isComplete else {
                    if keyframe { requestKeyframe() }
                    return
                }
                decoder?.invalidate()
                decoder = nil
                decoder = try TailingH264Decoder(configuration: parameters.decoderConfigurationRecord(),
                    output: { [weak self] in self?.store($0) },
                    failure: { [weak self] _ in self?.recoverDecoder() })
                needsIDR = false
            }
            guard let decoder else { return }
            let started = Self.now
            try decoder.decode(AnnexB.lengthPrefixed(picture),
                               presentationTimeMicroseconds: Int64(started * 1_000_000), isKeyframe: keyframe)
            decoder.waitForFrames()
            lock.lock()
            decodeCount += 1
            decodeTime += Self.now - started
            lock.unlock()
        } catch {
            recoverDecoder()
        }
    }

    private func recoverDecoder() {
        lock.lock()
        let request = !decoderFailed && !closed
        decoderFailed = true
        lock.unlock()
        if request { requestKeyframe() }
    }

    private func store(_ image: CVPixelBuffer) {
        lock.lock()
        if !closed { latest = image }
        lock.unlock()
    }

    private func tick() {
        guard !isClosed else { return }
        let now = Self.now
        report(at: now)
        guard let connection else {
            if now >= retryAt { connect() }
            return
        }
        if (endpoint == nil && now - connectedAt > 3) || inFlight.values.contains(where: { now - $0 > 3 }) {
            disconnect(connection, reason: "握手或 ACK 超时")
            return
        }
        guard let endpoint, !encoding, !writing, inFlight.count < 2 else { return }
        lock.lock()
        let image = latest
        latest = nil
        lock.unlock()
        guard let image else { return }
        encoding = true
        imageQueue.async { [self] in
            let started = Self.now
            let composed = compose(image, side: endpoint.side)
            let rendered = context.createCGImage(composed, from: composed.extent, format: .RGBA8,
                                                 colorSpace: CGColorSpaceCreateDeviceRGB(), deferred: false)
            let scaled = Self.now
            let jpeg = rendered.flatMap { encode($0, ceiling: endpoint.maximum) }
            let finished = Self.now
            sendQueue.async { [self] in
                encoding = false
                guard self.connection === connection, !isClosed else { return }
                imageCount += 1
                scaleTime += scaled - started
                jpegTime += finished - scaled
                guard let jpeg else {
                    if finished - lastJPEGError >= 5 {
                        lastJPEGError = finished
                        log("RoundNav：JPEG 未通过基线 4:2:0 或帧大小检查")
                    }
                    return
                }
                send(jpeg, to: connection, geometry: endpoint)
            }
        }
    }

    private func compose(_ buffer: CVPixelBuffer, side: Int) -> CIImage {
        let side = CGFloat(side)
        let source = CIImage(cvPixelBuffer: buffer).oriented(settings.orientation).samplingLinear()
        let extent = source.extent
        let widthScale = side / extent.width
        let heightScale = side / extent.height
        let scale = settings.fit ? min(widthScale, heightScale) : max(widthScale, heightScale)
        let bounds = CGRect(x: 0, y: 0, width: side, height: side)
        let positioned = source.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: side / 2 - extent.midX * scale,
                                               y: side / 2 - extent.midY * scale))
        let black = CIImage(color: .black).cropped(to: bounds)
        let mask = CIFilter(name: "CIRadialGradient", parameters: [
            "inputCenter": CIVector(x: side / 2, y: side / 2),
            "inputRadius0": side / 2 - 1,
            "inputRadius1": side / 2,
            "inputColor0": CIColor(red: 1, green: 1, blue: 1),
            "inputColor1": CIColor(red: 0, green: 0, blue: 0)
        ])?.outputImage?.cropped(to: bounds)
        guard let mask else { return positioned.composited(over: black).cropped(to: bounds) }
        return positioned.composited(over: black).cropped(to: bounds).applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: black, kCIInputMaskImageKey: mask
        ]).cropped(to: bounds)
    }

    private func encode(_ image: CGImage, ceiling: Int) -> Data? {
        let preferredSize = min(20 * 1024, ceiling)
        var lastValid: Data?
        for quality in stride(from: settings.quality, through: 20, by: -10) {
            let output = NSMutableData()
            guard let writer = CGImageDestinationCreateWithData(output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else {
                return nil
            }
            CGImageDestinationAddImage(writer, image,
                [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100] as CFDictionary)
            guard CGImageDestinationFinalize(writer) else { continue }
            let data = output as Data
            guard Self.isBaseline420(data) else { continue }
            if data.count <= preferredSize { return data }
            if data.count <= ceiling, lastValid.map({ data.count < $0.count }) ?? true { lastValid = data }
        }
        return lastValid
    }

    private static func isBaseline420(_ jpeg: Data) -> Bool {
        let bytes = [UInt8](jpeg)
        guard bytes.count > 4, bytes[0] == 0xff, bytes[1] == 0xd8 else { return false }
        var index = 2
        while index + 3 < bytes.count, bytes[index] == 0xff {
            let marker = bytes[index + 1]
            let length = Int(bytes[index + 2]) * 256 + Int(bytes[index + 3])
            guard length >= 2, index + 2 + length <= bytes.count, marker != 0xda else { return false }
            if marker == 0xc0 {
                return length == 17 && bytes[index + 4] == 8 && bytes[index + 9] == 3
                    && bytes[index + 11] == 0x22 && bytes[index + 14] == 0x11 && bytes[index + 17] == 0x11
            }
            index += 2 + length
        }
        return false
    }

    private func connect() {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let options = NWParameters(tls: nil, tcp: tcp)
        options.requiredInterfaceType = .wifi
        guard let port = NWEndpoint.Port(rawValue: 7000) else { return }
        let socket = NWConnection(host: NWEndpoint.Host(settings.host), port: port, using: options)
        connection = socket
        connectedAt = Self.now
        socket.stateUpdateHandler = { [weak self, weak socket] state in
            guard let self, let socket, self.connection === socket, !self.isClosed else { return }
            switch state {
            case .ready: self.readHello(socket)
            case .failed(let error): self.disconnect(socket, reason: error.localizedDescription)
            case .cancelled: self.disconnect(socket, reason: "TCP 已关闭")
            default: break
            }
        }
        socket.start(queue: sendQueue)
    }

    private func readHello(_ socket: NWConnection) {
        receive(16, from: socket) { [weak self] bytes in
            guard let self else { return }
            guard let geometry = Geometry(bytes) else {
                self.disconnect(socket, reason: "DiPlay 握手不兼容")
                return
            }
            self.endpoint = geometry
            self.log("RoundNav：DiPlay TCP 已连接，JPEG \(geometry.side)×\(geometry.side)")
            self.readAck(socket)
        }
    }

    private func receive(_ size: Int, from socket: NWConnection, handle: @escaping (Data) -> Void) {
        socket.receive(minimumIncompleteLength: size, maximumLength: size) { [weak self, weak socket] data, _, _, error in
            guard let self, let socket, self.connection === socket, !self.isClosed else { return }
            guard error == nil, let data, data.count == size else {
                self.disconnect(socket, reason: error?.localizedDescription ?? "TCP 数据不完整")
                return
            }
            handle(data)
        }
    }

    private func send(_ jpeg: Data, to socket: NWConnection, geometry: Geometry) {
        frameID &+= 1
        var packet = Data("DPJF".utf8)
        packet.appendLE(frameID)
        packet.appendLE(UInt32(jpeg.count))
        packet.appendLE(geometry.x)
        packet.appendLE(geometry.y)
        packet.append(jpeg)
        let started = Self.now
        inFlight[frameID] = started
        writing = true
        socket.send(content: packet, completion: .contentProcessed { [weak self, weak socket] error in
            guard let self, let socket, self.connection === socket, !self.isClosed else { return }
            self.writing = false
            if let error {
                self.disconnect(socket, reason: error.localizedDescription)
            } else {
                self.sent += 1
                self.bytes += jpeg.count
                self.writeTime += Self.now - started
            }
        })
    }

    private func readAck(_ socket: NWConnection) {
        receive(20, from: socket) { [weak self] bytes in
            guard let self else { return }
            guard bytes.prefix(4).elementsEqual("DPAK".utf8),
                  let started = self.inFlight.removeValue(forKey: bytes.uint32LE(4)) else {
                self.disconnect(socket, reason: "DiPlay ACK 不匹配")
                return
            }
            let status = bytes.uint16LE(8)
            if status == 0 {
                self.accepted += 1
                self.rtt += Self.now - started
                self.espDecode += Double(bytes.uint32LE(12)) / 1_000_000
                self.espFrame += Double(bytes.uint32LE(16)) / 1_000_000
            } else {
                self.rejected += 1
                if self.rejected == 1 { self.log("RoundNav：ESP32 ACK 状态 \(status)") }
            }
            self.readAck(socket)
        }
    }

    private func disconnect(_ socket: NWConnection, reason: String) {
        connection = nil
        endpoint = nil
        writing = false
        frameID = 0
        inFlight.removeAll()
        retryAt = Self.now + 1
        socket.cancel()
        log("RoundNav：\(reason)，将重连")
    }

    private func report(at now: Double) {
        let span = now - reportAt
        guard span >= 5 else { return }
        reportAt = now
        lock.lock()
        let (inputs, decoded, decodeSeconds) = (inputCount, decodeCount, decodeTime)
        inputCount = 0
        decodeCount = 0
        decodeTime = 0
        lock.unlock()
        guard inputs > 0 || sent > 0 || accepted > 0 else { return }
        log(String(format: "RoundNav %.1f 秒：源 %.1f / 发送 %.1f / ESP 提交 %.1f FPS，%.1f KiB/帧，拒绝/跳帧 %d",
                   span, Double(inputs) / span, Double(sent) / span, Double(accepted) / span,
                   Double(bytes) / Double(max(sent, 1)) / 1024, rejected))
        log(String(format: "RoundNav 平均 ms：iOS 解码 %.1f、合成 %.1f、JPEG %.1f、TCP 写入 %.1f，ACK 往返 %.1f、ESP 解码 %.1f、整帧 %.1f",
                   Self.ms(decodeSeconds, decoded), Self.ms(scaleTime, imageCount), Self.ms(jpegTime, imageCount),
                   Self.ms(writeTime, sent), Self.ms(rtt, accepted), Self.ms(espDecode, accepted), Self.ms(espFrame, accepted)))
        (sent, bytes, accepted, rejected, rtt, espDecode, espFrame, writeTime, imageCount, scaleTime, jpegTime) =
            (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }

    private static var now: Double { ProcessInfo.processInfo.systemUptime }
    private static func ms(_ seconds: Double, _ count: Int) -> Double { seconds * 1000 / Double(max(count, 1)) }

    private struct Geometry {
        let x: UInt16
        let y: UInt16
        let side: Int
        let maximum: Int

        init?(_ bytes: Data) {
            guard bytes.prefix(4).elementsEqual("DPHI".utf8), bytes.uint16LE(12) == 1 else { return nil }
            let width = Int(bytes.uint16LE(4))
            let height = Int(bytes.uint16LE(6))
            let alignment = Int(bytes.uint16LE(14))
            maximum = Int(bytes.uint32LE(8))
            guard alignment == 8 || alignment == 16, maximum > 0 else { return nil }
            side = min(width, height, 360) / alignment * alignment
            guard side >= alignment else { return nil }
            x = UInt16(((width - side) / 2) & ~1)
            y = UInt16(((height - side) / 2) & ~1)
        }
    }
}
