import SwiftUI
import UIKit

// 只替换本检查不执行的原生核心初始化和播放器；浏览使用正式页面及MediaAPI。
enum NativeCore {
    static func initialize(dataRoot: URL, version: String) throws -> CoreClient { throw CoreFailure(code: "E_UNSUPPORTED") }
}
struct VisualPlayerEngine {
    struct Track: Identifiable { let id: Int; let name: String }
    struct Chapter: Identifiable { let id: Int; let name: String; let startSeconds: Double }
    let duration: Double = 9960
    let isBuffering = false
    let maxSupportedRate: Float = 2
    let mediaChapters = [Chapter(id: 0, name: "序章", startSeconds: 0)]
    let audioTracks = [Track(id: 1, name: "原声")]
    let activeAudioTrackIndex: Int? = 1
    let activeSubtitleTrackIndex: Int? = nil
}
struct VideoSurface: View {
    let engine: VisualPlayerEngine
    var body: some View {
        ZStack {
            LinearGradient(colors: [.indigo.opacity(0.5), .black], startPoint: .topTrailing, endPoint: .bottomLeading)
            Text("合成播放背景 · 不验证解码").font(.system(size: 36)).foregroundStyle(.white.opacity(0.35))
        }
    }
}
@MainActor final class PlaybackController: ObservableObject {
    let item: String
    let title: String
    let version: String?
    let fromStart: Bool
    var syncWarning: String?
    let engine: VisualPlayerEngine? = VisualPlayerEngine()
    let subtitleText = "这是一条用于检查位置和遮挡的示例字幕。"
    let subtitleNote: String? = nil
    let message: String? = nil
    let loading = false, changingTrack = false, closing = false, ended = false, queueLoading = false, paused = false, audioSelectable = true, canSeek = true
    let queueError: String? = nil
    let versionError: String? = nil
    let nextEpisode: MediaItem? = nil
    let previousEpisode: MediaItem? = nil
    let episodes: [MediaItem] = []
    let versions: [MediaVersion] = []
    let actualVersion = "v1", pictureMode = "fit"
    let speed: Float = 1
    let position: Double = 3600
    let textTracks = [VisualPlayerEngine.Track(id: 2, name: "简体中文")]
    let unsupportedSubtitleCount = 0
    init(api: MediaAPI, item: String, title: String, version: String?, fromStart: Bool, subtitle: InitialSubtitle? = nil) {
        self.item = item; self.title = title; self.version = version; self.fromStart = fromStart
    }
    func suspend() {}
    func close() async {}
    func start() async {}
    func seek(to: Double) async {}
    func toggle() {}
    func loadQueue() {}
    func loadVersions() {}
    func setSpeed(_ rate: Float) {}
    func setPictureMode(_ value: String) {}
    func selectAudio(_ id: Int) {}
    func selectSubtitle(_ id: Int?) {}
}

