import SwiftUI
import UIKit

@MainActor final class TVDanmakuSession: ObservableObject {
    let api: MediaAPI
    let item: String
    @Published var enabled = false
    @Published var style: JSONValue = .null
    @Published var layout: TVDanmakuLayout?
    @Published var source = ""
    @Published var error: String?
    @Published var busy = false
    @Published var offset: Double = 0
    @Published var heatmap: [Double] = []
    private var work: Task<Void, Never>?
    private var layoutWork: Task<Void, Never>?
    private var saveWork: Task<Void, Never>?
    private var revision = UUID()
    private var generation: Double?
    private var viewport = CGSize.zero
    private var closed = false
    private var duration: Double = 0
    private var position: Double = 0
    init(api: MediaAPI, item: String) { self.api = api; self.item = item }

    func resolve(selection: JSONValue? = nil, ignoreMemory: Bool = false) {
        guard !closed else { return }
        let previous = work
        previous?.cancel(); layoutWork?.cancel()
        let request = UUID(); revision = request
        layout = nil; heatmap = []; busy = true; error = nil; offset = 0
        work = Task {
            defer { if revision == request { busy = false } }
            do {
                await previous?.value
                try Task.checkCancellation()
                // begin 必须收到核心回执后才允许下一代开始，取消不能提前释放顺序屏障。
                let beginWork = Task { try await self.api.request("player.danmakuBegin") }
                let begin = try await beginWork.value
                guard let generation = begin["generation"].number else { throw CoreFailure(code: "E_PROTOCOL") }
                self.generation = generation
                try Task.checkCancellation()
                let prefs = try await api.request("prefs.getPrefs")
                enabled = prefs["danmaku_enabled"].bool == true
                style = try await api.request("player.getDanmakuStyle")
                let input = try await matchInput()
                var args: [String: JSONValue] = ["input": input, "ignore_memory": .bool(ignoreMemory)]
                if let selection { args["selection"] = selection }
                let result = try await api.request("danmaku.resolve", args)
                try Task.checkCancellation()
                guard !closed, revision == request else { return }
                _ = try await api.request("player.danmakuSet", ["generation": .number(generation), "items": result["items"]])
                try Task.checkCancellation()
                guard !closed, revision == request else { return }
                source = ["source_name", "anime_title", "episode_title"].compactMap { result["match"][$0].string }.joined(separator: " · ")
                if source.isEmpty { source = "未匹配到弹幕，可手动搜索" }
                if !result["warnings"].array.isEmpty { source += " · 部分来源加载失败" }
                if selection != nil {
                    _ = try await api.request("player.setDanmakuEnabled", ["enabled": .bool(true)])
                    enabled = true
                }
                reloadLayout()
            } catch is CancellationError {} catch { if !closed, revision == request { self.error = error.localizedDescription } }
        }
    }
    private func matchInput() async throws -> JSONValue {
        let detail = try await api.request("emby.itemDetail", ["item_id": .string(item)])
        var input: [String: JSONValue] = ["item_id": .string(item), "title": detail["series_name"].string.map(JSONValue.string) ?? detail["name"]]
        for key in ["series_id", "season_id", "episode_no", "season_no", "genres", "bgm_id"] {
            if detail[key] != .null { input[key] = detail[key] }
        }
        return .object(input)
    }
    func configure(size: CGSize, position: Double, duration: Double) {
        self.position = position; self.duration = duration
        if viewport != size { viewport = size; reloadLayout() }
    }
    private func reloadLayout() {
        guard !closed, generation != nil, viewport.width > 0, viewport.height > 0 else { return }
        layoutWork?.cancel()
        let request = revision, size = viewport, firstUntil = max(0, position + 30)
        layoutWork = Task {
            do {
                for until in [Optional(firstUntil), nil] {
                    var args: [String: JSONValue] = ["prepare": .bool(true)]
                    if let until { args["until"] = .number(until) }
                    let prepared = try await api.request("player.danmakuLayout", args).decode(TVDanmakuLayout.self)
                    let sy = size.height / prepared.res_y
                    guard sy.isFinite, sy > 0 else { throw CoreFailure(code: "E_PROTOCOL") }
                    let font = UIFont.systemFont(ofSize: prepared.font_size * sy, weight: prepared.bold ? .bold : .regular)
                    var widths: [JSONValue] = []
                    for (index, line) in prepared.items.enumerated() {
                        if index % 256 == 0 { await Task.yield(); try Task.checkCancellation() }
                        widths.append(.number(Double((line.u as NSString).size(withAttributes: [.font: font]).width / sy)))
                    }
                    args = ["revision": .number(prepared.revision), "viewport_width": .number(size.width / sy), "widths": .array(widths)]
                    if let until { args["until"] = .number(until) }
                    let measured = try await api.request("player.danmakuLayout", args).decode(TVDanmakuLayout.self)
                    try Task.checkCancellation()
                    guard !closed, revision == request else { return }
                    layout = measured
                }
                let values = try await api.request("player.danmakuHeatmap", ["buckets": .number(120), "duration": .number(duration)]).decode([Double].self)
                try Task.checkCancellation()
                if !closed, revision == request { heatmap = values }
            } catch is CancellationError {} catch { if !closed, revision == request { self.error = error.localizedDescription } }
        }
    }
    func change(_ command: String, _ args: [String: JSONValue]) {
        guard saveWork == nil, !closed else { return }
        busy = true; error = nil
        saveWork = Task {
            defer { busy = false; saveWork = nil }
            do {
                let result = try await api.request(command, args)
                guard !closed else { return }
                switch command {
                case "player.setDanmakuEnabled": enabled = args["enabled"]?.bool == true
                case "player.danmakuOffset": offset = result["seconds"].number ?? 0; reloadLayout()
                case "player.setDanmakuStyle":
                    style = result
                    if args.keys.contains(where: { $0 != "opacity" && $0 != "heatmap" }) { reloadLayout() }
                default: break
                }
            } catch is CancellationError {} catch { if !closed { self.error = error.localizedDescription } }
        }
    }
    func forget() async {
        do { _ = try await api.request("danmaku.forget", ["input": try await matchInput()]) }
        catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
    func close() async {
        closed = true; revision = UUID(); work?.cancel(); layoutWork?.cancel()
        await work?.value; await layoutWork?.value; await saveWork?.value
        layout = nil; heatmap = []
        if let generation { _ = try? await api.request("player.danmakuSet", ["generation": .number(generation), "items": .array([])]) }
    }
}

struct TVDanmakuOverlay: View {
    @ObservedObject var session: TVDanmakuSession
    let position: () -> Double
    let duration: Double
    let paused: Bool
    var body: some View {
        GeometryReader { geometry in
            TimelineView(.animation(minimumInterval: 1 / 60, paused: paused || !session.enabled)) { _ in
                Canvas { context, size in
                    guard session.enabled, let layout = session.layout else { return }
                    let now = position(), sy = size.height / layout.res_y
                    context.opacity = session.style["opacity"].number ?? layout.opacity
                    context.addFilter(.shadow(color: .black, radius: 1, x: 1, y: 1))
                    for line in layout.candidates(at: now) {
                        let age = now - line.t, lifespan = line.m == 1 ? layout.roll_seconds : layout.fix_seconds
                        if age >= lifespan || line.l < 0 { continue }
                        let width = line.w * sy
                        let x = line.m == 1 ? size.width - age / lifespan * (size.width + width) : (size.width - width) / 2
                        let y = line.m == 4 ? layout.area_height * sy - 4 * sy - Double(line.l + 1) * layout.lane_height * sy
                            : 4 * sy + Double(line.l) * layout.lane_height * sy
                        let color = Color(red: Double((line.c >> 16) & 255) / 255, green: Double((line.c >> 8) & 255) / 255, blue: Double(line.c & 255) / 255)
                        context.draw(Text(line.u).font(.system(size: layout.font_size * sy, weight: layout.bold ? .bold : .regular)).foregroundColor(color), at: CGPoint(x: x, y: y), anchor: .topLeading)
                    }
                }
            }.task(id: "\(geometry.size):\(duration)") { session.configure(size: geometry.size, position: position(), duration: duration) }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}

struct TVDanmakuPanel: View {
    @ObservedObject var session: TVDanmakuSession
    // 与 Android 一致：搜索框预填播放标题「 · 」之前的部分（分集即剧名）。
    var title = ""
    @State private var query = ""
    @State private var results: JSONValue = .null
    @State private var episodes: JSONValue = .null
    @State private var anime: JSONValue = .null
    @State private var sourceID = ""
    @State private var searching = false
    @State private var searchWork: Task<Void, Never>?
    @State private var episodesWork: Task<Void, Never>?
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Toggle("显示弹幕", isOn: Binding(get: { session.enabled }, set: { session.change("player.setDanmakuEnabled", ["enabled": .bool($0)]) }))
            Text(session.source).font(.system(size: 22)).foregroundStyle(.secondary)
            if let error = session.error { Text(error).foregroundStyle(.orange) }
            Button("重新匹配") { session.resolve(ignoreMemory: true) }
            Button("清除匹配记忆") { Task { await session.forget() } }
            TVTextInput("搜索弹幕", text: $query, onSubmit: search).onAppear {
                if query.isEmpty { query = title.components(separatedBy: " · ").first ?? "" }
            }
            Button("搜索") { search() }.disabled(searching || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            ForEach(Array(results.array.enumerated()), id: \.offset) { _, source in
                Text(source["source_name"].string ?? "弹幕源")
                if let error = source["error"].string, !error.isEmpty { Text(error).foregroundStyle(.orange) }
                ForEach(Array(source["animes"].array.enumerated()), id: \.offset) { _, entry in
                    Button(entry["anime_title"].string ?? "未命名") {
                        sourceID = source["source_id"].string ?? ""; anime = entry; episodes = .null
                        episodesWork?.cancel()
                        let source = sourceID
                        episodesWork = Task {
                            do {
                                let values = try await session.api.request("danmaku.episodes", ["source_id": .string(source), "anime_id": entry["anime_id"]])
                                try Task.checkCancellation()
                                episodes = values
                            } catch is CancellationError {} catch { session.error = error.localizedDescription }
                        }
                    }
                }
            }
            ForEach(Array(episodes.array.enumerated()), id: \.offset) { _, entry in
                Button(entry["episode_title"].string ?? "未命名分集") {
                    session.resolve(selection: .object(["source_id": .string(sourceID), "anime_id": anime["anime_id"], "anime_title": anime["anime_title"],
                        "episode_id": entry["episode_id"], "episode_title": entry["episode_title"]]))
                    results = .null; episodes = .null
                }
            }
            HStack {
                Button("提前 0.5 秒") { session.change("player.danmakuOffset", ["seconds": .number(max(-600, session.offset - 0.5))]) }
                Button("延后 0.5 秒") { session.change("player.danmakuOffset", ["seconds": .number(min(600, session.offset + 0.5))]) }
            }
            Text(String(format: "延迟 %.1f 秒", session.offset))
            TVChoice(title: "显示区域", selection: numberBinding("area", fallback: 1), options: [(0.25, "1/4 屏"), (0.5, "1/2 屏"), (0.75, "3/4 屏"), (1, "全屏")])
            ForEach([("scale", "字号"), ("opacity", "透明度"), ("speed", "滚动速度")], id: \.0) { key, title in
                TVChoice(title: title, selection: numberBinding(key, fallback: 1), options: [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2, 3].filter { key != "opacity" || $0 <= 1 }.map { ($0, String(format: "%.2g", $0)) })
            }
            ForEach([("roll_lines", "滚动行数"), ("top_lines", "顶部行数"), ("bottom_lines", "底部行数")], id: \.0) { key, title in
                TVChoice(title: title, selection: numberBinding(key, fallback: 20), options: [0.0, 1, 3, 5, 10, 20].map { ($0, String(Int($0))) })
            }
            ForEach([("prevent_overlap", "避免重叠"), ("merge", "合并重复弹幕"), ("bold", "粗体"), ("heatmap", "进度热力图")], id: \.0) { key, title in
                Toggle(title, isOn: Binding(get: { session.style[key].bool ?? false }, set: { session.change("player.setDanmakuStyle", [key: .bool($0)]) }))
            }
        }.disabled(session.busy)
            .onDisappear { searchWork?.cancel(); episodesWork?.cancel() }
    }
    private func numberBinding(_ key: String, fallback: Double) -> Binding<Double> {
        Binding(get: { session.style[key].number ?? fallback }, set: { value in
            var args: [String: JSONValue] = [key: .number(value)]
            if key == "area" { for key in ["roll_lines", "top_lines", "bottom_lines"] { args[key] = .number(20) } }
            session.change("player.setDanmakuStyle", args)
        })
    }
    private func search() {
        guard !searching else { return }; searching = true
        episodesWork?.cancel(); episodes = .null; anime = .null; sourceID = ""
        searchWork = Task {
            defer { searching = false }
            do { results = try await session.api.request("danmaku.search", ["keyword": .string(query)]); episodes = .null }
            catch is CancellationError {} catch { session.error = error.localizedDescription }
        }
    }
}

struct TVDanmakuHeatmap: View {
    @ObservedObject var session: TVDanmakuSession
    var body: some View {
        Canvas { context, size in
            guard session.enabled, session.style["heatmap"].bool == true, !session.heatmap.isEmpty else { return }
            let width = size.width / CGFloat(session.heatmap.count)
            for (index, density) in session.heatmap.enumerated() {
                let height = size.height * min(1, max(0, density))
                context.fill(Path(CGRect(x: CGFloat(index) * width, y: size.height - height, width: width, height: height)), with: .color(.white.opacity(0.35)))
            }
        }.allowsHitTesting(false)
    }
}

struct TVDanmakuSettings: View {
    let api: MediaAPI
    @State private var sources: [JSONValue] = []
    @State private var official: JSONValue = .null
    @State private var error: String?
    @State private var reload = 0
    @State private var editing: JSONValue?
    @State private var words = ""
    @State private var users: JSONValue = .array([])
    @State private var saving = false
    @State private var loaded = false
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 24) {
            TVSectionHeading("弹幕")
            ForEach(Array(sources.enumerated()), id: \.offset) { _, source in
                Button { editing = source } label: {
                    HStack { Text(source["name"].string ?? "弹幕源"); Spacer(); Text(source["disabled"].bool == true ? "已停用" : "已启用").foregroundStyle(.secondary) }
                }
            }
            Button("添加弹幕源") { editing = .object(["id": .string(UUID().uuidString), "auth_type": .string("none")]) }
            if official != .null {
                VStack(alignment: .leading, spacing: 6) {
                    HStack { Text("弹弹Play 官方源"); Spacer(); Text(official["enabled"].bool == true ? "可用" : "不可用").foregroundStyle(.secondary) }
                    if official["enabled"].bool != true, let reason = official["reason"].string, !reason.isEmpty {
                        Text(reason).font(.system(size: 22)).foregroundStyle(.secondary)
                    }
                }
            }
            TVTextInput("屏蔽词（每行一个）", text: $words).disabled(!loaded)
            Button("保存屏蔽词") {
                saving = true
                Task { defer { saving = false }
                    do {
                        let values = words.split(whereSeparator: \.isNewline).map { JSONValue.string(String($0).trimmingCharacters(in: .whitespaces)) }
                        _ = try await api.request("danmaku.setBlockwords", ["words": .array(values), "users": users]); error = nil
                    } catch { self.error = error.localizedDescription }
                }
            }.disabled(saving || !loaded)
            // shortcut: tvOS 无文件选择器，只显示屏蔽用户数，导入弹弹Play 屏蔽表需在其它端完成。
            if loaded { HStack { Text("屏蔽用户"); Spacer(); Text("\(users.array.count) 个").foregroundStyle(.secondary) } }
            if let error { RetryMessage(text: error) { reload += 1 } }
        }.padding(64) }.buttonStyle(TVRowStyle())
            .task(id: reload) {
                loaded = false
                do {
                    sources = try await api.request("danmaku.getDanmakuConfig").array
                    official = try await api.request("danmaku.getOfficialDanmaku")
                    let blocked = try await api.request("danmaku.getBlockwords")
                    words = blocked["words"].array.compactMap(\.string).joined(separator: "\n"); users = blocked["users"]; loaded = true
                    error = nil
                } catch is CancellationError {} catch { self.error = error.localizedDescription }
            }
            .sheet(isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil; reload += 1 } })) {
                if let editing { TVDanmakuSourceEditor(api: api, original: editing).id(editing["id"].string ?? "") }
            }
    }
}

