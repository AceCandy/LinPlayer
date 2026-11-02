import SwiftUI
import UIKit
import Lottie

// 只替换本检查不执行的原生核心初始化和播放器；浏览使用正式页面及MediaAPI。
enum NativeCore {
    static func initialize(dataRoot: URL, version: String) throws -> CoreClient { throw CoreFailure(code: "E_UNSUPPORTED") }
}
final class VisualPlayerEngine {
    struct Track: Identifiable { let id: Int; let name: String; var codec = "aac"; var language: String? = "jpn"; var isExternal = false }
    struct Chapter: Identifiable { let id: Int; let name: String; let startSeconds: Double }
    let currentTime: Double = 1517
    let duration: Double = 2729
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
        Image(uiImage: UIImage(data: try! Data(contentsOf: VisualCatalog.directory.appendingPathComponent("visual-1-Backdrop.png")))!)
            .resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity).background(.black)
    }
}
@MainActor final class PlaybackController: ObservableObject {
    let danmaku: TVDanmakuSession
    let item: String
    let title: String
    let version: String?
    let fromStart: Bool
    let speedScope: String
    var syncWarning: String?
    let engine: VisualPlayerEngine? = VisualPlayerEngine()
    let subtitleText: String
    let subtitleNote: String? = nil
    let subtitleStyle: JSONValue = .null
    let subtitleStyleError: String? = nil
    let canChangeSubtitleStyle = false
    func loadSubtitleStyle() {}
    func setSubtitleStyle(_ key: String, _ value: JSONValue) {}
    let message: String? = nil
    let failure: String? = nil
    let autoRetried = false
    let loading = false, changingTrack = false, closing = false, ended = false, queueLoading = false, paused = false, audioSelectable = true, canSeek = true
    let queueError: String? = nil
    let versionError: String? = nil
    let nextEpisode: MediaItem? = nil
    let previousEpisode: MediaItem? = nil
    let episodes: [MediaItem] = []
    let versions: [MediaVersion] = []
    let actualVersion = "v1", pictureMode = "fit"
    let speed: Float = 1
    let canSetSpeed = true
    let bufferedPosition: Double = 2050
    let position: Double = 1517
    let textTracks = [VisualPlayerEngine.Track(id: 2, name: "简体中文")]
    let unsupportedSubtitleCount = 0
    let skipInfo: PlaybackSkip?
    let skipError: String? = nil
    var activeSkip: PlaybackSkip.Range? { skipInfo?.active(at: position, duration: engine?.duration ?? 0) }
    let markers: SkipMarkerSelection? = nil
    let markerBusy = false
    let markerError: String? = nil
    func loadMarkers(scope: String? = nil) {}
    func mark(_ action: String) {}
    let skipIntroEnabled: Bool? = true, skipOutroEnabled: Bool? = false
    let initialAudio: InitialAudio?
    let initialSubtitle: InitialSubtitle?
    func loadSkipPrefs() {}
    func setSkipPref(_ key: String, _ value: Bool) {}
    func retrySkip() {}
    func skip(_ range: PlaybackSkip.Range) {}
    init(api: MediaAPI, item: String, title: String, version: String?, fromStart: Bool, subtitle: InitialSubtitle? = nil, audio: InitialAudio? = nil, speedScope: String = "normal", trackMemory: SessionTrackMemory = SessionTrackMemory(), showSkip: Bool = false) {
        self.speedScope = speedScope
        initialSubtitle = subtitle; initialAudio = audio
        danmaku = TVDanmakuSession(api: api, item: item)
        self.item = item; self.title = title; self.version = version; self.fromStart = fromStart
        subtitleText = showSkip ? "这是一条用于检查位置和遮挡的示例字幕。" : ""
        skipInfo = showSkip ? PlaybackSkip(intro: .init(start: 1500, end: 1600), outro: nil, skip_auto: false, skip_source: "合成区间") : nil
    }
    func setMuted(_ value: Bool) {}
    func restart() async {}
    func suspend() {}
    func close() async {}
    func start() async {}
    func seek(to: Double) async {}
    @discardableResult func toggle() -> Bool { true }
    func loadQueue() {}
    func loadVersions() {}
    func setSpeed(_ rate: Float) -> Bool { true }
    @Published var holdRate: Float?
    func beginHoldSpeed(_ rate: Float) {}
    func endHoldSpeed() {}
    func setPictureMode(_ value: String) {}
    func selectAudio(_ id: Int) {}
    func selectSubtitle(_ id: Int?) {}
}