@MainActor final class VisualCommands: LinPlayerCommands {
    static func item(_ id: String, _ name: String, type: String = "Movie", resume: Double = 0) -> JSONValue {
        .object(["id": .string(id), "name": .string(name), "type_": .string(type), "is_folder": .bool(false),
            "has_primary": .bool(true), "has_backdrop": .bool(true), "runtime_secs": .number(9960), "resume_secs": .number(resume),
            "played": .bool(false), "is_favorite": .bool(false), "year": .number(2024), "rating": .number(8.6), "DoubanRating": .number(8.3)])
    }
    let movies = [item("film1", "沙丘 2"), item("film2", "漫长的旅程"), item("film3", "星河回声"), item("film4", "远方的夏天"), item("film5", "海岸线"), item("film6", "最后一班列车")]
    static let account: JSONValue = .object(["server": .string("fixture"), "name": .string("演示媒体库"), "user_name": .string("演示账号"), "user_id": .string("fixture"), "active": .bool(true), "line_url": .string("https://fixture.invalid"), "aggregate": .bool(true), "aggregate_primary": .bool(true), "lines": .array([.object(["name": .string("默认线路"), "url": .string("https://fixture.invalid")])])])
    func call(_ command: String, _ args: [String: JSONValue]) async throws -> JSONValue {
        switch command {
        case "emby.views": return .array([Self.item("library", "电影", type: "CollectionFolder")])
        case "emby.listLatest", "emby.cachedItems", "emby.similarItems", "emby.search", "emby.personItems": return .array(movies)
        case "emby.listResume": return .array([Self.item("resume", "继续探索", resume: 3600)])
        case "emby.listCollections": return .array([])
        case "emby.seriesSeasons": return .array((1...3).map { .object(["id": .string("season\($0)"), "name": .string("第 \($0) 季"), "child_count": .number(8), "has_primary": .bool(true)]) })
        case "emby.seasonEpisodes": return .object(["items": .array((1...8).map { Self.item("episode\($0)", "第 \($0) 集 · 旅程的起点", type: "Episode") }), "total": .number(8)])
        case "emby.listItemsPage", "emby.listFavorites":
            let values = movies.map { value -> JSONValue in
                guard case .object(var fields) = value else { return value }
                fields["library_ids"] = .array([.string("library")]); return .object(fields)
            }
            return .object(["items": .array(values), "total": .number(Double(values.count)), "has_more": .bool(false)])
        case "emby.getFilters": return .object(["genres": .array([.string("科幻"), .string("冒险")]), "tags": .array([.string("史诗")]), "years": .array([.number(2024)])])
        case "account.listAccounts": return .array([Self.account])
        case "emby.aggregateOverview": return .array([.object(["server_id": .string("fixture"), "server_name": .string("演示媒体库"), "line_url": .string("https://fixture.invalid"), "counts_known": .bool(true), "counts": .object(["movie": .number(128), "series": .number(32), "episode": .number(400), "boxset": .number(6)]), "resume": .array(movies)])])
        case "emby.blockedList": return .array([.object(["id": .string("blocked"), "name": .string("演示屏蔽条目")])])
        case "emby.personDetail": return .object(["name": .string("演示演员"), "overview": .string("用于检查人物简介与相关作品排列的合成资料。"), "has_primary": .bool(true)])
        case "emby.collectionItems": return .object(["movies": .array(movies), "series": .array([]), "others": .array([])])
        case "emby.extRankings": return .array([.object(["id": .string("popular"), "label": .string("热门电影"), "source": .string("fixture"), "enabled": .bool(true)])])
        case "emby.extRankingItems", "emby.extCalendar": return .array((1...5).map { .object(["id": .string("rank\($0)"), "source": .string("fixture"), "external_id": .string("rank\($0)"), "media_type": .string("Movie"), "title": .string("第 \($0) 部演示影片"), "subtitle": .string("2024 · 科幻 / 冒险"), "rank": .number(Double($0)), "rating": .number(8.6)]) })
        case "emby.heroCandidates": return .array([.string("film1"), .string("film2")])
        case "prefs.getHomeSettings": return .object(["collections_enabled": .bool(false)])
        case "prefs.getPrefs", "player.getPlaybackPrefs": return .object(["sub_enabled": .bool(true), "default_speed": .number(1), "search_history": .array([.string("沙丘"), .string("星河"), .string("夏天")])])
        case "prefs.getPrefetchSettings": return .object(["servers": .array([.string("fixture")]), "threads": .number(3), "cache_bytes": .number(268435456), "media_cache_bytes": .number(1073741824)])
        case "prefs.getPrimaryProgressServer": return .object(["server": .string("fixture"), "valid": .bool(true), "pending": .number(2), "conflicts": .number(0)])
        case "account.getCrossServerResume": return .bool(true)
        case "system.cacheSize": return .object(["bytes": .number(134217728)])
        case "emby.itemDetail":
            guard case .object(var fields) = movies[0] else { fatalError() }
            if args["item_id"]?.string == "series" {
                fields["id"] = .string("series"); fields["name"] = .string("漫长的旅程 · 关于星空与远方的一段故事")
                fields["type_"] = .string("Series")
            }
            fields["overview"] = .string("在遥远的沙漠星球，年轻的旅人踏上一段关于责任与选择的旅程。面对未知的未来，他必须找到自己的道路。")
            fields["genres"] = .array([.string("科幻"), .string("冒险")]); fields["tags"] = .array([.string("史诗")])
            fields["premiere_date"] = .string("2024-03-01")
            fields["people"] = .array([])
            return .object(fields)
        case "emby.itemMedia":
            let versions: [JSONValue] = [.object(["id": .string("v1"), "name": .string("2160p 导演剪辑版"), "preferred": .bool(true),
            "container": .string("MKV"), "size_bytes": .number(66_120_000_000), "streams": .array([
                .object(["is_external": .bool(false), "index": .number(0), "type_": .string("Video"), "codec": .string("hevc"), "width": .number(3840), "height": .number(2160)]),
                .object(["is_external": .bool(false), "index": .number(1), "type_": .string("Audio"), "codec": .string("truehd"), "title": .string("日语原声 7.1"), "language": .string("jpn")]),
                .object(["is_external": .bool(false), "index": .number(2), "type_": .string("Subtitle"), "codec": .string("srt"), "title": .string("简体中文"), "language": .string("chi")])])])]
            guard case .object(var alternate) = versions[0] else { fatalError() }
            alternate["id"] = .string("v2"); alternate["name"] = .string("1080p 剧场版"); alternate["preferred"] = .bool(false)
            return .array(versions + [.object(alternate)])
        default: fatalError("视觉夹具缺少命令：" + command)
        }
    }
}