private struct TVDanmakuSourceEditor: View {
    let api: MediaAPI
    let original: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var auth = "none"
    @State private var token = ""
    @State private var appID = ""
    @State private var secret = ""
    @State private var disabled = false
    @State private var working = false
    @State private var error: String?
    @State private var tested = false
    @State private var deleting = false
    private var source: JSONValue {
        guard case .object(var source) = original else { return .null }
        source["name"] = .string(name); source["api_url"] = .string(url); source["auth_type"] = .string(auth)
        source["token"] = .string(token); source["app_id"] = .string(appID); source["app_secret"] = .string(secret); source["disabled"] = .bool(disabled)
        return .object(source)
    }
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 24) {
            TVSectionHeading("弹幕源")
            if original["official"].bool != true {
                TVTextInput("名称", text: $name)
                TVTextInput("接口地址", text: $url, contentType: .URL)
                TVChoice(title: "认证", selection: $auth, options: [("none", "无认证"), ("pathToken", "路径 Token"), ("headerToken", "请求头 Token"), ("queryToken", "查询参数 Token"), ("dandanplaySignature", "App ID 与密钥")])
                if ["pathToken", "headerToken", "queryToken"].contains(auth) { TVTextInput("Token", text: $token, secure: true) }
                if auth == "dandanplaySignature" { TVTextInput("App ID", text: $appID); TVTextInput("密钥", text: $secret, secure: true) }
            }
            Toggle("停用此源", isOn: $disabled).toggleStyle(TVToggleStyle())
            Button("测试连接") { perform { _ = try await api.request("danmaku.testSource", ["source": source]); tested = true } }
            if tested { Text("连接测试通过") }
            Button("保存") { save(removing: false) }
            if original["official"].bool != true { Button("删除", role: .destructive) { deleting = true } }
            if let error { Text(error).foregroundStyle(.orange) }
            Button("返回") { dismiss() }
        }.padding(64).disabled(working) }.buttonStyle(TVActionStyle())
            .task {
                name = original["name"].string ?? ""; url = original["api_url"].string ?? ""; auth = original["auth_type"].string ?? "none"
                token = original["token"].string ?? ""; appID = original["app_id"].string ?? ""; secret = original["app_secret"].string ?? ""; disabled = original["disabled"].bool == true
            }
            .onChange(of: source) { _, _ in tested = false }
            .confirmationDialog("删除此弹幕源？", isPresented: $deleting) { Button("删除", role: .destructive) { save(removing: true) } }
            .tvExitCommand { dismiss() }
    }
    private func perform(_ action: @escaping () async throws -> Void) {
        guard !working else { return }; working = true
        Task { defer { working = false }; do { try await action(); error = nil } catch { self.error = error.localizedDescription } }
    }
    private func save(removing: Bool) {
        perform {
            var sources = try await api.request("danmaku.getDanmakuConfig").array
            let index = sources.firstIndex { $0["id"] == original["id"] }
            if removing { if let index { sources.remove(at: index) } }
            else if let index { sources[index] = source }
            else { sources.append(source) }
            _ = try await api.request("danmaku.setDanmakuConfig", ["sources": .array(sources)])
            secret = ""; token = ""; dismiss()
        }
    }
}
