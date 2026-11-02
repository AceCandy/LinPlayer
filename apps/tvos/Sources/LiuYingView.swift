import SwiftUI

@MainActor final class TVLiuYingSession: ObservableObject {
    let api: MediaAPI
    private weak var model: AppModel?
    @Published var page: LiuYingPage?
    @Published var index = 0
    @Published var labels: JSONValue = .null
    @Published var feed = "normal"
    @Published var tab = "dance"
    @Published var channel = ""
    @Published var exposure = ""
    @Published var favorites = false
    @Published var expanded = true
    @Published var autoAdvance = true
    @Published var muted = false
    @Published var busy = false
    @Published var error: String?
    // 能力读取失败与服务器不提供能力是终态说明，与 Android 一致不给重试。
    @Published var unavailable: (title: String, detail: String)?
    private var previous: (String, LiuYingPage)?
    private var seed = UUID().uuidString
    private var work: Task<Void, Never>?
    private var closed = false
    var current: LiuYingItem? { page?.items.indices.contains(index) == true ? page?.items[index] : nil }
    var canGoBack: Bool { index > 0 || previous != nil || (page?.offset ?? 0) > 0 }
    init(api: MediaAPI) { self.api = api }
    func start(model: AppModel) {
        guard api.privateSpace, !closed else { return }
        self.model = model; model.liuying = self
        run {
            let capability: JSONValue
            do { capability = try await self.api.request("emby.extCapabilities") }
            catch is CancellationError { throw CancellationError() }
            catch { self.unavailable = ("流影暂不可用", error.localizedDescription); return }
            guard capability["liuying"]["enabled"].bool == true else {
                self.unavailable = ("服务器未提供流影能力", "请刷新普通首页更新能力，或检查账号权限"); return
            }
            self.unavailable = nil
            self.labels = try await self.api.request("emby.extLiuYingLabels")
            try await self.load(seed: self.seed, offset: 0)
        }
    }
    func query(seed: String, offset: Int, favorite: Bool? = nil) -> [String: JSONValue] {
        ["feed": .string(feed), "tab": .string(tab), "channel": .string(channel), "exposure": .string(exposure),
         "favorite": .bool(favorite ?? favorites), "seed": .string(seed), "offset": .number(Double(offset))]
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy, !closed, api.privateSpace else { return }
        busy = true; error = nil
        work = Task {
            defer { busy = false }
            do { try await action() }
            catch is CancellationError {} catch { if !closed { self.error = error.localizedDescription } }
        }
    }
    private func load(seed: String, offset: Int, last: Bool = false) async throws {
        let result = try await api.request("emby.extLiuYingItems", query(seed: seed, offset: offset)).decode(LiuYingPage.self)
        try Task.checkCancellation()
        guard !closed, api.privateSpace else { return }
        self.seed = seed; page = result; index = last ? max(0, result.items.count - 1) : 0
        await playCurrent()
    }
    private func playCurrent() async {
        guard !closed, api.privateSpace, let model else { return }
        guard let current else {
            if let player = model.playback {
                await player.close()
                if model.playback === player { model.playback = nil }
            }
            return
        }
        if model.playback == nil {
            model.openPlayer(item: current.item_id, title: current.title, version: nil, fromStart: true, speedScope: "liuying")
        } else { await model.switchPlayer(item: current.item_id, title: current.title, fromStart: true) }
        model.playback?.setMuted(muted)
    }
    func move(_ delta: Int) {
        guard delta > 0 || canGoBack else { return }
        run {
            guard let page = self.page else { return }
            if page.items.indices.contains(self.index + delta) {
                self.index += delta; await self.playCurrent(); return
            }
            if delta < 0, let old = self.previous {
                self.seed = old.0; self.page = old.1; self.previous = nil
                self.index = max(0, old.1.items.count - 1); await self.playCurrent(); return
            }
            if delta > 0 { self.previous = (self.seed, page) }
            let seed = delta > 0 && !page.has_more ? UUID().uuidString : self.seed
            let offset = delta < 0 ? max(0, page.offset - 12) : page.has_more ? page.offset + page.items.count : 0
            try await self.load(seed: seed, offset: offset, last: delta < 0)
        }
    }
    func reset(feed: String? = nil, tab: String? = nil, channel: String? = nil, exposure: String? = nil, favorites: Bool? = nil) {
        guard !busy else { return }
        if let feed { self.feed = feed; self.tab = feed == "normal" ? "dance" : ""; self.channel = ""; self.exposure = "" }
        if let tab { self.tab = tab; self.channel = ""; self.exposure = "" }
        if let channel { self.channel = channel; self.exposure = "" }
        if let exposure { self.exposure = exposure }
        if let favorites { self.favorites = favorites }
        previous = nil
        run { try await self.load(seed: UUID().uuidString, offset: 0) }
    }
    func toggleFavorite() {
        guard let item = current else { return }
        run {
            _ = try await self.api.request("emby.setFavorite", ["item_id": .string(item.item_id), "fav": .bool(!item.favorite)])
            self.page?.setFavorite(item.item_id, !item.favorite)
            if var previous = self.previous { previous.1.setFavorite(item.item_id, !item.favorite); self.previous = previous }
        }
    }
    func selectFavorite(_ item: LiuYingItem) {
        run {
            self.previous = nil; self.seed = UUID().uuidString
            self.page = LiuYingPage(items: [item], has_more: false, offset: 0)
            self.index = 0; self.autoAdvance = false; self.favorites = false
            self.channel = ""; self.exposure = ""; self.tab = item.tab; self.feed = item.feed
            await self.playCurrent()
        }
    }
    func toggleMute() { muted.toggle(); model?.playback?.setMuted(muted) }
    func retry() { run { if self.current != nil { await self.playCurrent() } else { try await self.load(seed: self.seed, offset: self.page?.offset ?? 0) } } }
    func stop() async { closed = true; work?.cancel(); await work?.value; page = nil; previous = nil }
}