enum VisualCatalog {
    static let directory = Bundle.main.resourceURL!.appendingPathComponent("VisualFixtures")
    static let data = try! JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: directory.appendingPathComponent("catalog.json")))
    static let rows = Dictionary(uniqueKeysWithValues: (data["items"].array + data["details"].array + data["libraries"].array).map { value in
        (value["id"].string!, item(value))
    })
    // 共享资料省略的传输字段在测试适配层补齐，不放宽正式 DTO 的校验。
    static func item(_ value: JSONValue) -> JSONValue {
        guard case .object(var fields) = value else { fatalError("视觉资料不是对象") }
        fields["is_folder"] = .bool(["CollectionFolder", "Season", "Series"].contains(value["type_"].string ?? ""))
        fields["runtime_secs"] = fields["runtime_secs"] ?? .number(0)
        fields["resume_secs"] = fields["resume_secs"] ?? .number(0)
        return .object(fields)
    }
    static func selected(_ key: String) -> [JSONValue] {
        data[key].array.map { rows[$0.string!]! }
    }
    static var media: JSONValue {
        .array(data["media"].array.map { version in
            guard case .object(var fields) = version else { fatalError("视觉版本不是对象") }
            fields["streams"] = .array(version["streams"].array.map { stream in
                guard case .object(var fields) = stream else { fatalError("视觉轨道不是对象") }
                fields["is_external"] = fields["is_external"] ?? .bool(false)
                return .object(fields)
            })
            return .object(fields)
        })
    }
    static func verify() {
        precondition(data["version"].number == 1)
        _ = try! JSONValue.array(Array(rows.values)).decode([MediaItem].self)
        _ = try! JSONValue.array(data["items"].array + data["details"].array).decode([MediaDetail].self)
        _ = try! JSONValue.array(selected("seasons")).decode([MediaSeason].self)
        _ = try! media.decode([MediaVersion].self)
        for key in ["hero", "resume", "latest", "episodes"] { precondition(!selected(key).isEmpty) }
        for row in rows.values {
            let art = row["art_id"].string ?? row["id"].string!
            for (flag, kind) in [("has_primary", "Primary"), ("has_backdrop", "Backdrop")] where row[flag].bool == true {
                let url = directory.appendingPathComponent("\(art)-\(kind).png")
                // 媒体库与其它端一样复用第一张样本图。
                if row["type_"].string != "CollectionFolder" { precondition(UIImage(data: try! Data(contentsOf: url)) != nil) }
            }
        }
    }
}

