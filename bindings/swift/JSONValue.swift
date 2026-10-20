import Foundation

// 保留任意 JSON 返回形状，不能将数组、标量或 null 强转成对象。
enum JSONValue: Codable, Equatable, Sendable {
    case object([String: JSONValue]), array([JSONValue]), string(String), number(Double), bool(Bool), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    subscript(_ key: String) -> JSONValue { if case .object(let v) = self { return v[key] ?? .null }; return .null }
    var string: String? { if case .string(let v) = self { return v }; return nil }
    var number: Double? { if case .number(let v) = self { return v }; return nil }
    var bool: Bool? { if case .bool(let v) = self { return v }; return nil }
    var array: [JSONValue] { if case .array(let v) = self { return v }; return [] }

    func decode<T: Decodable>(_ type: T.Type) throws -> T {
        do { return try JSONDecoder().decode(type, from: JSONEncoder().encode(self)) }
        catch { throw CoreFailure(code: "E_PROTOCOL") }
    }
}