final class VisualImages: URLProtocol {
    static var data = Data()
    private static let lock = NSLock()
    private static var requests: [String: Int] = [:]
    static func count(_ host: String) -> Int { lock.lock(); defer { lock.unlock() }; return requests[host] ?? 0 }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.path == "/img" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let source = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "src" }?.value ?? ""
        let host = URL(string: source)?.host ?? ""
        Self.lock.lock(); Self.requests[host, default: 0] += 1; Self.lock.unlock()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main struct VisualCheck {
    @MainActor static func main() {
        UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(VisualDelegate.self))
    }
}

@MainActor final class VisualDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Visual", sessionRole: session.role)
        configuration.delegateClass = VisualScene.self
        return configuration
    }
}

@MainActor final class VisualScene: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let model: AppModel = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VisualImages.self]
        return AppModel(imageConfiguration: configuration)
    }()
    private var pages: [(String, AnyView)] = []
    private var output: URL!
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        output = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let asset = ImageRenderer(content: ZStack { LinearGradient(colors: [Color(red: 0.32, green: 0.27, blue: 0.12), Color(red: 0.08, green: 0.16, blue: 0.23)], startPoint: .topLeading, endPoint: .bottomTrailing).frame(width: 1920, height: 1080)
            Circle().fill(Color.orange.opacity(0.5)).frame(width: 300, height: 300).offset(x: 450, y: -180)
            Path { path in
                path.move(to: CGPoint(x: 0, y: 900)); path.addLine(to: CGPoint(x: 900, y: 260))
                path.addLine(to: CGPoint(x: 1450, y: 850)); path.addLine(to: CGPoint(x: 1920, y: 440))
                path.addLine(to: CGPoint(x: 1920, y: 1080)); path.addLine(to: CGPoint(x: 0, y: 1080)); path.closeSubpath()
            }.fill(Color(red: 0.23, green: 0.3, blue: 0.37))
            Text("演示封面").font(.system(size: 80, weight: .semibold)).foregroundStyle(.white.opacity(0.6)).offset(y: 220)
        }.frame(width: 1920, height: 1080))
        VisualImages.data = asset.uiImage!.pngData()!
        let core = VisualCommands(), api = MediaAPI(core: VisualCommands())
        api.use(MediaSession(server: "https://fixture.invalid", token: "fixture", user_id: "fixture", user_name: "演示账号"))
        model.api = api; model.ready = true; model.account = try! VisualCommands.account.decode(MediaAccount.self)
        model.images.use(server: "https://fixture.invalid")
        model.images.setChannel(base: "https://fixture.invalid", token: "fixture")
        let movie = try! core.movies[0].decode(MediaItem.self)
        let library = try! VisualCommands.item("library", "电影", type: "CollectionFolder").decode(MediaItem.self)
        var favorite = movie; favorite.library_ids = ["library"]
        precondition(FavoriteGroup.make(items: [favorite], libraries: [library]).map(\.id) == ["library:library"])
        precondition(FavoriteGroup.make(items: [favorite], libraries: []).map(\.id) == ["type:Movie"])
        let unknown = MediaItem.route(id: "unknown", name: "兼容条目", type: "BoxSet")
        let grouped = FavoriteGroup.make(items: [favorite, unknown], libraries: [library])
        precondition(Set(grouped.flatMap(\.items).map(\.id)) == [favorite.id, unknown.id], "分组不能丢失未知类型或无库归属的收藏")
        let series = try! VisualCommands.item("series", "漫长的旅程 · 关于星空与远方的一段故事", type: "Series").decode(MediaItem.self)
        pages = [
            ("player", AnyView(PlayerView(controller: PlaybackController(api: api, item: "film1", title: "沙丘 2", version: nil, fromStart: false)))),
            ("library", AnyView(NavigationStack { LibraryView(api: api, item: library) })),
            ("favorites", AnyView(ProductRoot(api: api, initialTab: "favorites"))),
            ("favorite-category", AnyView(NavigationStack { FavoritesView(api: api, groupID: "library:library", title: "电影") })),
            ("search", AnyView(ProductRoot(api: api, initialTab: "search"))),
            ("servers", AnyView(ProductRoot(api: api, initialTab: "servers"))),
            ("account", AnyView(NavigationStack { AccountEditor(api: api, account: model.account!) })),
            ("aggregate", AnyView(ProductRoot(api: api, initialTab: "aggregate"))),
            ("ranking", AnyView(NavigationStack { DiscoveryView(api: api, calendar: false) })),
            ("calendar", AnyView(NavigationStack { DiscoveryView(api: api, calendar: true) })),
            ("person", AnyView(NavigationStack { PersonView(api: api, item: .route(id: "person", name: "演示演员", type: "Person")) })),
            ("collection", AnyView(NavigationStack { CollectionView(api: api, item: .route(id: "collection", name: "演示合集", type: "BoxSet")) })),
            ("series", AnyView(NavigationStack { DetailView(api: api, item: series) })),
            ("appearance", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .appearance, title: "外观") })),
            ("player-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .player, title: "播放器") })),
            ("prefetch-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .prefetch, title: "多线程加载") })),
            ("progress-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .progress, title: "主进度服") })),
            ("storage-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .storage, title: "存储与数据目录") })),
            ("blocked", AnyView(NavigationStack { BlockedView(api: api) })),
            ("home", AnyView(ProductRoot(api: api))),
            ("detail", AnyView(NavigationStack { DetailView(api: api, item: movie) })),
            ("settings", AnyView(ProductRoot(api: api, initialTab: "settings"))),
            ("settings-light", AnyView(ProductRoot(api: api, initialTab: "settings")))
        ]
        window = UIWindow(windowScene: scene)
        Task { @MainActor in
            let images = model.images
            let first = await images.load(id: "shared", server: "https://one.fixture.invalid")
            let second = await images.load(id: "shared", server: "https://two.fixture.invalid")
            let cached = await images.load(id: "shared", server: "https://one.fixture.invalid")
            precondition(first != nil && second != nil && cached != nil)
            precondition(VisualImages.count("one.fixture.invalid") == 1 && VisualImages.count("two.fixture.invalid") == 1, "跨服同ID不能复用另一服图片")
            images.use(server: "https://fixture.invalid")
            _ = await images.load(id: "shared", server: "https://one.fixture.invalid")
            precondition(VisualImages.count("one.fixture.invalid") == 2, "切身份后必须清理图片缓存")
            let invalid = await images.load(id: "shared", server: "")
            precondition(invalid == nil, "无生效线路不能误用当前服务器")
            showNext()
        }
    }
    private func showNext() {
        guard !pages.isEmpty else {
            try! Data("ok".utf8).write(to: output.appendingPathComponent("done"))
            return
        }
        let (name, page) = pages.removeFirst()
        let light = name.hasSuffix("-light")
        let host = UIHostingController(rootView: page.environmentObject(model)
            .preferredColorScheme(light ? .light : .dark)
            .foregroundStyle(TVPalette.foreground(false)).tint(TVPalette.accent(false))
            .background(TVPalette.background(false)))
        window!.overrideUserInterfaceStyle = light ? .light : .dark
        window!.rootViewController = host; window!.makeKeyAndVisible()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            self.capture(name)
            if ["series", "detail", "player-settings"].contains(name), let scroll = self.verticalScroll(in: host.view) {
                scroll.setContentOffset(CGPoint(x: 0, y: min(850, max(0, scroll.contentSize.height - scroll.bounds.height))), animated: false)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    self.capture(name + "-lower"); self.showNext()
                }
            } else { self.showNext() }
        }
    }
    private func verticalScroll(in view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView, scroll.contentSize.height > scroll.bounds.height + 100 { return scroll }
        return view.subviews.lazy.compactMap { self.verticalScroll(in: $0) }.first
    }
    private func capture(_ name: String) {
        window!.layoutIfNeeded()
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let data = UIGraphicsImageRenderer(size: window!.bounds.size, format: format).pngData { _ in
            precondition(window!.drawHierarchy(in: window!.bounds, afterScreenUpdates: true), "页面绘制失败")
        }
        try! data.write(to: output.appendingPathComponent(name + ".png"))
    }
}