@MainActor final class VisualCommands: LinPlayerCommands {
    static func item(_ id: String, _ name: String, type: String = "Movie", resume: Double = 0) -> JSONValue {
        .object(["id": .string(id), "name": .string(name), "type_": .string(type), "is_folder": .bool(false),
            "has_primary": .bool(true), "has_backdrop": .bool(true), "has_logo": .bool(id == "film1"), "genres": .array([.string("科幻")]), "runtime_secs": .number(9960), "resume_secs": .number(resume),
            "played": .bool(false), "is_favorite": .bool(false), "year": .number(2024), "rating": .number(8.6), "DoubanRating": .number(8.3)])
    }
    let movies = VisualCatalog.selected("latest")
    static let account: JSONValue = .object(["server": .string("fixture"), "name": VisualCatalog.data["server_name"], "user_name": .string("演示账号"), "user_id": .string("fixture"), "active": .bool(true), "line_url": .string("https://fixture.invalid"), "aggregate": .bool(true), "aggregate_primary": .bool(true), "lines": .array([.object(["name": .string("默认线路"), "url": .string("https://fixture.invalid")])])])
    func call(_ command: String, _ args: [String: JSONValue]) async throws -> JSONValue {
        switch command {
        case "emby.views": return .array(VisualCatalog.data["libraries"].array.map(VisualCatalog.item))
        case "emby.listLatest", "emby.search", "emby.personItems": return .array(movies)
        case "emby.cachedItems": return .array(VisualCatalog.selected("hero"))
        case "emby.similarItems": return .array(Array(movies.dropFirst()))
        case "emby.listResume": return .array(VisualCatalog.selected("resume"))
        case "emby.listCollections": return .array([])
        case "emby.seriesPlaybackTarget": return VisualCatalog.selected("episodes")[1]
        case "emby.seriesSeasons": return .array(VisualCatalog.selected("seasons"))
        case "emby.seasonEpisodes":
            let episodes = VisualCatalog.selected("episodes")
            return .object(["items": .array(args["start_index"]?.number == 0 ? episodes : []), "total": .number(Double(episodes.count))])
        case "emby.listItemsPage", "emby.listFavorites":
            let values = movies.map { value -> JSONValue in
                guard case .object(var fields) = value else { return value }
                fields["library_ids"] = .array([.string("visual-library")]); return .object(fields)
            }
            return .object(["items": .array(values), "total": .number(Double(values.count)), "next_index": .number(Double(values.count)), "has_more": .bool(false)])
        case "emby.personItemsPage":
            let start = Int(args["start_index"]?.number ?? 0)
            let type = args["type"]?.string ?? "Movie"
            let values = (start..<min(start + 30, 65)).map { Self.item("\(type)-\($0)", "作品 \($0 + 1)", type: type) }
            return .object(["items": .array(values), "total": .number(65), "next_index": .number(Double(start + values.count)), "has_more": .bool(start + values.count < 65)])
        case "account.probeLine": return .object(["ms": .number(12)])
        case "account.listAccounts": return .array([Self.account])
        case "emby.aggregateOverview": return .array([.object(["server_id": .string("fixture"), "server_name": .string("演示媒体库"), "line_url": .string("https://fixture.invalid"), "counts_known": .bool(true), "counts": .object(["movie": .number(128), "series": .number(32), "episode": .number(400), "boxset": .number(6)]), "resume": .array(movies)])])
        case "emby.blockedList": return .array([.object(["id": .string("blocked"), "name": .string("演示屏蔽条目")])])
        case "emby.personDetail": return .object(["name": .string("演示演员"), "overview": .string("用于检查人物简介与相关作品排列的合成资料。"), "has_primary": .bool(true)])
        case "emby.collectionItems": return .object(["movies": .array(movies), "series": .array([]), "others": .array([])])
        case "emby.extRankings": return .array([.object(["id": .string("popular"), "label": .string("热门电影"), "source": .string("fixture"), "enabled": .bool(true)])])
        case "emby.extRankingItems", "emby.extCalendar":
            let page = command == "emby.extCalendar" ? 1 : Int(args["page"]?.number ?? 1)
            guard page <= 2 else { return .array([]) }
            let start = (page - 1) * 5 + 1
            return .array((start..<(start + 5)).map { .object(["id": .string("rank\($0)"), "source": .string("fixture"), "external_id": .string("rank\($0)"), "media_type": .string("Movie"), "title": .string("第 \($0) 部演示影片"), "subtitle": .string("2024 · 科幻 / 冒险"), "rank": .number(Double($0)), "rating": .number(8.6), "image_url": .string("https://fixture.invalid/poster"), "weekday": .number(Double(($0 - 1) % 7 + 1)), "broadcast_time": .string("20:30"), "timezone": .string("Asia/Tokyo")]) })
        case "emby.heroCandidates": return VisualCatalog.data["hero"]
        case "prefs.getHomeSettings": return .object(["collections_enabled": .bool(false)])
        case "prefs.getPrefs": return .object([:])
        case "prefs.getUpdateSettings": return .object(["channel": .string("stable"), "auto_check": .bool(false), "proxy": .string("")])
        case "player.getPlaybackPrefs": return .object(["skip_intro": .bool(false), "skip_outro": .bool(false), "skip_auto": .bool(false)])
        case "prefs.getPrefetchSettings": return .object(["servers": .array([.string("fixture")]), "threads": .number(3), "cache_bytes": .number(268435456), "media_cache_bytes": .number(1073741824)])
        case "prefs.getPrimaryProgressServer": return .object(["server": .string("fixture"), "valid": .bool(true), "pending": .number(2), "conflicts": .number(0)])
        case "system.cacheSize": return .object(["bytes": .number(134217728)])
        case "emby.itemDetail": return VisualCatalog.rows[args["item_id"]!.string!]!
        case "emby.itemMedia", "prefs.preloadItem": return VisualCatalog.media
        case "player.setPlaybackPrefs", "prefs.setPrefetchSettings", "prefs.setUpdateSettings": return .object([:])
        case "account.testConnection": return .object(["name": .string("演示媒体库"), "version": .string("1.0")])
        default: fatalError("视觉夹具缺少命令：" + command)
        }
    }
}

