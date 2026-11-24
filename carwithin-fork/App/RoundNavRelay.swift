import Foundation
import Network

/// Receives the original CarWithin's framed H.264 samples and shares the RoundNav JPEG output.
final class RoundNavRelay {
    private let source: URL
    private let settings: RoundNavOutput.Settings
    private let log: (String) -> Void
    private let queue = DispatchQueue(label: "CarWithin.roundnav.relay", qos: .userInitiated)
    private var connection: NWConnection?
    private var output: RoundNavOutput?
    private var configuration: TailingAVCConfigurationRecord?
    private var timer: DispatchSourceTimer?
    private var lastVideo = 0.0
    private var retryAt = 0.0
    private var reportedVideo = false
    private var stopped = false

    init(source: URL, settings: RoundNavOutput.Settings, log: @escaping (String) -> Void) {
        self.source = source
        self.settings = settings
        self.log = log
    }

    func start() {
        queue.async { [self] in
            guard !stopped, timer == nil else { return }
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now(), repeating: .seconds(1))
            timer.setEventHandler { [weak self] in self?.tick() }
            self.timer = timer
            timer.resume()
        }
    }

    func stop() {
        queue.async { [self] in
            stopped = true
            timer?.cancel()
            timer = nil
            connection?.cancel()
            connection = nil
            output?.stop()
            output = nil
        }
    }

    private func tick() {
        let now = ProcessInfo.processInfo.systemUptime
        if let connection {
            if now - lastVideo > 8 {
                reconnect(connection, reason: "等待原版视频超时")
            }
        } else if now >= retryAt {
            connect(now: now)
        }
    }

    private func connect(now: Double) {
        let options = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        webSocket.maximumMessageSize = 8 * 1024 * 1024
        options.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        let socket = NWConnection(to: .url(source), using: options)
        connection = socket
        lastVideo = now
        log("正在连接原版 CarWithin")
        socket.stateUpdateHandler = { [weak self, weak socket] state in
            guard let self, let socket, self.connection === socket else { return }
            switch state {
            case .ready:
                self.send(#"{"type":"ready","decoder":"webcodecs"}"#, to: socket)
                self.receive(socket)
            case .failed(let error):
                self.reconnect(socket, reason: error.localizedDescription)
            case .cancelled:
                self.reconnect(socket, reason: "原版视频连接已关闭")
            default:
                break
            }
        }
        socket.start(queue: queue)
    }

    private func receive(_ socket: NWConnection) {
        socket.receiveMessage { [weak self, weak socket] data, context, _, error in
            guard let self, let socket, self.connection === socket else { return }
            if let error {
                self.reconnect(socket, reason: error.localizedDescription)
                return
            }
            let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            if context == nil || metadata?.opcode == .close {
                self.reconnect(socket, reason: "原版已停止共享")
                return
            }
            do {
                if let data, metadata?.opcode == .text {
                    try self.handleFormat(data, socket: socket)
                } else if let data, metadata?.opcode == .binary {
                    try self.handleVideo(data)
                }
            } catch {
                self.reconnect(socket, reason: error.localizedDescription)
                return
            }
            self.receive(socket)
        }
    }

    private func handleFormat(_ data: Data, socket: NWConnection) throws {
        guard data.count <= 65_536,
              let message = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              message["type"] as? String == "format" else { return }
        let codec = message["codec"] as? String ?? ""
        guard codec.hasPrefix("avc1") || codec.hasPrefix("avc3"),
              let description = message["description"] as? String,
              let record = Data(base64Encoded: description) else {
            throw ConfigurationError("请在原版 CarWithin 的画面设置选择 H.264，再重新启动 CarPlay")
        }
        configuration = try TailingAVCConfigurationRecord(data: record)
        output?.stop()
        let output = RoundNavOutput(settings: settings, log: log) { [weak self, weak socket] in
            guard let self, let socket else { return }
            self.queue.async {
                guard self.connection === socket else { return }
                self.send(#"{"type":"keyframe","reason":"RoundNav decode recovery"}"#, to: socket)
            }
        }
        self.output = output
        output.start()
        reportedVideo = false
    }

    private func handleVideo(_ data: Data) throws {
        guard let output, let configuration, let type = data.first,
              type == 0 || type == 1, data.count > 9 else { return }
        let keyframe = type == 1
        let bytes = [UInt8](data)
        let lengthSize = configuration.nalUnitHeaderLength
        var offset = 9
        let startCode: [UInt8] = [0, 0, 0, 1]
        var annexB = Data()
        if keyframe {
            for parameter in configuration.parameterSets {
                annexB.append(contentsOf: startCode)
                annexB.append(parameter)
            }
        }
        while offset < bytes.count {
            guard bytes.count - offset >= lengthSize else { throw ConfigurationError("H.264 长度字段不完整") }
            var length = 0
            for byte in bytes[offset..<offset + lengthSize] {
                length = (length << 8) | Int(byte)
            }
            offset += lengthSize
            guard length > 0, length <= bytes.count - offset else { throw ConfigurationError("H.264 视频帧不完整") }
            annexB.append(contentsOf: startCode)
            annexB.append(contentsOf: bytes[offset..<offset + length])
            offset += length
        }
        lastVideo = ProcessInfo.processInfo.systemUptime
        output.append(annexB, codec: .h264, keyframe: keyframe)
        if !reportedVideo {
            reportedVideo = true
            log("已收到原版 H.264 视频，正在转发圆屏")
        }
    }

    private func send(_ text: String, to socket: NWConnection) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "RoundNav Relay", metadata: [metadata])
        socket.send(content: Data(text.utf8), contentContext: context, isComplete: true,
                    completion: .contentProcessed { [weak self, weak socket] error in
            guard let self, let socket, self.connection === socket, let error else { return }
            self.reconnect(socket, reason: error.localizedDescription)
        })
    }

    private func reconnect(_ socket: NWConnection, reason: String) {
        connection = nil
        socket.cancel()
        output?.stop()
        output = nil
        configuration = nil
        retryAt = ProcessInfo.processInfo.systemUptime + 2
        log("转发：\(reason)，正在等待重连")
    }
}