struct LiuYingView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var session: TVLiuYingSession
    init(api: MediaAPI) { _session = StateObject(wrappedValue: TVLiuYingSession(api: api)) }
    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            TVSectionHeading("流影")
            if session.busy { TVLoading() }
            if let unavailable = session.unavailable { LiuYingEmpty(title: unavailable.title, detail: unavailable.detail) }
            else if let error = session.error { RetryMessage(text: error) { session.start(model: model) } }
            else if !session.busy && session.current == nil { LiuYingEmpty.noItems; LiuYingFilters(session: session) }
            Button("返回") { Task { await model.closePlayer(); dismiss() } }
        }.padding(64).buttonStyle(TVActionStyle())
            .task { session.start(model: model) }
            .onChange(of: model.liuying == nil) { _, value in if value { dismiss() } }
            .onDisappear {
                if model.liuying === session {
                    Task { await session.stop(); if model.liuying === session { model.liuying = nil } }
                }
            }
            .tvExitCommand { Task { await model.closePlayer(); dismiss() } }
    }
}

private struct LiuYingFilters: View {
    @ObservedObject var session: TVLiuYingSession
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            TVChoice(title: "来源", selection: Binding(get: { session.feed }, set: { session.reset(feed: $0) }), options: [("normal", "普通"), ("adult", "成人")])
            if session.feed == "normal" {
                TVChoice(title: "分类", selection: Binding(get: { session.tab }, set: { session.reset(tab: $0) }), options: [("dance", "舞蹈"), ("live", "直播")])
            }
            Toggle("只看收藏", isOn: Binding(get: { session.favorites }, set: { session.reset(favorites: $0) }))
            if session.feed == "normal" && session.tab == "dance" {
                ForEach([("channel", "频道"), ("exposure", "衣着")], id: \.0) { kind, title in
                    TVChoice(title: title, selection: Binding(get: { kind == "channel" ? session.channel : session.exposure }, set: {
                        if kind == "channel" { session.reset(channel: $0) } else { session.reset(exposure: $0) }
                    }), options: [("", "全部")] + session.labels.array.filter { $0["kind"].string == kind }.compactMap { label in
                        guard let name = label["name"].string else { return nil }
                        let title = label["title"].string ?? name
                        return (name, label["count"].number.map { "\(title) · \(Int($0)) 部" } ?? title)
                    })
                }
            }
        }.disabled(session.busy)
    }
}

private struct LiuYingEmpty: View {
    let title: String
    let detail: String
    static let noItems = LiuYingEmpty(title: "暂无可播放的入库资源", detail: "可以切换来源、分类或标签；资料中心内容不会直接播放")
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { Text(title); Text(detail).font(.system(size: 22)).foregroundStyle(.secondary) }
    }
}

struct LiuYingStage: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: TVLiuYingSession
    var body: some View {
        Group {
            if let controller = model.playback {
                LiuYingPlayerView(controller: controller, session: session).id(ObjectIdentifier(controller))
            } else {
                VStack(alignment: .leading, spacing: 28) {
                    TVSectionHeading("流影")
                    if session.busy { TVLoading("正在加载") }
                    else if let error = session.error { RetryMessage(text: error) { session.retry() } }
                    else { LiuYingEmpty.noItems }
                    LiuYingFilters(session: session)
                    Button("上一条") { session.move(-1) }.disabled(!session.canGoBack || session.busy)
                    Button("返回") { Task { await model.closePlayer() } }
                }.padding(64)
            }
        }.buttonStyle(TVActionStyle()).tvExitCommand { Task { await model.closePlayer() } }
    }
}