final class VisualImages: URLProtocol {
    private static let lock = NSLock()
    private static var requests: [String: Int] = [:]
    static func count(_ host: String) -> Int { lock.lock(); defer { lock.unlock() }; return requests[host] ?? 0 }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.path == "/img" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let source = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "src" }?.value ?? ""
        let host = URL(string: source)?.host ?? ""
        Self.lock.lock(); Self.requests[host, default: 0] += 1; Self.lock.unlock()
        let path = URL(string: source)?.path ?? ""
        let id = path.components(separatedBy: "/").drop(while: { $0 != "Items" }).dropFirst().first ?? ""
        let row = VisualCatalog.rows[id]
        let art = row?["type_"].string == "CollectionFolder" ? "visual-1" : row?["art_id"].string ?? row?["id"].string ?? "visual-1"
        let missing = art == "visual-4" || path.hasSuffix("/Images/Logo")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: missing ? 404 : 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!, cacheStoragePolicy: .notAllowed)
        if !missing {
            let kind = path.contains("/Images/Backdrop") ? "Backdrop" : "Primary"
            client?.urlProtocol(self, didLoad: try! Data(contentsOf: VisualCatalog.directory.appendingPathComponent("\(art)-\(kind).png")))
        }
        client?.urlProtocolDidFinishLoading(self)
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
        VisualCatalog.verify()
        let core = VisualCommands(), api = MediaAPI(core: VisualCommands())
        api.use(MediaSession(server: "https://fixture.invalid", token: "fixture", user_id: "fixture", user_name: "演示账号"))
        model.api = api; model.ready = true; model.account = try! VisualCommands.account.decode(MediaAccount.self)
        model.images.use(server: "https://fixture.invalid")
        model.images.setChannel(base: "https://fixture.invalid", token: "fixture")
        let movie = try! core.movies[0].decode(MediaItem.self)
        let library = try! VisualCatalog.rows["visual-library"]!.decode(MediaItem.self)
        var favorite = movie; favorite.library_ids = [library.id]
        precondition(FavoriteGroup.make(items: [favorite], libraries: [library]).map(\.id) == ["library:" + library.id])
        precondition(FavoriteGroup.make(items: [favorite], libraries: []).map(\.id) == ["type:Movie"])
        let unknown = MediaItem.route(id: "unknown", name: "兼容条目", type: "BoxSet")
        let grouped = FavoriteGroup.make(items: [favorite, unknown], libraries: [library])
        precondition(Set(grouped.flatMap(\.items).map(\.id)) == [favorite.id, unknown.id], "分组不能丢失未知类型或无库归属的收藏")
        let series = try! VisualCatalog.rows["visual-series"]!.decode(MediaItem.self)
        for style in TVLoading.styles where style != 0 {
            precondition(LottieAnimation.named("lp_loading_\(style)", subdirectory: "Loading") != nil, "加载动画资源无法解析")
        }
        pages = [
            ("loading-styles", AnyView(VStack(alignment: .leading, spacing: 28) {
                Text("加载动画").font(.largeTitle)
                ForEach(TVLoading.styles, id: \.self) { style in
                    TVLoading(style == 0 ? "系统默认" : "样式 \(style)", style: style)
                }
            }.padding(64))),
            ("player-skip", AnyView(RootView())),
            ("favorites", AnyView(ProductRoot(api: api, initialTab: "favorites"))),
            ("favorite-category", AnyView(NavigationStack { FavoritesView(api: api, groupID: "library:" + library.id, title: "电影") })),
            ("search", AnyView(ProductRoot(api: api, initialTab: "search"))),
            ("servers", AnyView(ProductRoot(api: api, initialTab: "servers"))),
            ("account", AnyView(NavigationStack { AccountEditor(api: api, account: model.account!) })),
            ("aggregate", AnyView(ProductRoot(api: api, initialTab: "aggregate"))),
            ("ranking", AnyView(NavigationStack { DiscoveryView(api: api, calendar: false) })),
            ("calendar", AnyView(NavigationStack { DiscoveryView(api: api, calendar: true) })),
            ("person", AnyView(NavigationStack { PersonView(api: api, item: .route(id: "person", name: "演示演员", type: "Person")) })),
            ("collection", AnyView(NavigationStack { CollectionView(api: api, item: .route(id: "collection", name: "演示合集", type: "BoxSet")) })),
            ("appearance", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .appearance, title: "外观") })),
            ("player-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .player, title: "播放器") })),
            ("prefetch-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .prefetch, title: "多线程加载") })),
            ("progress-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .progress, title: "主进度服") })),
            ("storage-settings", AnyView(NavigationStack { SettingsDetailView(api: api, pane: .storage, title: "存储与数据目录") })),
            ("blocked", AnyView(NavigationStack { BlockedView(api: api) }))
        ]
        for mode in ["dark", "light"] {
            pages += [
                ("\(mode)-home", AnyView(ProductRoot(api: api))),
                ("\(mode)-library", AnyView(NavigationStack { LibraryView(api: api, item: library) })),
                ("\(mode)-detail", AnyView(NavigationStack { DetailView(api: api, item: movie) })),
                ("\(mode)-series", AnyView(NavigationStack { DetailView(api: api, item: series) })),
                ("\(mode)-settings", AnyView(ProductRoot(api: api, initialTab: "settings"))),
                ("\(mode)-player", AnyView(RootView()))
            ]
        }
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
        let light = name.hasPrefix("light-")
        window!.rootViewController = nil
        model.playback = nil
        let host = UIHostingController(rootView: page.environmentObject(model)
            .preferredColorScheme(light ? .light : .dark)
            .foregroundStyle(TVPalette.foreground(false)).tint(TVPalette.accent(false))
            .background(TVPalette.background(false)))
        window!.overrideUserInterfaceStyle = light ? .light : .dark
        window!.rootViewController = host; window!.makeKeyAndVisible()
        if name.hasSuffix("-player") || name == "player-skip" {
            DispatchQueue.main.async {
                let movie = try! VisualCatalog.selected("hero")[0].decode(MediaItem.self)
                self.model.playback = PlaybackController(api: self.model.api!, item: movie.id, title: movie.name,
                    version: nil, fromStart: false, showSkip: name == "player-skip")
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            self.capture(name)
            if name.hasSuffix("-series") || name.hasSuffix("-detail") || name == "player-settings",
               let scroll = self.verticalScroll(in: host.view) {
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
    private func animations(in view: UIView) -> [LottieAnimationView] {
        (view as? LottieAnimationView).map { [$0] } ?? view.subviews.flatMap { animations(in: $0) }
    }
    private func luminances(_ image: CGImage, in rect: CGRect) -> [Double] {
        let width = Int(rect.width), height = Int(rect.height)
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        rgba.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(image.cropping(to: rect)!, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return stride(from: 0, to: rgba.count, by: 4).map { offset in
            zip(rgba[offset..<offset + 3], [0.2126, 0.7152, 0.0722]).reduce(0) { result, channel in
                let value = Double(channel.0) / 255
                return result + (value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)) * channel.1
            }
        }
    }
    private func capture(_ name: String) {
        window!.layoutIfNeeded()
        if name == "loading-styles" {
            let views = animations(in: window!)
            precondition(views.count == TVLoading.styles.count - 1)
            precondition(views.allSatisfy { $0.bounds.size == CGSize(width: 48, height: 48) },
                "加载动画必须使用48点槽位，实际为：\(views.map { $0.bounds.size })")
        }
        if name.hasSuffix("-player") || name == "player-skip" {
            let presented = window!.rootViewController?.presentedViewController
            precondition(presented != nil && presented!.traitCollection.userInterfaceStyle == .dark, "正式播放呈现必须保持深色控件")
        }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let image = UIGraphicsImageRenderer(size: window!.bounds.size, format: format).image { _ in
            precondition(window!.drawHierarchy(in: window!.bounds, afterScreenUpdates: true), "页面绘制失败")
        }
        if name == "light-home" || name == "dark-home" {
            // 固定夹具：分别取标题、元信息和未选中导航的实绘字色，再与同高度无字背景比较。
            precondition(image.size == CGSize(width: 1920, height: 1080))
            let source = image.cgImage!
            let title = luminances(source, in: CGRect(x: 64, y: 445, width: 200, height: 45)).max()!
            let metadata = luminances(source, in: CGRect(x: 64, y: 521, width: 120, height: 17)).max()!
            let navigation = luminances(source, in: CGRect(x: 568, y: 73, width: 48, height: 23))
            let navigationText = name == "light-home" ? navigation.min()! : navigation.max()!
            for point in [CGPoint(x: 320, y: 470), CGPoint(x: 800, y: 470),
                          CGPoint(x: 320, y: 530), CGPoint(x: 800, y: 530), CGPoint(x: 950, y: 85)] {
                let background = luminances(source, in: CGRect(origin: point, size: CGSize(width: 1, height: 1)))[0]
                let values = [point.y < 140 ? navigationText : point.y < 500 ? title : metadata, background]
                let contrast = (values.max()! + 0.05) / (values.min()! + 0.05)
                precondition(contrast >= (point.y < 140 ? 3 : 4.5), "\(name)叠图文字对比度不足：\(contrast)，位置：\(point)")
            }
        }
        try! image.pngData()!.write(to: output.appendingPathComponent(name + ".png"))
    }
}
