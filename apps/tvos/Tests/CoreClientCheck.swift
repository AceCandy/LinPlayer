import Foundation

final class FakeTransport: CoreTransport, @unchecked Sendable {
    private let lock = NSCondition()
    private var events: [Data] = []
    private(set) var sent: [(Int64, String)] = []
    var rejection: Int32 = 0
    private(set) var cancels: [Int64] = []
    func send(seq: Int64, command: String, args: Data) -> Int32 {
        lock.lock(); defer { lock.unlock() }; sent.append((seq, command)); return rejection
    }
    func cancel(seq: Int64) { lock.lock(); cancels.append(seq); lock.unlock() }
    func next() -> Data? {
        lock.lock(); defer { lock.unlock() }
        if events.isEmpty { _ = lock.wait(until: Date().addingTimeInterval(0.02)) }
        return events.isEmpty ? nil : events.removeFirst()
    }
    func emit(_ value: String) { lock.lock(); events.append(Data(value.utf8)); lock.signal(); lock.unlock() }
    func shutdown() { emit(#"{"t":"eof"}"#) }
    func snapshot() -> [(Int64, String)] { lock.lock(); defer { lock.unlock() }; return sent }
    func sent(_ count: Int) async -> [(Int64, String)] {
        for _ in 0..<400 {
            let v = snapshot(); if v.count >= count { return v }
            try? await Task.sleep(for: .milliseconds(5))
        }
        preconditionFailure("发送或事件泵被阻塞")
    }
}

@MainActor final class FakeCommands: LinPlayerCommands {
    var calls: [(String, [String: JSONValue])] = []
    var held: CheckedContinuation<JSONValue, Error>?
    var responses: [String: [JSONValue]] = [:]
    var failures: [String: CoreFailure] = [:]
    var holdCommand = "held"
    func call(_ command: String, _ args: [String: JSONValue]) async throws -> JSONValue {
        calls.append((command, args))
        if let failure = failures[command] { throw failure }
        if var values = responses[command], !values.isEmpty {
            let value = values.removeFirst(); responses[command] = values; return value
        }
        if command == holdCommand { return try await withCheckedThrowingContinuation { held = $0 } }
        if command == "player.play" {
            return try JSONDecoder().decode(JSONValue.self, from: Data(#"{"play_url":"https://example.invalid/media","resume_secs":126,"media_source_id":"source-fixture","external_subs":[]}"#.utf8))
        }
        return .object(["items": .array([]), "total": .number(0)])
    }
}

@main struct CoreClientCheck {
    static func main() async throws {
        let transport = FakeTransport(), client: CoreClient
        let partials = FakeTransport()
        client = CoreClient(transport: transport)
        client.onPartial = { seq, value in
            precondition(value["step"] == .number(1))
            partials.emit("{\"seq\":\(seq)}")
        }
        client.start()
        let first = Task { try await client.call("array", [:]) }
        let second = Task { try await client.call("scalar", [:]) }
        let third = Task { try await client.call("null", [:]) }
        let calls = await transport.sent(3)
        for (seq, name) in calls.reversed() {
            let data = name == "array" ? "[1,2]" : name == "scalar" ? "42" : "null"
            transport.emit("{\"t\":\"partial\",\"seq\":\(seq),\"data\":{\"step\":1}}")
            transport.emit("{\"t\":\"event\",\"name\":\"unknown.future.event\",\"data\":null}")
            transport.emit("{\"t\":\"result\",\"seq\":\(seq),\"ok\":true,\"data\":\(data)}")
        }
        let a = try await first.value, b = try await second.value, c = try await third.value
        precondition(a == .array([.number(1),.number(2)]) && b == .number(42) && c == .null)
        for _ in 0..<3 { precondition(partials.next() != nil, "partial 回调丢失") }
        let earlyCancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await client.call("early-cancel", [:])
        }
        do { _ = try await earlyCancelled.value; preconditionFailure("预先取消未传播") } catch is CancellationError {}
        let cancelled = Task { try await client.call("cancelled", [:]) }
        let fourth = await transport.sent(4); cancelled.cancel()
        do { _ = try await cancelled.value; preconditionFailure("取消未传播") } catch is CancellationError {} catch { throw error }
        transport.emit("{\"t\":\"result\",\"seq\":\(fourth.last!.0),\"ok\":true,\"data\":false}")
        let afterCancel = Task { try await client.call("after-cancel", [:]) }
        let fifth = await transport.sent(5)
        transport.emit("{\"t\":\"result\",\"seq\":\(fifth.last!.0),\"ok\":true,\"data\":true}")
        let afterValue = try await afterCancel.value; precondition(afterValue == .bool(true))
        let waiting = Task { try await client.call("pending", [:]) }; _ = await transport.sent(6)
        await client.shutdown()
        do { _ = try await waiting.value; preconditionFailure("关停没有结束等待") } catch {}

        let rejecting = FakeTransport(); rejecting.rejection = -4
        let rejected = CoreClient(transport: rejecting); rejected.start()
        do { _ = try await rejected.call("reject", [:]); preconditionFailure("接受失败被当作成功") }
        catch { precondition((error as? CoreFailure)?.code == "E_BUSY") }
        await rejected.shutdown()

        let malformed = FakeTransport(), bad = CoreClient(transport: malformed); bad.start()
        let badCall = Task { try await bad.call("malformed", [:]) }; _ = await malformed.sent(1)
        malformed.emit("{broken")
        do { _ = try await badCall.value; preconditionFailure("损坏信封导致永久等待") } catch {}
        await bad.shutdown()

        try await displayContracts()
        try await pagedDisplayContracts()
        try await apiContracts()
        print("Swift CoreClient / DTO / request checks passed")
    }

    @MainActor static func displayContracts() async throws {
        let transport = FakeTransport()
        let core = CoreClient(transport: transport); core.start()
        let api = MediaAPI(core: core)
        let session = try json(#"{"server":"https://example.invalid","token":"TOKEN_PLACEHOLDER","user_id":"USER_PLACEHOLDER","user_name":"fixture"}"#).decode(MediaSession.self)
        api.use(session)
        var snapshots: [JSONValue] = []
        let first = Task { try await api.request("emby.views", snapshot: { snapshots.append($0) }) }
        let sent = await transport.sent(1); let seq = sent[0].0
        transport.emit("{\"t\":\"partial\",\"seq\":\(seq),\"data\":[1]}")
        transport.emit("{\"t\":\"result\",\"seq\":\(seq),\"ok\":true,\"data\":[2]}")
        let final = try await first.value
        precondition(snapshots == [.array([.number(1)])] && final == .array([.number(2)]), "展示快照与实时结果必须分离并顺序交付")
        let changed = Task { try await api.request("emby.views", snapshot: { snapshots.append($0) }) }
        let next = await transport.sent(2); api.use(session)
        transport.emit("{\"t\":\"partial\",\"seq\":\(next[1].0),\"data\":[3]}")
        transport.emit("{\"t\":\"result\",\"seq\":\(next[1].0),\"ok\":true,\"data\":[4]}")
        do { _ = try await changed.value; preconditionFailure("旧身份快照未取消") } catch is CancellationError {}
        precondition(snapshots.count == 1, "旧身份快照污染页面")
        let failed = Task { try await api.request("emby.views", snapshot: { snapshots.append($0) }) }
        let last = await transport.sent(3)
        transport.emit("{\"t\":\"partial\",\"seq\":\(last[2].0),\"data\":[5]}")
        transport.emit("{\"t\":\"result\",\"seq\":\(last[2].0),\"ok\":false,\"err\":{\"code\":\"E_NETWORK\"}}")
        do { _ = try await failed.value; preconditionFailure("缓存命中不得吞掉实时失败") }
        catch { precondition((error as? CoreFailure)?.code == "E_NETWORK") }
        precondition(snapshots.last == .array([.number(5)]))
        let cancelled = Task { try await api.request("emby.views", snapshot: { snapshots.append($0) }) }
        let cancelledCalls = await transport.sent(4)
        cancelled.cancel()
        do { _ = try await cancelled.value; preconditionFailure("展示请求取消未传播") } catch is CancellationError {}
        transport.emit("{\"t\":\"partial\",\"seq\":\(cancelledCalls[3].0),\"data\":[6]}")
        await core.shutdown()
        precondition(snapshots.count == 2, "取消后迟到快照进入页面")
    }

    @MainActor static func pagedDisplayContracts() async throws {
        let transport = FakeTransport(), core: CoreClient
        core = CoreClient(transport: transport); core.start()
        let api = MediaAPI(core: core)
        api.use(try json(#"{"server":"https://example.invalid","token":"TOKEN_PLACEHOLDER","user_id":"USER_PLACEHOLDER","user_name":"fixture"}"#).decode(MediaSession.self))
        for index in 0..<2 {
            var totals: [Int] = []
            let task = Task {
                if index == 0 { return try await api.items(parent: "library", start: 0, snapshot: { totals.append($0.total) }) }
                return try await api.episodes(parent: "season", start: 0, snapshot: { totals.append($0.total) })
            }
            let sent = await transport.sent(index + 1), seq = sent[index].0
            transport.emit("{\"t\":\"partial\",\"seq\":\(seq),\"data\":{\"items\":[],\"total\":7}}")
            transport.emit("{\"t\":\"result\",\"seq\":\(seq),\"ok\":true,\"data\":{\"items\":[],\"total\":9}}")
            let final = try await task.value
            precondition(totals == [7] && final.total == 9, "分页展示必须分别交付快照和实时结果")
        }
        await core.shutdown()
    }

    static func json(_ value: String) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: Data(value.utf8))
    }

    @MainActor static func apiContracts() async throws {
        let seasonDone = try json(#"{"id":"s","name":"Season","child_count":12,"unplayed":0,"unplayed_count_known":true,"played":false}"#).decode(MediaSeason.self)
        let seasonUnknown = try json(#"{"id":"s","name":"Season","child_count":12,"unplayed":0}"#).decode(MediaSeason.self)
        precondition(seasonDone.statusText == "已看完" && seasonUnknown.statusText == "共 12 集")
        let liveEpisode = try json(#"{"id":"e1","name":"E1","type_":"Episode","is_folder":false,"has_primary":false,"runtime_secs":100,"resume_secs":0,"season_id":"s","played":false}"#).decode(MediaItem.self)
        let livePage = MediaPage(items: [liveEpisode], total: 1)
        precondition(seasonDone.calibrated(livePage)?.statusText == "1 集未看", "完整实时分集应覆盖陈旧季统计")
        precondition(seasonDone.calibrated(MediaPage(items: [liveEpisode], total: 2)) == nil, "分页未完整不能校准")
        precondition(seasonDone.calibrated(MediaPage(items: [liveEpisode, liveEpisode], total: 2)) == nil, "重复分集不能校准")
        precondition(seasonDone.calibrated(MediaPage(items: [], total: 0)) == nil, "空季不能伪装已看完")
        let unknownEpisode = try json(#"{"id":"e2","name":"E2","type_":"Episode","is_folder":false,"has_primary":false,"runtime_secs":100,"resume_secs":0,"season_id":"s"}"#).decode(MediaItem.self)
        precondition(seasonDone.calibrated(MediaPage(items: [unknownEpisode], total: 1)) == nil, "无实时已看状态不能校准")
        let seasonArt = seasonUnknown.artwork(series: "series")
        precondition(seasonArt.artworkSources == [ArtworkSource(id: "series", kind: "Primary")], "缺季图必须回退剧海报")
        let history = try json(#"{"record_id":"r","title":"E1","series_title":"Series","media_kind":"episode","last_emby_item_id":"e1","last_played_at":1000,"last_position_ticks":500000000,"run_time_ticks":1000000000,"played":false}"#).decode(WatchHistoryRecord.self)
        precondition(history.item?.id == "e1" && history.item?.type_ == "Episode" && history.item?.resume_secs == 50)
        let orphan = try json(#"{"record_id":"r","title":"Old","media_kind":"movie","last_emby_item_id":null,"last_played_at":1000,"last_position_ticks":0,"played":true}"#).decode(WatchHistoryRecord.self)
        precondition(orphan.item == nil, "历史缺少服务器条目时不能猜测路由")
        let cachedDetail = try json(#"{"id":"item","name":"Name","overview":"Overview","has_primary":true,"rating":8.1}"#).decode(DetailPresentation.self)
        precondition(cachedDetail.name == "Name" && cachedDetail.rating == 8.1)
        let detailMetadata = try json(#"{"id":"fixture","name":"Fixture","type_":"Movie","overview":"Overview","is_favorite":false,"played":false,"runtime_secs":3600,"resume_secs":0,"has_primary":true,"has_backdrop":true,"rating":8.2,"DoubanRating":8.8,"premiere_date":"2026-01-01","official_rating":"PG","status":"Ended","tagline":"Tagline"}"#).decode(MediaDetail.self)
        precondition(detailMetadata.has_backdrop == true && detailMetadata.rating == 8.2 && detailMetadata.DoubanRating == 8.8 && detailMetadata.tagline == "Tagline")
        let heroMovie = MediaItem.route(id: "movie", name: "Movie", type: "Movie")
        let heroSeries = MediaItem.route(id: "series", name: "Series", type: "Series")
        let watchedEpisode = try json(#"{"id":"episode","name":"Episode","type_":"Episode","is_folder":false,"has_primary":true,"runtime_secs":100,"resume_secs":20,"series_id":"series"}"#).decode(MediaItem.self)

        let scored = try json(#"{"id":"fixture","name":"fixture","type_":"Series","is_folder":true,"has_primary":false,"runtime_secs":0,"resume_secs":0,"rating":8.6,"DoubanRating":9.1,"unplayed_item_count":6,"unplayed_count_known":true,"played":false,"library_ids":["library-a","library-b"]}"#).decode(MediaItem.self)
        precondition(scored.library_ids == ["library-a", "library-b"], "收藏库归属字段不能丢失")
        precondition(scored.ratingText() == "8.6" && scored.ratingText(douban: true) == "9.1", "评分来源或字段名不匹配")
        precondition(scored.episodeStatus == 6 && scored.resumeTimeText == nil)
        let unknown = try json(#"{"id":"fixture","name":"fixture","type_":"Series","is_folder":true,"has_primary":false,"runtime_secs":0,"resume_secs":0,"rating":null,"unplayed_item_count":0,"played":true}"#).decode(MediaItem.self)
        precondition(unknown.library_ids == nil, "旧接口缺省库归属仍可读取")
        precondition(unknown.episodeStatus == nil && unknown.ratingText() == nil && unknown.ratingText(douban: true) == nil, "未知整剧统计不得显示完成")
        let resumed = try json(#"{"id":"fixture","name":"fixture","type_":"Episode","is_folder":false,"has_primary":false,"runtime_secs":3600,"resume_secs":1029,"played":false}"#).decode(MediaItem.self)
        precondition(resumed.resumeTimeText == "剩余 42:51")
        var invalid = scored
        invalid.rating = 0; invalid.DoubanRating = 0
        precondition(invalid.ratingText() == nil && invalid.ratingText(douban: true) == "0.0")
        invalid.rating = 11; invalid.DoubanRating = .infinity
        precondition(invalid.ratingText() == nil && invalid.ratingText(douban: true) == nil)
        let prefetch = try json(#"{"servers":["https://a.invalid","https://b.invalid"],"threads":3,"cache_bytes":536870912,"media_cache_bytes":1073741824}"#).decode(PrefetchSettings.self)
        let disabled = prefetch.arguments(server: "https://a.invalid", enabled: false, threads: 4, cacheMiB: 256)
        precondition(disabled["settings"] == .object([
            "servers": .array([.string("https://b.invalid")]), "threads": .number(4), "cache_bytes": .number(268435456)
        ]), "预取保存必须嵌套并保留其它账号，不覆盖媒体缓存预算")
        let enabled = prefetch.arguments(server: "https://a.invalid", enabled: true, threads: 2, cacheMiB: 512)
        precondition(enabled["settings"]?["servers"] == .array([.string("https://b.invalid"), .string("https://a.invalid")]), "启用预取不得重复账号")
        let progress = try json(#"{"server":"","valid":false,"pending":0,"conflicts":0}"#).decode(PrimaryProgressSettings.self)
        precondition(progress.error == nil && !progress.valid)
        let retry = try json(#"{"server":"https://a.invalid","valid":true,"pending":2,"conflicts":1,"error":"待同步记录已保留"}"#).decode(PrimaryProgressSettings.self)
        precondition(retry.error != nil && retry.pending == 2 && retry.conflicts == 1, "成功信封中的同步失败状态不能丢失")
        let emptySession = try MediaSession.restore(.object([:]))
        let nullSession = try MediaSession.restore(.null)
        precondition(emptySession == nil && nullSession == nil, "未登录结果未进入登录流程")
        let sessionFixture = #"{"server":"https://example.invalid","token":"TOKEN_PLACEHOLDER","user_id":"USER_PLACEHOLDER","user_name":"fixture"}"#
        let restored = try MediaSession.restore(JSONDecoder().decode(JSONValue.self, from: Data(sessionFixture.utf8)))
        precondition(restored?.user_name == "fixture", "有效会话未恢复")
        for malformed in [JSONValue.object(["server": .string("https://example.invalid")]), .array([])] {
            do { _ = try MediaSession.restore(malformed); preconditionFailure("损坏会话被当作未登录") }
            catch { precondition((error as? CoreFailure)?.code == "E_PROTOCOL") }
        }
        let commands = FakeCommands(), api = MediaAPI(core: commands)
        api.deviceID = "DEVICE_PLACEHOLDER"
        api.use(MediaSession(server: "https://example.invalid", token: "TOKEN_PLACEHOLDER", user_id: "USER_PLACEHOLDER", user_name: "fixture"))
        let beforeTarget = commands.calls.count
        let movieTarget = try await api.homePlaybackTarget(heroMovie)
        precondition(movieTarget.id == heroMovie.id && commands.calls.count == beforeTarget, "电影不应请求剧集选集")
        commands.responses["emby.seriesPlaybackTarget"] = [try json(#"{"id":"episode","name":"Episode","type_":"Episode","is_folder":false,"has_primary":true,"runtime_secs":100,"resume_secs":20}"#)]
        let seriesTarget = try await api.homePlaybackTarget(heroSeries)
        precondition(seriesTarget.id == "episode" && seriesTarget.resume_secs == 20)
        precondition(commands.calls.last!.0 == "emby.seriesPlaybackTarget" && commands.calls.last!.1["series_id"] == .string("series"))
        precondition(commands.calls.last!.1["request_scope"]?["private"] == .bool(false))
        commands.failures["emby.seriesPlaybackTarget"] = CoreFailure(code: "E_NETWORK")
        do { _ = try await api.homePlaybackTarget(heroSeries); preconditionFailure("选集失败被当作播放成功") }
        catch { precondition((error as? CoreFailure)?.code == "E_NETWORK") }
        commands.failures = [:]
        for (path, expected) in [
            ("/media/film.mkv", "/media/film.mkv"),
            (#"D:\Media\film.mkv"#, #"D:\Media\film.mkv"#),
            ("https://USER_PLACEHOLDER:PASSWORD_PLACEHOLDER@example.invalid/media/film.mkv?token=TOKEN_PLACEHOLDER#FRAGMENT_PLACEHOLDER", "/media/film.mkv"),
            ("//USER_PLACEHOLDER:PASSWORD_PLACEHOLDER@example.invalid/media/film.mkv?token=TOKEN_PLACEHOLDER", "/media/film.mkv"),
            ("https://example.invalid?token=TOKEN_PLACEHOLDER", nil), ("  ", nil)
        ] as [(String, String?)] {
            let version = try JSONValue.object(["id": .string("source"), "name": .string("版本"), "preferred": .bool(true), "path": .string(path)]).decode(MediaVersion.self)
            precondition(version.displayPath == expected, "媒体路径必须保留本地文件名并移除远程凭据和查询")
        }
        commands.responses["emby.heroCandidates"] = [.array([.string("movie")])]
        let heroes = try await api.heroCandidates([heroMovie, heroSeries, watchedEpisode, heroMovie])
        precondition(heroes.map(\.id) == ["movie"], "必须按核心返回候选去重展示")
        _ = try await api.items(parent: "library-fixture", start: 40)
        let request = commands.calls.last!.1
        precondition(request["query"]?["start_index"] == .number(40) && request["start_index"] == nil)
        precondition(request["request_scope"]?["private"] == .bool(false) && request["device_id"] == .string("DEVICE_PLACEHOLDER"))
        _ = try await api.play(item: "movie-fixture", version: nil, fromStart: false)
        precondition(commands.calls.last!.1["media_source_id"] == nil && commands.calls.last!.1["resume_secs"] == nil)
        let target = try await api.play(item: "movie-fixture", version: "source-fixture", fromStart: true)
        precondition(commands.calls.last!.1["from_start"] == .bool(true) && commands.calls.last!.1["media_source_id"] == .string("source-fixture"))
        precondition(target.resume_secs == 126)
        let fixture = #"{"items":[{"id":"item-fixture","name":"电影","type_":"Movie","is_folder":false,"has_primary":false,"runtime_secs":100,"resume_secs":5,"series_name":null,"episode_no":null,"year":null}],"total":51}"#
        let page = try JSONDecoder().decode(MediaPage.self, from: Data(fixture.utf8))
        precondition(page.items.first?.type_ == "Movie" && page.total == 51)
        let episodeArtwork = #"{"id":"episode-fixture","name":"分集","type_":"Episode","is_folder":false,"has_primary":true,"runtime_secs":100,"resume_secs":5,"season_id":"season-fixture","series_id":"series-fixture","has_backdrop":true}"#
        let episode = try JSONDecoder().decode(MediaItem.self, from: Data(episodeArtwork.utf8))
        precondition(episode.artworkSources == [ArtworkSource(id: "episode-fixture", kind: "Primary"),
            ArtworkSource(id: "episode-fixture", kind: "Backdrop/0"), ArtworkSource(id: "season-fixture", kind: "Primary"),
            ArtworkSource(id: "series-fixture", kind: "Backdrop/0"), ArtworkSource(id: "series-fixture", kind: "Primary")], "分集缺图回退顺序错误")
        let noOwnArtwork = episodeArtwork.replacingOccurrences(of: "\"has_primary\":true", with: "\"has_primary\":false")
            .replacingOccurrences(of: "\"has_backdrop\":true", with: "\"has_backdrop\":false")
        let withoutOwn = try JSONDecoder().decode(MediaItem.self, from: Data(noOwnArtwork.utf8))
        precondition(withoutOwn.artworkSources.first == ArtworkSource(id: "season-fixture", kind: "Primary"), "本集无图阻断父级回退")
        let omittedBackdrop = try json(episodeArtwork
            .replacingOccurrences(of: ",\"has_backdrop\":true", with: "")
            .replacingOccurrences(of: "\"has_primary\":true", with: "\"has_primary\":false")).decode(MediaItem.self)
        precondition(omittedBackdrop.has_backdrop == nil)
        precondition(omittedBackdrop.artworkSources == withoutOwn.artworkSources,
            "核心省略false背景标记时仍探测了本集背景图")
        precondition(page.items[0].artworkSources.isEmpty, "电影无图却请求父级")
        do { _ = try JSONValue.null.decode(MediaPage.self); preconditionFailure("损坏 DTO 被接受") }
        catch { precondition((error as? CoreFailure)?.code == "E_PROTOCOL") }
        var query = BrowseQuery()
        query.sort = "ProductionYear"; query.descending = true; query.genre = "剧情"; query.year = "2025"; query.played = "false"; query.ratingMin = 8
        _ = try await api.items(parent: "library-fixture", start: 80, query: query)
        let filtered = commands.calls.last!.1
        precondition(filtered["query"]?["sort_order"] == .string("Descending"))
        precondition(filtered["query"]?["genres"] == .array([.string("剧情")]) && filtered["query"]?["played"] == .bool(false))
        precondition(filtered["query"]?["years"] == .array([.number(2025)]) && filtered["genres"] == nil)
        precondition(filtered["query"]?["rating_min"] == .number(8) && filtered["rating_min"] == nil, "评分下限未放入核心分页query")
        precondition(BrowseQuery().arguments(start: 0)["rating_min"] == nil)
        let works = try json(#"{"items":[],"total":null,"next_index":30,"has_more":true}"#).decode(PersonWorksPage.self)
        var workState = PersonWorksState()
        try workState.append(works)
        precondition(workState.cursor == 30 && workState.items.isEmpty && workState.more, "全页屏蔽后丢失核心游标")
        try workState.append(PersonWorksPage(items: page.items + page.items, total: 100, next_index: 60, has_more: true))
        precondition(workState.cursor == 60 && workState.items.count == Set(page.items.map(\.id)).count, "作品去重改变了分页游标")
        do {
            try workState.append(PersonWorksPage(items: [], total: 100, next_index: 60, has_more: true))
            preconditionFailure("未推进游标仍继续翻页")
        } catch { precondition((error as? CoreFailure)?.code == "E_PROTOCOL" && workState.cursor == 60) }
        try workState.append(PersonWorksPage(items: [], total: 100, next_index: 100, has_more: false))
        precondition(!workState.more && workState.cursor == 100)
        let hero = try json(#"{"id":"hero","name":"作品","type_":"Movie","is_folder":false,"has_primary":false,"runtime_secs":0,"resume_secs":0,"has_logo":true,"genres":["科幻"]}"#).decode(MediaItem.self)
        precondition(hero.has_logo == true && hero.genres == ["科幻"])

        var revisions = 0
        api.onUserStateChanged = { revisions += 1 }
        _ = try await api.request("emby.setFavorite", ["item_id": .string("item-fixture"), "fav": .bool(true)])
        precondition(revisions == 0, "宿主不能重复推导写入通知")
        api.userStateChanged(.object(["id": .string(api.epoch.uuidString), "scope": .string("library")]))
        precondition(revisions == 1)
        commands.failures["emby.setFavorite"] = CoreFailure(code: "E_NETWORK")
        do { _ = try await api.request("emby.setFavorite"); preconditionFailure("写入失败被吞掉") } catch {}
        precondition(revisions == 1, "失败写入触发刷新")
        commands.failures = [:]

        commands.responses["emby.views"] = [.array([.object(["id": .string("library-fixture")])])]
        do { try await api.setPrivateSpace(true); preconditionFailure("缺少分类仍进入隐私空间") } catch {}
        precondition(!api.privateSpace)
        commands.responses["emby.views"] = [.array([.object(["is_adult": .bool(false)]), .object(["is_adult": .bool(true)])])]
        let beforeSpace = api.epoch
        try await api.setPrivateSpace(true)
        precondition(api.privateSpace && beforeSpace != api.epoch)
        precondition(commands.calls.last!.1["is_adult"] == nil, "探测误用过滤后的库清单")
        _ = try await api.items(parent: "private-library-fixture", start: 0)
        precondition(commands.calls.last!.1["request_scope"]?["private"] == .bool(true))
        try await api.setPrivateSpace(false)
        precondition(!api.privateSpace)

        let detail = try json(#"{"id":"episode-fixture","name":"第2集","type_":"Episode","overview":"","genres":null,"is_favorite":false,"played":false,"season_id":"season-fixture","season_no":1,"runtime_secs":100,"resume_secs":0,"has_primary":false}"#)
        let firstEpisode = try json(#"{"id":"first-fixture","name":"第1集","type_":"Episode","is_folder":false,"has_primary":false,"runtime_secs":100,"resume_secs":0,"episode_no":1,"season_no":1}"#)
        let currentEpisode = try json(#"{"id":"episode-fixture","name":"第2集","type_":"Episode","is_folder":false,"has_primary":false,"runtime_secs":100,"resume_secs":0,"episode_no":2,"season_no":1}"#)
        _ = detail
        commands.responses["emby.playbackSeason"] = [.array([firstEpisode, currentEpisode])]
        let queue = try await api.playbackSeason(item: "episode-fixture")
        precondition(queue.map(\.id) == ["first-fixture", "episode-fixture"])
        precondition(commands.calls.last!.0 == "emby.playbackSeason" && commands.calls.last!.1["item_id"] == .string("episode-fixture"))
        commands.failures["emby.playbackSeason"] = CoreFailure(code: "E_NETWORK")
        do { _ = try await api.playbackSeason(item: "episode-fixture"); preconditionFailure("队列失败被当作季末") } catch {}
        commands.failures.removeValue(forKey: "emby.playbackSeason")
        let skips = try json(#"{"intro":{"start":10,"end":30},"outro":null,"skip_auto":false,"skip_source":"手动设定"}"#).decode(PlaybackSkip.self)
        precondition(skips.active(at: 10, duration: 100)?.end == 30)
        precondition(skips.active(at: 30, duration: 100) == nil && skips.active(at: 20, duration: 25) == nil)
        let chapterInfo = try json(#"{"intro":null,"outro":null,"skip_auto":true,"skip_source":"","thumbs":false,"chapters":[{"index":0,"start_secs":0,"name":"开场","image_url":null}]}"#).decode(PlaybackSkip.self)
        precondition(chapterInfo.thumbs == false && chapterInfo.chapters?.first?.name == "开场")
        precondition(!PlaybackSkip.nextUp(at: 89, duration: 100, outro: nil))
        precondition(PlaybackSkip.nextUp(at: 90, duration: 100, outro: nil))
        precondition(!PlaybackSkip.nextUp(at: .nan, duration: 100, outro: nil))
        precondition(!PlaybackSkip.nextUp(at: 100, duration: 0, outro: nil))
        precondition(!PlaybackSkip.nextUp(at: 98, duration: 100, outro: .init(start: 70, end: 90)), "片尾后内容被自动下一集截断")
        precondition(PlaybackSkip.nextUp(at: 70, duration: 100, outro: .init(start: 70, end: 100)))
        let markers = try json(#"{"scope":"season","can_season":true,"intro":{"start":0,"end":30},"outro":null}"#).decode(SkipMarkerSelection.self)
        precondition(markers.scope == "season" && markers.can_season && markers.intro?.end == 30)
        let dmLayout = try json(#"{"res_y":1080,"area_height":540,"lane_height":54,"roll_seconds":8,"fix_seconds":5,"font_size":40,"opacity":1,"bold":false,"revision":2,"items":[{"t":0,"m":1,"l":0,"w":100,"c":16777215,"u":"旧"},{"t":10,"m":1,"l":1,"w":100,"c":16777215,"u":"当前"},{"t":20,"m":5,"l":0,"w":100,"c":16777215,"u":"未来"}]}"#).decode(TVDanmakuLayout.self)
        precondition(dmLayout.candidates(at: 12).map(\.u) == ["当前"])
        precondition(dmLayout.candidates(at: -1).isEmpty && dmLayout.candidates(at: 30).isEmpty)
        let near = UUID(), far = UUID(), wrongSide = UUID()
        let remoteFrames = [TVRemoteFrame(id: near, x: 100, y: 0), TVRemoteFrame(id: far, x: 80, y: 100), TVRemoteFrame(id: wrongSide, x: -100, y: 0)]
        precondition(TVRemoteFrame.next("right", x: 0, y: 0, candidates: remoteFrames) == near)
        precondition(TVRemoteFrame.next("left", x: 0, y: 0, candidates: remoteFrames) == wrongSide)
        precondition(TVRemoteFrame.next("up", x: 0, y: 0, candidates: remoteFrames) == nil)
        let facet = BrowseQuery(studioID: "studio-fixture").arguments(start: 0)
        precondition(facet["studio_ids"] == .array([.string("studio-fixture")]) && facet["studios"] == nil)

        let traditional = TrackCandidate(id: 4, title: "繁体中文", language: "zh", codec: "subrip", external: false, selected: true)
        let simplified = TrackCandidate(id: 8, title: "简体中文", language: "chi", codec: "subrip", external: false, selected: false)
        let stream = try json(#"{"index":8,"type_":"Subtitle","codec":"srt","title":"简体中文","language":"zh","is_external":false}"#).decode(MediaStream.self)
        precondition(TrackPreference.explicit(stream, tracks: [traditional, simplified]) == 8, "明确字幕没有按真实索引和格式匹配")
        let external = try json(#"{"index":1,"type_":"Subtitle","codec":"srt","title":"简体中文","language":"zh","is_external":true}"#).decode(MediaStream.self)
        let one = TrackCandidate(id: 100, title: "简体中文", language: "chi", codec: "subrip", external: true, selected: false)
        let two = TrackCandidate(id: 101, title: "简体中文", language: "chi", codec: "subrip", external: true, selected: false)
        precondition(TrackPreference.explicit(external, tracks: [one]) == 100)
        precondition(TrackPreference.explicit(external, tracks: [one, two]) == nil, "歧义字幕被猜成首轨")

        let audioStream = try json(#"{"index":2,"type_":"Audio","codec":"aac","title":"日语原声","language":"jpn","is_external":false}"#).decode(MediaStream.self)
        let audioTrack = TrackCandidate(id: 9, title: "日语原声", language: "jpn", codec: "aac", external: false, selected: false)
        precondition(TrackPreference.explicit(audioStream, tracks: [audioTrack]) == 9, "音轨预选误用媒体流序号代替引擎ID")

        let old = api.epoch
        let late = Task { try await api.request("held") }
        while commands.held == nil { await Task.yield() }
        api.use(nil); precondition(api.epoch != old)
        commands.held?.resume(returning: .string("旧身份结果")); commands.held = nil
        do { _ = try await late.value; preconditionFailure("旧身份迟到结果未拦截") } catch is CancellationError {}
        do { _ = try await api.items(parent: "library-fixture", start: 0); preconditionFailure("无身份仍能浏览") } catch {}
        let fixtureSession = MediaSession(server: "https://example.invalid", token: "TOKEN_PLACEHOLDER", user_id: "USER_PLACEHOLDER", user_name: "fixture")
        api.use(fixtureSession)
        commands.holdCommand = "emby.setPlayed"
        let oldWrite = Task { try await api.request("emby.setPlayed", ["item_id": .string("episode-fixture"), "played": .bool(true)]) }
        while commands.held == nil { await Task.yield() }
        api.use(fixtureSession)
        commands.held?.resume(returning: .object(["played": .bool(true)])); commands.held = nil
        do { _ = try await oldWrite.value; preconditionFailure("旧会话写入推进新页面代次") } catch is CancellationError {}
        api.userStateChanged(.object(["id": .string(old.uuidString), "scope": .string("all")]))
        precondition(revisions == 1)
        commands.holdCommand = "held"
        var expired = 0; api.onAuthExpired = { expired += 1 }
        let oldAuth = Task { try await api.request("held") }
        while commands.held == nil { await Task.yield() }
        api.use(fixtureSession)
        commands.held?.resume(throwing: CoreFailure(code: "E_AUTH")); commands.held = nil
        do { _ = try await oldAuth.value; preconditionFailure("旧鉴权异常未取消") } catch is CancellationError {}
        precondition(expired == 0, "旧会话异常让新会话退出")
    }
}
