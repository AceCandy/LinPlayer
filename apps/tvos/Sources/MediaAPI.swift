import Foundation

struct MediaItem: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    let type_: String
    let is_folder: Bool
    let has_primary: Bool
    let runtime_secs: Double
    let resume_secs: Double
    let series_name: String?
    let episode_no: Int?
    let year: Int?
    let season_no: Int?
    let series_id: String?
    let season_id: String?
    let has_backdrop: Bool?
    let played: Bool?
    let is_favorite: Bool?
    var rating: Double? = nil
    var DoubanRating: Double? = nil
    var unplayed_item_count: Int? = nil
    var unplayed_count_known: Bool? = nil
    var library_ids: [String]? = nil
    var has_logo: Bool? = nil
    var genres: [String]? = nil
    var title: String { series_name.map { "\($0) · \(name)" } ?? name }
    static func route(id: String, name: String, type: String) -> MediaItem {
        MediaItem(id: id, name: name, type_: type, is_folder: false, has_primary: false, runtime_secs: 0, resume_secs: 0,
            series_name: nil, episode_no: nil, year: nil, season_no: nil, series_id: nil, season_id: nil,
            has_backdrop: nil, played: nil, is_favorite: nil)
    }
    var episodeStatus: Int? {
        if (type_ == "Series" || type_ == "Season"), unplayed_count_known == true, let count = unplayed_item_count {
            if count > 0 { return count }
            if count == 0 && played == true { return 0 }
        }
        return ["Movie", "Episode", "Season"].contains(type_) && played == true ? 0 : nil
    }
    func ratingText(douban: Bool = false) -> String? {
        guard let value = douban ? DoubanRating : rating,
              value.isFinite, (0...10).contains(value), douban || value > 0 else { return nil }
        return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
    var resumeTimeText: String? {
        guard ["Movie", "Episode"].contains(type_), runtime_secs.isFinite, runtime_secs > 0 else { return nil }
        let remaining = played != true && resume_secs > 0 && resume_secs < runtime_secs
        let seconds = remaining ? runtime_secs - resume_secs : runtime_secs
        guard seconds < Double(Int.max) else { return nil }
        let total = max(1, Int(seconds))
        let duration = total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
        return remaining ? "剩余 " + duration : duration
    }
    var opensFolder: Bool { is_folder && !["Series", "Season"].contains(type_) }
}

struct ArtworkSource: Equatable {
    let id: String
    let kind: String
}

extension MediaItem {
    var artworkSources: [ArtworkSource] {
        var result: [ArtworkSource] = has_primary ? [ArtworkSource(id: id, kind: "Primary")] : []
        if type_ == "Episode" {
            if has_backdrop == true { result.append(ArtworkSource(id: id, kind: "Backdrop/0")) }
            if let season_id, !season_id.isEmpty { result.append(ArtworkSource(id: season_id, kind: "Primary")) }
            if let series_id, !series_id.isEmpty {
                result.append(ArtworkSource(id: series_id, kind: "Backdrop/0"))
                result.append(ArtworkSource(id: series_id, kind: "Primary"))
            }
        } else if type_ == "Season", let series_id, !series_id.isEmpty {
            result.append(ArtworkSource(id: series_id, kind: "Primary"))
        }
        return result
    }
}

struct MediaPage: Decodable {
    let items: [MediaItem]
    let total: Int
}

struct PersonWorksPage: Decodable {
    let items: [MediaItem]
    let total: Int?
    let next_index: Int
    let has_more: Bool
}

struct PersonWorksState {
    var items: [MediaItem] = []
    var cursor = 0
    var more = true
    var total: Int?
    mutating func append(_ page: PersonWorksPage) throws {
        // 服务端游标包含被屏蔽的作品，不能用可见卡片数推进。
        guard !page.has_more || page.next_index > cursor else { throw CoreFailure(code: "E_PROTOCOL") }
        var seen = Set(items.map(\.id))
        items += page.items.filter { seen.insert($0.id).inserted }
        cursor = page.next_index; more = page.has_more; total = page.total
    }
}

