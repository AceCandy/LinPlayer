import Foundation

protocol CoreTransport: AnyObject {
    func send(seq: Int64, command: String, args: Data) -> Int32
    func cancel(seq: Int64)
    // 返回已复制、已释放原生指针的数据。nil 只表示本次超时。
    func next() -> Data?
    func shutdown()
}

struct CoreFailure: Error, LocalizedError, Equatable {
    let code: String
    var retryable = false
    var errorDescription: String? {
        switch code {
        case "E_AUTH": return "登录失败或已失效，请检查账号、密码后重新连接。"
        case "E_INVALID": return "输入信息有误，请检查后重试。"
        case "E_NETWORK": return "网络连接失败，请检查网络后重试。"
        case "E_NOTFOUND": return "内容已不存在，请返回刷新。"
        case "E_PERMISSION": return "当前账号没有访问权限。"
        case "E_UNSUPPORTED": return "当前内容或服务器暂不支持此操作。"
        case "E_SHUTDOWN": return "核心已关闭，请重新启动应用。"
        case "E_BUSY": return "请求较多，请稍后重试。"
        default: return "操作失败，请重试。"
        }
    }
}

// 发送/分派与阻塞读取分队列；唯一读者持续消费所有 seq，而不是由每次调用抢事件。
enum CoreDisplayUpdate: Sendable { case snapshot(JSONValue), result(JSONValue) }

protocol DisplayCommands: LinPlayerCommands {
    func display(_ command: String, _ args: [String: JSONValue]) -> AsyncThrowingStream<CoreDisplayUpdate, Error>
}

final class CoreClient: DisplayCommands, @unchecked Sendable {
    // 字段只在 state 队列访问。
    private final class Ticket: @unchecked Sendable {
        var continuation: CheckedContinuation<JSONValue, Error>?
        var cancelled = false
        var timeout: DispatchWorkItem?
        var partial: (@Sendable (JSONValue) -> Void)?
    }
    private let transport: CoreTransport
    private let state = DispatchQueue(label: "linplayer.core.dispatch")
    private let pump = DispatchQueue(label: "linplayer.core.events")
    private var sequence: Int64 = 0
    private var pending: [Int64: Ticket] = [:]
    private var closed = false
    private var stopping = false
    private var started = false
    var onEvent: (@Sendable (String, JSONValue) -> Void)?
    var onPartial: (@Sendable (Int64, JSONValue) -> Void)?

    init(transport: CoreTransport) {
        self.transport = transport
    }

    func start() {
        let shouldStart = state.sync { () -> Bool in
            if started { return false }; started = true; return true
        }
        guard shouldStart else { return }
        pump.async { [self] in
            while !state.sync(execute: { closed }) {
                guard let data = transport.next() else { continue }
                do {
                    let envelope = try JSONDecoder().decode(JSONValue.self, from: data)
                    state.async { self.receive(envelope) }
                } catch {
                    state.async { self.finishAll(CoreFailure(code: "E_PROTOCOL")) }
                }
            }
        }
    }

    func call(_ command: String, _ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await call(command, args, partial: nil)
    }

    func display(_ command: String, _ args: [String: JSONValue]) -> AsyncThrowingStream<CoreDisplayUpdate, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let value = try await call(command, args, partial: { continuation.yield(.snapshot($0)) })
                    continuation.yield(.result(value)); continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func call(_ command: String, _ args: [String: JSONValue], partial: (@Sendable (JSONValue) -> Void)?) async throws -> JSONValue {
        let data = try JSONEncoder().encode(JSONValue.object(args))
        let (seq, ticket): (Int64, Ticket) = state.sync {
            sequence += 1
            let t = Ticket(); t.partial = partial; pending[sequence] = t
            return (sequence, t)
        }
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                state.async { [self] in
                    if ticket.cancelled || self.closed || self.stopping {
                        self.pending.removeValue(forKey: seq)
                        continuation.resume(throwing: ticket.cancelled ? CancellationError() : CoreFailure(code: "E_SHUTDOWN"))
                        return
                    }
                    ticket.continuation = continuation
                    let rc = self.transport.send(seq: seq, command: command, args: data)
                    if rc != 0 {
                        self.finish(seq, .failure(CoreFailure(code: rc == -4 ? "E_BUSY" : "E_INTERNAL")))
                        return
                    }
                    let timeout = DispatchWorkItem { [weak self] in
                        guard let self, self.pending[seq] != nil else { return }
                        self.transport.cancel(seq: seq)
                        self.finish(seq, .failure(CoreFailure(code: "E_NETWORK")))
                    }
                    ticket.timeout = timeout
                    self.state.asyncAfter(deadline: .now() + 30, execute: timeout)
                }
            }
        }, onCancel: {
            self.state.async {
                ticket.cancelled = true
                guard self.pending[seq] != nil else { return }
                self.transport.cancel(seq: seq)
                if ticket.continuation != nil { self.finish(seq, .failure(CancellationError())) }
            }
        })
    }

    private func receive(_ e: JSONValue) {
        let seq = Int64(e["seq"].number ?? 0)
        switch e["t"].string {
        case "result":
            if e["ok"].bool == true { finish(seq, .success(e["data"])) }
            else { finish(seq, .failure(CoreFailure(code: e["err"]["code"].string ?? "E_INTERNAL", retryable: e["err"]["retryable"].bool == true))) }
        case "partial": if let ticket = pending[seq], !ticket.cancelled { ticket.partial?(e["data"]); onPartial?(seq, e["data"]) }
        case "event": onEvent?(e["name"].string ?? "", e["data"])
        case "eof": finishAll(CoreFailure(code: "E_SHUTDOWN"))
        default: break
        }
    }

    private func finish(_ seq: Int64, _ result: Result<JSONValue, Error>) {
        guard let t = pending.removeValue(forKey: seq) else { return }
        t.timeout?.cancel()
        t.continuation?.resume(with: result)
    }

    private func finishAll(_ error: Error) {
        closed = true
        for seq in Array(pending.keys) { finish(seq, .failure(error)) }
    }

    // 调用者先结束引擎和会话。关停仍让泵消费直到 EOF，不阻塞发送或主线程。
    func shutdown() async {
        let shouldStop = state.sync { () -> Bool in
            if stopping { return false }
            stopping = true
            for seq in Array(pending.keys) { transport.cancel(seq: seq); finish(seq, .failure(CoreFailure(code: "E_SHUTDOWN"))) }
            return true
        }
        if shouldStop { await Task.detached { self.transport.shutdown() }.value }
    }
}
