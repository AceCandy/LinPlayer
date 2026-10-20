import Foundation

final class NativeCore: CoreTransport {
    static func initialize(dataRoot: URL, version: String) throws -> CoreClient {
        guard lp_abi_version() == LinPlayerAbi.version else { throw CoreFailure(code: "E_ABI") }
        let host: [String: String] = ["dataDir": dataRoot.path, "platform": "tvos", "version": version]
        let text = String(decoding: try JSONEncoder().encode(host), as: UTF8.self)
        let rc = text.withCString { lp_init(UnsafeMutablePointer(mutating: $0)) }
        guard rc == 0 else { throw CoreFailure(code: "E_STORAGE") }
        return CoreClient(transport: NativeCore())
    }

    func send(seq: Int64, command: String, args: Data) -> Int32 {
        command.withCString { cmd in
            String(decoding: args, as: UTF8.self).withCString { json in
                lp_call(seq, UnsafeMutablePointer(mutating: cmd), UnsafeMutablePointer(mutating: json))
            }
        }
    }
    func cancel(seq: Int64) { lp_cancel(seq) }
    func next() -> Data? {
        guard let p = lp_next_event(250) else { return nil }
        defer { lp_free(p) }
        return Data(bytes: p, count: strlen(p))
    }
    func shutdown() { lp_shutdown() }
}