struct MediaDetail: Decodable {
    let id: String
    let name: String
    let type_: String
    let overview: String
    let year: Int?
    let genres: [String]?
    let is_favorite: Bool
    let played: Bool
    let series_id: String?
    let season_id: String?
    let season_no: Int?
    let episode_no: Int?
    let people: [MediaPerson]?
    let tags: [String]?
    let runtime_secs: Double
    let resume_secs: Double
    let has_primary: Bool
    var has_backdrop: Bool? = nil
    var rating: Double? = nil
    var DoubanRating: Double? = nil
    var premiere_date: String? = nil
    var official_rating: String? = nil
    var status: String? = nil
    var tagline: String? = nil
    var studios: [MediaNamed]? = nil
    var capabilities: [String: Bool]? = nil
}

struct MediaNamed: Decodable, Identifiable {
    let id: String
    let name: String
}

struct MediaPerson: Decodable, Identifiable {
    let id: String
    let name: String
    let role: String?
    let type_: String
    let has_primary: Bool
}

struct BrowseQuery: Equatable {
    var sort = "SortName"
    var descending = false
    var genre = ""
    var tag = ""
    var studioID = ""
    var year = ""
    var played = ""
    var ratingMin = 0

    func arguments(start: Int) -> [String: JSONValue] {
        var args: [String: JSONValue] = ["start_index": .number(Double(start)), "limit": .number(40),
            "sort_by": .string(sort), "sort_order": .string(descending ? "Descending" : "Ascending")]
        if !genre.isEmpty { args["genres"] = .array([.string(genre)]) }
        if !tag.isEmpty { args["tags"] = .array([.string(tag)]) }
        if !studioID.isEmpty { args["studio_ids"] = .array([.string(studioID)]) }
        if let value = Int(year) { args["years"] = .array([.number(Double(value))]) }
        if !played.isEmpty { args["played"] = .bool(played == "true") }
        if ratingMin > 0 { args["rating_min"] = .number(Double(ratingMin)) }
        return args
    }
}

struct MediaSeason: Decodable, Identifiable {
    let id: String
    let name: String
    var index_no: Int? = nil
    var child_count: Int? = nil
    var unplayed: Int? = nil
    var unplayed_count_known: Bool? = nil
    var played: Bool? = nil
    var has_primary: Bool? = nil
    func calibrated(_ page: MediaPage) -> MediaSeason? {
        guard page.total > 0, page.items.count == page.total,
              Set(page.items.map(\.id)).count == page.total,
              page.items.allSatisfy({ !$0.id.isEmpty && $0.played != nil && $0.season_id == id }) else { return nil }
        var result = self
        result.child_count = page.total
        result.unplayed = page.items.filter { $0.played != true }.count
        result.unplayed_count_known = true
        result.played = result.unplayed == 0
        return result
    }
    func artwork(series: String) -> MediaItem {
        MediaItem(id: id, name: name, type_: "Season", is_folder: true, has_primary: has_primary == true,
            runtime_secs: 0, resume_secs: 0, series_name: nil, episode_no: nil, year: nil,
            season_no: index_no, series_id: series, season_id: nil, has_backdrop: nil, played: played, is_favorite: nil)
    }
    var statusText: String? {
        if unplayed_count_known == true, let unplayed, unplayed > 0 { return "\(unplayed) 集未看" }
        if played == true || (unplayed_count_known == true && unplayed == 0 && (child_count ?? 0) > 0) { return "已看完" }
        return child_count.map { "共 \($0) 集" }
    }
}

// 展示快照不含可靠的收藏/续播状态，与可操作的实时详情分开解码。
struct DetailPresentation: Decodable {
    let id: String
    let name: String
    let overview: String
    let year: Int?
    let genres: [String]?
    let rating: Double?
    let DoubanRating: Double?
    let premiere_date: String?
    let official_rating: String?
    let status: String?
    let tagline: String?
    let has_primary: Bool
    let has_backdrop: Bool?
    var tags: [String]? = nil
    var studios: [MediaNamed]? = nil
    let people: [MediaPerson]?
}