struct LiuYingPlayerView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var controller: PlaybackController
    @ObservedObject var session: TVLiuYingSession
    @State private var showingFavorites = false
    @State private var preview: Double?
    @FocusState private var progressFocused: Bool
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            if let engine = controller.engine { VideoSurface(engine: engine).ignoresSafeArea() }
            VStack(alignment: .leading, spacing: 22) {
                HStack { Button("退出流影") { Task { await model.closePlayer() } }; Spacer(); Button(session.expanded ? "收起筛选" : "展开筛选") { session.expanded.toggle() } }
                HStack {
                    Spacer()
                    if session.expanded { ScrollView { LiuYingFilters(session: session) }.padding(28).frame(width: 440).background(.black.opacity(0.85)) }
                }
                Spacer()
                if session.busy || controller.loading { TVLoading("正在加载") }
                if let error = session.error { RetryMessage(text: error) { session.retry() } }
                if let error = controller.failure ?? controller.message, !controller.ended { RetryMessage(text: error) { session.retry() } }
                Text(session.current?.title ?? "暂无可播放的入库资源").font(.system(size: 30)).lineLimit(1)
                if let author = session.current?.author { Text(author).foregroundStyle(.secondary) }
                Button { if let preview { self.preview = nil; Task { await controller.seek(to: preview) } } else { controller.toggle() } } label: {
                    ProgressView(value: preview ?? controller.position, total: max(1, controller.engine?.duration ?? 0))
                        .tint(.white).padding(.vertical, 18)
                }.focused($progressFocused).onMoveCommand { direction in
                    guard progressFocused, direction == .left || direction == .right else { return }
                    preview = min(max(0, (preview ?? controller.position) + (direction == .left ? -10 : 10)), controller.engine?.duration ?? 0)
                }.disabled(!controller.canSeek)
                HStack(spacing: 16) {
                    Button("上一条") { session.move(-1) }.disabled(!session.canGoBack || session.busy)
                    Button("下一条") { session.move(1) }.disabled(session.current == nil || session.busy)
                    Button(controller.paused ? "播放" : "暂停") { controller.toggle() }
                    Button(session.muted ? "开启声音" : "静音") { session.toggleMute() }
                    Button(session.current?.favorite == true ? "取消收藏" : "收藏") { session.toggleFavorite() }.disabled(session.busy)
                    Button("收藏列表") { showingFavorites = true }
                    Toggle("自动下一条", isOn: $session.autoAdvance)
                    TVChoice(title: "倍速", selection: Binding(get: { controller.speed }, set: { _ = controller.setSpeed($0) }),
                        options: [Float(0.25), 0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4].filter { $0 <= (controller.engine?.maxSupportedRate ?? 1) }.map { ($0, "\($0)×") })
                        .disabled(!controller.canSetSpeed)
                }
            }.padding(48).background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .center, endPoint: .bottom))
        }.foregroundStyle(.white).preferredColorScheme(.dark).buttonStyle(TVActionStyle(compact: true)).toggleStyle(TVToggleStyle())
            .onAppear {
                model.remotePlayback = { key in
                    if key == "menu" { session.expanded.toggle(); return true }
                    if progressFocused, ["left", "right"].contains(key), controller.canSeek {
                        preview = min(max(0, (preview ?? controller.position) + (key == "left" ? -10 : 10)), controller.engine?.duration ?? 0)
                        return true
                    }
                    return false
                }
            }
            .onDisappear { if model.playback === controller { model.remotePlayback = nil } }
            .task { await controller.start(); controller.setMuted(session.muted) }
            .onPlayPauseCommand { controller.toggle() }
            .tvExitCommand { if preview != nil { preview = nil } else { Task { await model.closePlayer() } } }
            .onChange(of: controller.ended) { _, ended in
                if ended { if session.autoAdvance { session.move(1) } else { Task { await controller.restart() } } }
            }
            .sheet(isPresented: $showingFavorites) { LiuYingFavorites(session: session) }
    }
}

private struct LiuYingFavorites: View {
    @ObservedObject var session: TVLiuYingSession
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [LiuYingItem] = []
    @State private var offset = 0
    @State private var more = true
    @State private var error: String?
    @State private var reload = 0
    @State private var seed = UUID().uuidString
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 24) {
            TVSectionHeading("收藏列表")
            ForEach(rows) { item in Button(item.title) { session.selectFavorite(item); dismiss() } }
            if let error { RetryMessage(text: error) { reload += 1 } }
            if more { Button("加载更多") { reload += 1 } }
            Button("返回") { dismiss() }
        }.padding(64) }.buttonStyle(TVRowStyle())
            .task(id: reload) {
                do {
                    var query = session.query(seed: seed, offset: offset, favorite: true)
                    query["channel"] = .string(""); query["exposure"] = .string("")
                    let page = try await session.api.request("emby.extLiuYingItems", query).decode(LiuYingPage.self)
                    var seen = Set(rows.map(\.item_id)); rows += page.items.filter { seen.insert($0.item_id).inserted }
                    guard !page.has_more || !page.items.isEmpty else { throw CoreFailure(code: "E_PROTOCOL") }
                    offset += page.items.count; more = page.has_more; error = nil
                } catch is CancellationError {} catch { self.error = error.localizedDescription }
            }
    }
}