struct MediaVersion: Decodable, Identifiable {
    let id: String
    let name: String
    let preferred: Bool
    let container: String?
    let size_bytes: Int64?
    let bitrate: Int64?
    let streams: [MediaStream]?
    var path: String? = nil
    var displayPath: String? {
        guard let value = path?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        if value.count > 1, value.first?.isLetter == true, value[value.index(after: value.startIndex)] == ":" { return value }
        guard let components = URLComponents(string: value.hasPrefix("//") ? "https:" + value : value) else { return nil }
        if components.scheme == nil && components.host == nil { return value }
        // 网络源仅展示路径，凭据、查询令牌与片段不进入界面。
        return components.path.isEmpty ? nil : components.path
    }
    var displayName: String {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = displayPath?.replacingOccurrences(of: "\\", with: "/").split(separator: "/").last.map(String.init)
        if !value.isEmpty && !["版本", "默认版本", "default", "default version"].contains(value.lowercased()) { return value }
        return fallback ?? "未提供版本名称"
    }
}

struct MediaStream: Decodable, Identifiable {
    let index: Int
    let type_: String
    let codec: String
    let title: String?
    let display_title: String?
    let language: String?
    let is_external: Bool
    let width: Int?
    let height: Int?
    var id: Int { index }
    var label: String { title ?? display_title ?? [language, codec].compactMap { $0 }.joined(separator: " · ") }
    var summary: String {
        ([Optional(codec.uppercased()), language, width.flatMap { width in height.map { "\(width)×\($0)" } }]).compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

struct InitialSubtitle {
    let version: String
    let stream: MediaStream?
}

struct InitialAudio {
    let version: String
    let stream: MediaStream
}

struct TrackCandidate {
    let id: Int
    let title: String
    let language: String
    let codec: String
    let external: Bool
    let selected: Bool
}

struct TrackPreference {
    static func explicit(_ stream: MediaStream, tracks: [TrackCandidate]) -> Int? {
        func language(_ value: String) -> String {
            let value = value.lowercased()
            return ["chi", "zho", "zh"].contains(value) ? "zh" : value
        }
        func codec(_ value: String) -> String {
            switch value.lowercased() {
            case "srt", "subrip": return "subrip"
            case "vtt", "webvtt": return "webvtt"
            default: return value.lowercased()
            }
        }
        let matching = tracks.filter {
            $0.external == stream.is_external && codec($0.codec) == codec(stream.codec) &&
                language($0.language) == language(stream.language ?? "")
        }
        if !stream.is_external, let indexed = matching.first(where: { $0.id == stream.index }) { return indexed.id }
        let named = matching.filter { $0.title == stream.title || $0.title == stream.display_title }
        return named.count == 1 ? named.first?.id : nil
    }
}

/// 同一剧连续播放时沿用的手选音轨/字幕；只保存在本次播放期间，不持久化，不跨服或跨账号。
@MainActor final class SessionTrackMemory {
    struct Scope: Equatable { let server: String; let user: String; let series: String }
    struct Identity: Equatable { let title: String; let language: String; let codec: String; let external: Bool }
    // 关闭字幕是明确意图，与无法辨识轨道身份不同。
    enum Choice: Equatable { case track(Identity), off }
    private var scope: Scope?
    private var choices: [String: Choice] = [:]

    func enter(_ next: Scope?) { if scope != next { scope = next; choices = [:] } }
    func record(_ key: Scope, kind: String, _ choice: Choice) {
        enter(key); choices[kind] = choice
    }
    func choice(_ key: Scope?, kind: String) -> Choice? { key != nil && key == scope ? choices[kind] : nil }
    func clear() { scope = nil; choices = [:] }

    /// 引擎轨道 ID 每集不同；按标题、语言、编码与外挂属性唯一匹配，不按位置猜测。
    nonisolated static func match(_ identity: Identity, tracks: [TrackCandidate]) -> Int? {
        func language(_ value: String) -> String {
            let value = value.lowercased()
            return value == "und" ? "" : ["chi", "zho", "zh"].contains(value) ? "zh" : value
        }
        guard !identity.title.isEmpty || !language(identity.language).isEmpty else { return nil }
        let matches = tracks.filter {
            $0.title == identity.title && language($0.language) == language(identity.language) &&
                $0.codec.lowercased() == identity.codec.lowercased() && $0.external == identity.external
        }
        return matches.count == 1 ? matches[0].id : nil
    }
}

struct MediaAccount: Decodable {
    let server: String
    let user_name: String
    let name: String
    let active: Bool
    let user_id: String
    let line_url: String
    let aggregate: Bool?
    let aggregate_primary: Bool?
    let lines: [MediaLine]?
    let active_line: Int?
    let remark: String?
    var icon_url: String? = nil
    let allow_insecure_tls: Bool?
    var activeLineName: String {
        let index = active_line ?? 0
        guard let lines, !lines.isEmpty else { return "主线路" }
        guard lines.indices.contains(index) else { return "线路 \(index + 1)" }
        return lines[index].name?.isEmpty == false ? lines[index].name! : "线路 \(index + 1)"
    }
}

struct MediaLine: Decodable {
    let id: String?
    let name: String?
    let url: String
    let remark: String?
}

struct ServerResults: Decodable, Identifiable {
    let server_id: String
    let server_name: String
    let items: [MediaItem]
    let error: String?
    let warning: String?
    var id: String { server_id }
}

struct SourceSearchRow: Decodable {
    let server_id: String
    let server_name: String?
    let emby_items: [MediaItem]?
    let error: String?
    let warning: String?
    func apply(to rows: inout [ServerResults]) {
        let index = rows.firstIndex { $0.server_id == server_id }
        let old = index.map { rows[$0] }
        let items = emby_items ?? []
        if error == nil && items.isEmpty && (warning ?? "").isEmpty {
            if let index { rows.remove(at: index) }
            return
        }
        let row = ServerResults(server_id: server_id, server_name: server_name ?? old?.server_name ?? server_id,
            items: error == nil ? items : old?.items ?? [], error: error, warning: warning)
        if let index { rows[index] = row } else { rows.append(row) }
    }
}

// 换源候选：tier 0 同一部 / 1 可能是 / 2 不像。
struct SwitchCandidate: Decodable, Identifiable {
    let server_id: String
    let server_name: String
    let tier: Int
    let emby_item: MediaItem?
    var id: String { server_id + "/" + (emby_item?.id ?? "") }
}

struct ServerOverview: Decodable, Identifiable {
    let line_url: String?
    struct Counts: Decodable { let movie: Int; let series: Int; let episode: Int; let boxset: Int }
    let server_id: String
    let server_name: String
    let counts: Counts
    let counts_known: Bool
    let counts_error: String?
    let resume: [MediaItem]?
    let error: String?
    var active: Bool? = nil
    var aggregate_primary: Bool? = nil
    var id: String { server_id }
}

struct MediaSession: Decodable {
    let server: String
    let token: String
    let user_id: String
    let user_name: String

    static func restore(_ value: JSONValue) throws -> MediaSession? {
        // 命令总线会将 handler 的 nil 成功结果转换为空对象。
        if value == .null || value == .object([:]) { return nil }
        return try value.decode(MediaSession.self)
    }
}

struct PlayTarget: Decodable {
    struct Subtitle: Decodable {
        let url: String
        let title: String
        let lang: String?
        let is_default: Bool
        let mime_type: String?
    }
    let play_url: String
    let resume_secs: Double
    let media_source_id: String
    let external_subs: [Subtitle]?
}

// 所有业务请求归核心；会话和失效代次在宿主统一合并，不由各页复制凭据。
@MainActor final class MediaAPI {
    let core: LinPlayerCommands
    private(set) var session: MediaSession?
    private(set) var epoch = UUID()
    private(set) var privateSpace = false
    var deviceID = ""
    var onUserStateChanged: (() -> Void)?
    var onAuthExpired: (() -> Void)?

    init(core: LinPlayerCommands) { self.core = core }

    func use(_ session: MediaSession?) {
        self.session = session
        privateSpace = false
        epoch = UUID()
    }

    func setPrivateSpace(_ enabled: Bool) async throws {
        guard enabled != privateSpace else { return }
        if enabled {
            guard let session else { throw CoreFailure(code: "E_AUTH") }
            let generation = epoch
            // 分类探测不能使用普通空间已过滤的库清单。
            let result = try await core.call("emby.views", ["server": .string(session.server), "token": .string(session.token),
                "user_id": .string(session.user_id), "device_id": .string(deviceID)])
            try Task.checkCancellation()
            guard generation == epoch else { throw CancellationError() }
            guard case .array(let rows) = result, !rows.isEmpty,
                  rows.allSatisfy({ $0["is_adult"].bool != nil }) else { throw CoreFailure(code: "E_UNSUPPORTED") }
        }
        privateSpace = enabled; epoch = UUID()
    }

    func request(_ command: String, _ args: [String: JSONValue] = [:], snapshot: ((JSONValue) -> Void)? = nil) async throws -> JSONValue {
        guard let s = session else { throw CoreFailure(code: "E_AUTH") }
        let generation = epoch
        var all = args
        if all["server"] == nil { all["server"] = .string(s.server) }
        if all["token"] == nil { all["token"] = .string(s.token) }
        if all["user_id"] == nil { all["user_id"] = .string(s.user_id) }
        if all["device_id"] == nil { all["device_id"] = .string(deviceID) }
        all["request_scope"] = .object(["id": .string(generation.uuidString), "private": .bool(privateSpace), "filter": .bool(true),
            "server": .string(s.server), "user_id": .string(s.user_id), "token": .string(s.token)])
        do {
            let result: JSONValue
            if let snapshot, let displayCore = core as? DisplayCommands {
                var final: JSONValue?
                for try await update in displayCore.display(command, all) {
                    try Task.checkCancellation()
                    guard epoch == generation else { throw CancellationError() }
                    switch update {
                    case .snapshot(let value): snapshot(value)
                    case .result(let value): final = value
                    }
                }
                guard let final else { throw CoreFailure(code: "E_PROTOCOL") }
                result = final
            } else { result = try await core.call(command, all) }
            try Task.checkCancellation()
            guard epoch == generation else { throw CancellationError() }
            return result
        } catch {
            try Task.checkCancellation()
            guard epoch == generation else { throw CancellationError() }
            if (error as? CoreFailure)?.code == "E_AUTH", epoch == generation { onAuthExpired?() }
            throw error
        }
    }

    func userStateChanged(_ data: JSONValue) {
        if data["id"].string == epoch.uuidString { onUserStateChanged?() }
    }

    func items(parent: String, start: Int, query: BrowseQuery = BrowseQuery(), snapshot: ((MediaPage) -> Void)? = nil) async throws -> MediaPage {
        try await request("emby.listItemsPage", ["parent_id": .string(parent),
            "query": .object(query.arguments(start: start))], snapshot: { value in
                if let page = try? value.decode(MediaPage.self) { snapshot?(page) }
            }).decode(MediaPage.self)
    }

    func episodes(parent: String, start: Int, snapshot: ((MediaPage) -> Void)? = nil) async throws -> MediaPage {
        try await request("emby.seasonEpisodes", ["parent_id": .string(parent),
            "start_index": .number(Double(start)), "limit": .number(200)], snapshot: { value in
                if let page = try? value.decode(MediaPage.self) { snapshot?(page) }
            }).decode(MediaPage.self)
    }

    static func appendEpisodes(_ existing: [MediaItem], page: [MediaItem]) throws -> [MediaItem] {
        var seen = Set(existing.map(\.id))
        guard page.allSatisfy({ !$0.id.isEmpty && seen.insert($0.id).inserted }) else { throw CoreFailure(code: "E_PROTOCOL") }
        return existing + page
    }

    func trackPreference(_ tracks: [TrackCandidate], kind: String, prefs: JSONValue) async throws -> String {
        let rows = tracks.map { track in JSONValue.object([
            "id": .string(String(track.id)), "kind": .string(kind), "title": .string(track.title),
            "lang": .string(track.language), "selected": .bool(track.selected)]) }
        return try await request("player.selectTrackPreferences", ["tracks": .array(rows), "prefs": prefs])[kind].string ?? ""
    }

    func heroCandidates(_ items: [MediaItem]) async throws -> [MediaItem] {
        let rows = items.map { item -> JSONValue in
            var row: [String: JSONValue] = ["id": .string(item.id), "type_": .string(item.type_),
                "played": .bool(item.played == true), "resume_secs": .number(item.resume_secs)]
            if let series = item.series_id { row["series_id"] = .string(series) }
            return .object(row)
        }
        let ids = Set(try await request("emby.heroCandidates", ["items": .array(rows)]).decode([String].self))
        var seen = Set<String>()
        return items.filter { ids.contains($0.id) && seen.insert($0.id).inserted }
    }

    func playbackSeason(item: String) async throws -> [MediaItem] {
        try await request("emby.playbackSeason", ["item_id": .string(item)]).decode([MediaItem].self)
    }

    func switchCandidates(title: String, year: Int?, update: @escaping ([SwitchCandidate]) -> Void) async throws -> [SwitchCandidate] {
        guard !privateSpace, let session else { throw CoreFailure(code: "E_PERMISSION") }
        var rows: [SwitchCandidate] = []
        return try await request("source.switchCandidates", ["server_id": .string(session.server), "title": .string(title), "year": .number(Double(year ?? 0))], snapshot: { value in
            rows += (try? value.decode([SwitchCandidate].self)) ?? []; update(rows)
        }).decode([SwitchCandidate].self)
    }

    func aggregateSearch(_ query: String, update: @escaping ([ServerResults]) -> Void) async throws -> [ServerResults] {
        guard !privateSpace else { throw CoreFailure(code: "E_PERMISSION") }
        var rows: [ServerResults] = []
        let result = try await request("source.aggregateSearch", ["query": .string(query)], snapshot: { value in
            if let row = try? value.decode(SourceSearchRow.self) { row.apply(to: &rows); update(rows) }
        }).decode([SourceSearchRow].self)
        let ids = Set(result.map(\.server_id))
        rows.removeAll { !ids.contains($0.server_id) }
        for row in result { row.apply(to: &rows) }
        return rows
    }

    func homePlaybackTarget(_ item: MediaItem) async throws -> MediaItem {
        guard item.type_ == "Series" else { return item }
        return try await request("emby.seriesPlaybackTarget", ["series_id": .string(item.id)]).decode(MediaItem.self)
    }

    func play(item: String, version: String?, fromStart: Bool, speedScope: String = "normal", preparedID: String? = nil) async throws -> PlayTarget {
        var args: [String: JSONValue] = ["item_id": .string(item), "engine": .string("exo")]
        if let preparedID { args["prepared_id"] = .string(preparedID) }
        if speedScope == "liuying" { args["speed_scope"] = .string("liuying") }
        if let version, !version.isEmpty { args["media_source_id"] = .string(version) }
        if fromStart { args["from_start"] = .bool(true) }
        return try await request("player.play", args).decode(PlayTarget.self)
    }
}

struct PrefetchSettings: Decodable {
    let servers: [String]
    let threads: Int
    let cache_bytes: Int
    let media_cache_bytes: Int

    func arguments(server: String, enabled: Bool, threads: Int, cacheMiB: Int) -> [String: JSONValue] {
        var revised = servers.filter { $0 != server }
        if enabled { revised.append(server) }
        return ["settings": .object([
            "servers": .array(revised.map(JSONValue.string)), "threads": .number(Double(threads)),
            "cache_bytes": .number(Double(cacheMiB) * 1024 * 1024)
        ])]
    }
}

struct PrimaryProgressSettings: Decodable {
    let server: String
    let valid: Bool
    let pending: Int
    let conflicts: Int
    let error: String?
}

struct PlaybackSkip: Decodable {
    struct Range: Decodable, Equatable {
        let start: Double
        let end: Double
        func contains(_ position: Double, duration: Double) -> Bool {
            start.isFinite && end.isFinite && start >= 0 && end > start && end <= duration && position >= start && position < end
        }
    }
    let intro: Range?
    let outro: Range?
    let skip_auto: Bool
    let skip_source: String
    var thumbs: Bool? = nil
    var chapters: [Chapter]? = nil
    struct Chapter: Decodable, Identifiable {
        let index: Int
        let start_secs: Double
        let name: String
        let image_url: String?
        var id: Int { index }
    }
    func active(at position: Double, duration: Double) -> Range? {
        [intro, outro].compactMap { $0 }.first { $0.contains(position, duration: duration) }
    }
    static func nextUp(at position: Double, duration: Double, outro: Range?) -> Bool {
        guard position.isFinite, position >= 0, duration.isFinite, duration > 0 else { return false }
        if let outro { return outro.end >= duration && position >= outro.start }
        return duration - position <= min(30, max(10, duration * 0.1))
    }
}

struct SkipMarkerSelection: Decodable {
    let scope: String
    let can_season: Bool
    let intro: PlaybackSkip.Range?
    let outro: PlaybackSkip.Range?
}

struct LiuYingItem: Decodable, Identifiable {
    let item_id: String
    let title: String
    let author: String?
    let tab: String
    let feed: String
    var favorite: Bool
    var id: String { item_id }
}

struct LiuYingPage: Decodable {
    var items: [LiuYingItem]
    let has_more: Bool
    let offset: Int
    mutating func setFavorite(_ id: String, _ value: Bool) {
        for index in items.indices where items[index].item_id == id { items[index].favorite = value }
    }
}

struct TVDanmakuLayout: Decodable {
    struct Line: Decodable {
        let t: Double
        let m: Int
        let l: Int
        let w: Double
        let c: Int
        let u: String
    }
    let res_y: Double
    let area_height: Double
    let lane_height: Double
    let roll_seconds: Double
    let fix_seconds: Double
    let font_size: Double
    let opacity: Double
    let bold: Bool
    let revision: Double
    let items: [Line]
    func candidates(at time: Double) -> ArraySlice<Line> {
        let from = time - max(roll_seconds, fix_seconds)
        var low = 0, high = items.count
        while low < high {
            let mid = (low + high) / 2
            if items[mid].t < from { low = mid + 1 } else { high = mid }
        }
        return items[low...].prefix { $0.t <= time }
    }
}

struct TVRemoteFrame {
    let id: UUID
    let x: Double
    let y: Double
    static func next(_ direction: String, x: Double, y: Double, candidates: [TVRemoteFrame]) -> UUID? {
        let horizontal = direction == "left" || direction == "right"
        let sign: Double = direction == "left" || direction == "up" ? -1 : 1
        return candidates.compactMap { candidate -> (UUID, Double)? in
            let primary = (horizontal ? candidate.x - x : candidate.y - y) * sign
            let cross = abs(horizontal ? candidate.y - y : candidate.x - x)
            guard primary > 1, primary.isFinite, cross.isFinite else { return nil }
            return (candidate.id, primary + cross * 3)
        }.min { $0.1 < $1.1 }?.0
    }
}
