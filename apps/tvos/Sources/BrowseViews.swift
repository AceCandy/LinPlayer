import SwiftUI
import UIKit
import Lottie

// 与共享浏览页保持同一组品牌色；私密空间不改写普通主题偏好。
enum TVPalette {
    static func background(_ privateSpace: Bool) -> Color {
        color(light: privateSpace ? 0xEFE5F7 : 0xFAF7FC, dark: privateSpace ? 0x21152D : 0x0D0C12)
    }
    static func foreground(_ privateSpace: Bool) -> Color {
        color(light: privateSpace ? 0x291B35 : 0x1A1622, dark: privateSpace ? 0xFAF3FF : 0xF5F5F7)
    }
    static func accent(_ privateSpace: Bool, palette: String = "amber") -> Color {
        if privateSpace { return color(light: 0x633287, dark: 0xDEB7FF) }
        switch palette {
        case "blue": return color(light: 0x315DA8, dark: 0x9BBAFF)
        case "green": return color(light: 0x216B4A, dark: 0x82D6B0)
        case "purple": return color(light: 0x71449C, dark: 0xCAB0FF)
        default: return color(light: 0x8A5A00, dark: 0xF4AD39)
        }
    }
    private static func color(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

#if !UI_RENDER_CHECK
@main
#endif
struct LinPlayerApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase
    @AppStorage("appearance") private var appearance = "dark"
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
                .preferredColorScheme(appearance == "system" ? nil : appearance == "light" ? .light : .dark)
                .tint(TVPalette.accent(model.privateSpace, palette: model.colorScheme))
                .task { await model.start() }
                .onChange(of: phase) { _, next in
                    model.sceneActive = next == .active
                    if next != .active { model.playback?.suspend() }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        ZStack {
            TVPalette.background(model.privateSpace).ignoresSafeArea()
            if let error = model.fatalError { Text(error).padding(80) }
            else if !model.ready { TVLoading("正在恢复账号") }
            else if model.needsLogin { LoginView() }
            else if let api = model.api {
                ProductRoot(api: api).id(model.identity)
            }
        }
        .foregroundStyle(TVPalette.foreground(model.privateSpace))
        .fullScreenCover(isPresented: Binding(get: { model.playback != nil }, set: { value in
            if !value { Task { await model.closePlayer() } }
        })) {
            if let controller = model.playback { PlayerView(controller: controller).environmentObject(model).id(ObjectIdentifier(controller)) }
        }
        .alert("提示", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("知道了") { model.notice = nil }
        } message: { Text(model.notice ?? "") }
    }
}

private struct TVPageActiveKey: EnvironmentKey { static let defaultValue = true }
extension EnvironmentValues {
    var tvPageActive: Bool {
        get { self[TVPageActiveKey.self] }
        set { self[TVPageActiveKey.self] = newValue }
    }
}

// 与 Android 使用同源动画；只保存本机外观偏好，不进入业务缓存。
struct TVLoading: View {
    static let styles = [0, 1, 2, 3, 4, 5, 7]
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.tvPageActive) private var pageActive
    @State private var visible = false
    var label = "加载中"
    var style: Int? = nil
    init(_ label: String = "加载中", style: Int? = nil) { self.label = label; self.style = style }
    var body: some View {
        let selected = style ?? model.loadingStyle
        HStack(spacing: 14) {
            if selected != 0, Self.styles.contains(selected),
               let animation = LottieAnimation.named("lp_loading_\(selected)", subdirectory: "Loading") {
                TVLoadingAnimation(animation: animation, themed: selected == 4,
                    tint: TVPalette.accent(model.privateSpace, palette: model.colorScheme),
                    playing: visible && pageActive && model.sceneActive && phase == .active && !reduceMotion,
                    reduceMotion: reduceMotion)
                    .id(selected).frame(width: 48, height: 48)
            } else if reduceMotion {
                Image(systemName: "hourglass").frame(width: 48, height: 48)
            } else if visible && pageActive && model.sceneActive && phase == .active {
                ProgressView().frame(width: 48, height: 48)
            } else {
                Color.clear.frame(width: 48, height: 48)
            }
            Text(label).font(.system(size: 22)).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel(label)
        .onAppear { visible = true }.onDisappear { visible = false }
    }
}

private struct TVLoadingAnimation: UIViewRepresentable {
    @Environment(\.colorScheme) private var scheme
    let animation: LottieAnimation
    let themed: Bool
    let tint: Color
    let playing: Bool
    let reduceMotion: Bool
    func makeUIView(context: Context) -> LottieAnimationView {
        let view = LottieAnimationView(animation: animation)
        view.loopMode = .loop; view.backgroundBehavior = .pause
        view.contentMode = .scaleAspectFit
        return view
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: LottieAnimationView, context: Context) -> CGSize? {
        proposal.replacingUnspecifiedDimensions()
    }
    func updateUIView(_ view: LottieAnimationView, context: Context) {
        if themed {
            let color = UIColor(tint).resolvedColor(with: UITraitCollection(userInterfaceStyle: scheme == .dark ? .dark : .light))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            let provider = ColorValueProvider(LottieColor(r: Double(r), g: Double(g), b: Double(b), a: Double(a)))
            view.setValueProvider(provider, keypath: AnimationKeypath(keypath: "**.Color"))
        }
        if reduceMotion { view.pause(); view.currentProgress = 0.5 }
        else if playing { if !view.isAnimationPlaying { view.play() } }
        else { view.pause() }
    }
    static func dismantleUIView(_ view: LottieAnimationView, coordinator: ()) { view.stop() }
}

struct LoginView: View {
    @EnvironmentObject private var model: AppModel
    @State private var name = ""
    @State private var testing = false
    @State private var testResult: String?
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    var body: some View {
        VStack(spacing: 28) {
            Text("LinPlayer").font(.largeTitle.bold())
            Text("连接 Emby 服务器").foregroundStyle(.secondary)
            TVTextInput("名称（可选）", text: $name)
            TVTextInput("服务器地址", text: $server, contentType: .URL)
            TVTextInput("用户名", text: $username, contentType: .username)
            TVTextInput("密码", text: $password, secure: true, contentType: .password)
            if let error { Text(error).foregroundStyle(.orange) }
            if let testResult { Text(testResult).foregroundStyle(.secondary) }
            HStack(spacing: 24) {
            Button(model.busy ? "正在连接…" : "连接") {
                Task {
                    error = nil
                    do { try await model.login(server: server, username: username, password: password, name: name); password = "" }
                    catch { self.error = error.localizedDescription }
                }
            }.buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled().disabled(model.busy || server.isEmpty || username.isEmpty)
            Button(testing ? "正在测试…" : "测试连接") {
                Task {
                    testing = true; defer { testing = false }
                    do {
                        let result = try await model.testConnection(server: server, username: username, password: password)
                        testResult = "连接成功 · " + (result["name"].string ?? "") + " · " + (result["version"].string ?? ""); error = nil
                    } catch is CancellationError {} catch { self.error = error.localizedDescription; testResult = nil }
                }
            }.buttonStyle(TVActionStyle()).focusEffectDisabled().disabled(model.busy || server.isEmpty)
            }.disabled(testing)
        }.frame(maxWidth: 720).padding(60)
    }
}

func invalidatesDisplay(_ error: Error) -> Bool {
    guard let failure = error as? CoreFailure else { return false }
    return ["E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_UNSUPPORTED", "E_INVALID"].contains(failure.code)
}

struct RetryMessage: View {
    let text: String
    let retry: () -> Void
    var body: some View {
        VStack(spacing: 16) { Text(text).foregroundStyle(.secondary); Button("重试", action: retry).buttonStyle(TVActionStyle()).focusEffectDisabled() }.padding()
    }
}

struct TVSectionHeading: View {
    let title: String
    init(_ title: String) { self.title = title }
    var body: some View {
        HStack(spacing: 14) {
            Capsule().fill(.secondary).frame(width: 5, height: 28)
            Text(title).font(.system(size: 30, weight: .semibold))
        }
    }
}

struct MediaCard: View {
    let item: MediaItem
    let images: ImageLoader
    var wide = false
    var episodeCaption = false
    var currentEpisode = false
    var targetEpisode = false
    var imageServer: String? = nil
    @EnvironmentObject private var model: AppModel
    @Environment(\.isFocused) private var focused
    private var width: CGFloat { wide ? 400 : 250 }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MediaArtwork(item: item, loader: images, width: width, height: wide ? 225 : 375, server: imageServer)
                .overlay(alignment: .topTrailing) {
                    if let count = item.episodeStatus {
                        Group {
                            if count == 0 { Image(systemName: "checkmark").foregroundStyle(.green) }
                            else { Text(String(count)).foregroundStyle(.white) }
                        }.font(.system(size: 22, weight: .semibold)).padding(10)
                            .background(.black.opacity(0.72), in: Capsule()).padding(12)
                            .accessibilityLabel(count == 0 ? "已看完" : "\(count) 集未观看")
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    if wide, let time = item.resumeTimeText {
                        Text(time).font(.system(size: 20, weight: .medium)).foregroundStyle(.white)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6)).padding(12)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if !episodeCaption {
                        VStack(alignment: .trailing, spacing: 5) {
                            ratingBadge(douban: false)
                            ratingBadge(douban: true)
                        }.padding(12)
                    }
                }
                .overlay(alignment: .bottom) {
                    if item.resume_secs > 0 && item.runtime_secs > 0 {
                        ProgressView(value: min(item.resume_secs / item.runtime_secs, 1)).padding(.horizontal, 2)
                    }
                }
                .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(focused ? TVPalette.foreground(model.privateSpace).opacity(0.65) : TVPalette.accent(model.privateSpace, palette: model.colorScheme).opacity(currentEpisode || targetEpisode ? 1 : 0), lineWidth: 2) }
            if episodeCaption {
                HStack {
                    Text([item.season_no.map { "S\($0)" }, item.episode_no.map { "E\($0)" }].compactMap { $0 }.joined())
                    Spacer()
                    if currentEpisode || targetEpisode { Text(currentEpisode ? "当前集" : "待播放") }
                }.font(.system(size: 22, weight: .semibold)).foregroundStyle(currentEpisode || targetEpisode ? TVPalette.accent(model.privateSpace, palette: model.colorScheme) : .secondary)
                Text(item.name).font(.system(size: 26, weight: .medium)).lineLimit(2)
            } else {
                Text(item.type_ == "Episode" ? item.series_name ?? item.name : item.name)
                    .font(.system(size: 26, weight: .medium)).lineLimit(1)
                if let season = item.season_no, let episode = item.episode_no, item.type_ == "Episode" {
                    Text("S\(season)E\(episode)").font(.system(size: 20)).foregroundStyle(.secondary)
                } else if let year = item.year, !wide {
                    Text(String(year)).font(.system(size: 20)).foregroundStyle(.secondary)
                }
            }
        }.frame(width: width, alignment: .topLeading)
            .scaleEffect(focused ? 1.025 : 1)
            .animation(.easeOut(duration: 0.16), value: focused)
            .contextMenu {
                if imageServer == nil || imageServer == model.api?.session?.server {
                    if ["Movie", "Series", "Season", "Episode"].contains(item.type_) {
                        Button(item.played == true ? "标记为未看" : "标记为已看") {
                            model.setItemState(item.id, played: item.played != true)
                        }.disabled(model.writingItems.contains(item.id))
                    }
                    if ["Movie", "Series", "BoxSet"].contains(item.type_) {
                        Button(item.is_favorite == true ? "取消收藏" : "收藏") {
                            model.setItemState(item.id, favorite: item.is_favorite != true)
                        }.disabled(model.writingItems.contains(item.id))
                    }
                }
            }
    }
    @ViewBuilder private func ratingBadge(douban: Bool) -> some View {
        if let score = item.ratingText(douban: douban) {
            HStack(spacing: 5) {
                Text(douban ? "豆" : "★").foregroundStyle(douban ? .green : .white.opacity(0.8))
                Text(score).foregroundStyle(.white)
            }.font(.system(size: 20, weight: .medium)).padding(.horizontal, 9).padding(.vertical, 4)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
                .accessibilityLabel("\(douban ? "豆瓣" : "普通")评分 \(score)")
        }
    }
}

private struct LibraryCard: View {
    let item: MediaItem
    let images: ImageLoader
    @Environment(\.isFocused) private var focused
    var body: some View {
        Poster(id: item.id, enabled: item.has_primary, loader: images, width: 316, height: 178, fit: true, placeholder: item.name)
            .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(.primary.opacity(focused ? 0.65 : 0), lineWidth: 2) }
    }
}

// 自绘焦点与按钮底色，避免 tvOS 系统白色焦点板覆盖海报。
struct TVCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.75 : 1)
    }
}

struct TVActionStyle: ButtonStyle {
    var primary = false
    var compact = false
    func makeBody(configuration: Configuration) -> some View {
        TVActionLabel(primary: primary, compact: compact, destructive: configuration.role == .destructive, pressed: configuration.isPressed) { configuration.label }
    }
}

private struct TVActionLabel<Content: View>: View {
    let primary: Bool
    let compact: Bool
    let destructive: Bool
    let pressed: Bool
    @ViewBuilder let content: () -> Content
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    @EnvironmentObject private var model: AppModel
    var body: some View {
        content().font(.system(size: 25, weight: .medium)).padding(.horizontal, compact ? 18 : 28).frame(height: compact ? 46 : 72)
            .foregroundStyle(destructive ? Color.red : primary ? TVPalette.background(model.privateSpace) : TVPalette.foreground(model.privateSpace))
            .background(primary ? TVPalette.accent(model.privateSpace, palette: model.colorScheme) : TVPalette.foreground(model.privateSpace).opacity(focused ? 0.14 : 0.07), in: RoundedRectangle(cornerRadius: 18))
            .overlay { RoundedRectangle(cornerRadius: 18).strokeBorder(TVPalette.foreground(model.privateSpace).opacity(focused ? 0.65 : 0), lineWidth: 2) }
            .scaleEffect(pressed ? 0.98 : 1).opacity(enabled ? 1 : 0.45)
            .animation(.easeOut(duration: 0.14), value: focused)
    }
}

private struct HomeBanner: View {
    @EnvironmentObject private var model: AppModel
    let items: [MediaItem]
    let active: Bool
    var height: CGFloat = 650
    @State private var logo: UIImage?
    @State private var progress: CGFloat = 0
    @State private var selected = ""
    @State private var pendingPlay: MediaItem?
    @State private var playError: String?
    @FocusState private var focused: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var item: MediaItem { items.first { $0.id == selected } ?? items[0] }
    private var timerIdentity: String { "\(item.id):\(active):\(focused ?? ""):\(model.sceneActive):\(reduceMotion):\(items.count):\(pendingPlay?.id ?? ""):\(model.playback != nil)" }
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            NavigationLink(value: item) { Backdrop(item: item, loader: model.images, height: height, whiteForeground: true) }
                .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focused, equals: "background").accessibilityLabel(item.name + "，查看详情")
            VStack(alignment: .leading, spacing: 18) {
                if let logo {
                    Image(uiImage: logo).resizable().scaledToFit().frame(maxWidth: 620, maxHeight: 180, alignment: .leading)
                        .accessibilityLabel(item.name)
                } else {
                    Text(item.type_ == "Episode" ? item.series_name ?? item.name : item.name)
                        .font(.system(size: 52, weight: .bold)).lineLimit(2)
                }
                HStack(spacing: 18) {
                    if let year = item.year { Text(String(year)) }
                    if let genre = item.genres?.first { Text(genre) }
                }.font(.system(size: 22)).foregroundStyle(.white.opacity(0.8))
                HStack(spacing: 24) {
                    if ["Movie", "Episode", "Series"].contains(item.type_) {
                        Button {
                            playError = nil; pendingPlay = item
                        } label: { Label(pendingPlay != nil ? "正在准备播放" : item.resume_secs > 0 && item.played != true ? "继续播放" : "播放", systemImage: "play.fill") }
                        .buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled().focused($focused, equals: "play").disabled(model.playback != nil || pendingPlay != nil)
                    } else {
                        NavigationLink(value: item) { Label("查看剧集", systemImage: "play.rectangle") }
                            .buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled().focused($focused, equals: "play")
                    }
                }.font(.system(size: 24, weight: .semibold))
                if let playError { Text(playError).font(.system(size: 22)).foregroundStyle(.orange) }
            }.padding(.horizontal, 64).padding(.bottom, 58)
        }.frame(height: height).foregroundStyle(.white)
        .task(id: "\(item.id):\(item.has_logo == true):\(model.images.revision)") {
            logo = nil
            if item.has_logo == true {
                let image = await model.images.load(id: item.id, kind: "Logo", height: 240)
                guard !Task.isCancelled else { return }; logo = image
            }
        }
        .onChange(of: active) { _, value in if !value { pendingPlay = nil } }
        .task(id: pendingPlay?.id) {
            guard let requested = pendingPlay, let api = model.api else { return }
            defer { if pendingPlay?.id == requested.id { pendingPlay = nil } }
            do {
                let target = try await api.homePlaybackTarget(requested)
                try Task.checkCancellation()
                guard active else { return }
                model.openPlayer(item: target.id, title: target.title, version: nil, fromStart: false, origin: requested.id)
            } catch is CancellationError {} catch { playError = error.localizedDescription }
        }
        .overlay(alignment: .bottomTrailing) {
            if items.count > 1 {
                HStack(spacing: 14) {
                    ForEach(items) { candidate in
                        Button { selected = candidate.id } label: {
                            Capsule().fill(.white.opacity(0.25)).frame(width: candidate.id == item.id ? 72 : 32, height: 6)
                                .overlay(alignment: .leading) {
                                    if candidate.id == item.id { Capsule().fill(.white).frame(width: 72 * progress, height: 6) }
                                }.padding(.vertical, 18)
                        }.buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focused, equals: "hero:\(candidate.id)")
                            .accessibilityLabel("切换到 " + candidate.name)
                    }
                }.padding(.trailing, 64).padding(.bottom, 12)
            }
        }
        .task(id: timerIdentity) {
            progress = 0
            guard active, focused == nil, model.sceneActive, !reduceMotion, items.count > 1, pendingPlay == nil, model.playback == nil else { return }
            do {
                withAnimation(.linear(duration: 5)) { progress = 1 }
                try await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                let index = items.firstIndex { $0.id == item.id } ?? 0
                selected = items[(index + 1) % items.count].id
            } catch {}
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    var active = true
    @State private var heroVisible = true
    @State private var scrolling = false
    @AppStorage("hideLibraryPosters") private var hideLibraryPosters = false
    @State private var libraries: [MediaItem] = []
    @State private var librariesLoaded = false
    @State private var bannerError: String?
    @State private var bannerResolved = false
    @State private var resume: [MediaItem] = []
    @State private var resumeError: String?
    @State private var latest: [String: [MediaItem]] = [:]
    @State private var banners: [MediaItem] = []
    @State private var collections: [MediaItem] = []
    @State private var collectionsEnabled: Bool?
    @State private var configError: String?
    @State private var libraryError: String?
    @State private var reload = 0

    private struct CandidateRequest: Equatable {
        let items: [MediaItem]
        let revision: Int
        let reload: Int
        let active: Bool
    }

    var body: some View {
        GeometryReader { geometry in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                if !banners.isEmpty { HomeBanner(items: banners, active: active && heroVisible && !scrolling, height: max(560, min(760, geometry.size.height * 0.64))) }
                else if bannerResolved { Color.clear.frame(height: 140) }
                else { RoundedRectangle(cornerRadius: 0).fill(.primary.opacity(0.035)).frame(height: max(560, min(760, geometry.size.height * 0.64))) }
                if let bannerError { RetryMessage(text: bannerError) { reload += 1 } }
                if let libraryError { RetryMessage(text: libraryError) { reload += 1 } }
                if !hideLibraryPosters { ScrollView(.horizontal) {
                    LazyHStack(spacing: 20) {
                        ForEach(libraries) { library in
                            NavigationLink(value: library) {
                                LibraryCard(item: library, images: model.images)
                            }.buttonStyle(TVCardStyle()).focusEffectDisabled().accessibilityLabel(library.name)
                        }
                    }.padding(.horizontal, 64).padding(.vertical, 12)
                } }
                if !resume.isEmpty {
                    if !hideLibraryPosters { TVSectionHeading("继续观看").padding(.horizontal, 64) }
                    MediaRow(items: resume, wide: true)
                }
                if let resumeError { RetryMessage(text: resumeError) { reload += 1 } }
                ForEach(libraries) { library in
                    MediaSection(api: api, title: library.name, command: "emby.listLatest",
                        args: ["parent_id": .string(library.id), "limit": .number(16)], library: library,
                        onLoaded: { items in latest[library.id] = items })
                }
                if collectionsEnabled == true { MediaSection(api: api, title: "合集", command: "emby.listCollections", args: [:], onLoaded: { collections = $0 }) }
                if let configError { RetryMessage(text: configError) { reload += 1 } }
            }.padding(.bottom, 40)
        }
        .onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.y < 500
        } action: { _, value in heroVisible = value }
        .onScrollPhaseChange { _, phase in scrolling = phase != .idle }
        .task(id: CandidateRequest(items: resume + libraries.flatMap { latest[$0.id] ?? [] } + collections,
                                   revision: model.userStateRevision, reload: reload, active: active)) {
            guard active else { return }
            do {
                let pool = try await api.request("emby.cachedItems").decode([MediaItem].self)
                let candidates = try await api.heroCandidates(resume + libraries.flatMap { latest[$0.id] ?? [] } + collections + pool)
                    .filter { $0.has_backdrop == true || $0.has_primary }
                let byID = Dictionary(candidates.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                let retained = banners.compactMap { byID[$0.id] }
                let ids = Set(retained.map(\.id))
                banners = Array((retained + candidates.filter { !ids.contains($0.id) }).prefix(6))
                bannerError = nil; bannerResolved = true
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { banners = [] }; bannerError = error.localizedDescription; bannerResolved = true }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            do {
                resume = try await api.request("emby.listResume", ["limit": .number(16)], snapshot: { value in if let cached = try? value.decode([MediaItem].self) { resume = cached } }).decode([MediaItem].self)
                resumeError = nil
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { resume = [] }; resumeError = error.localizedDescription }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            do { libraries = try await api.request("emby.views", snapshot: { value in if let cached = try? value.decode([MediaItem].self) { libraries = cached } }).decode([MediaItem].self); librariesLoaded = true; libraryError = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { libraries = []; latest = [:] }; librariesLoaded = true; libraryError = error.localizedDescription }
        }
        .task(id: "\(libraries.map(\.id)):\(reload)") {
            guard let first = libraries.first, latest[first.id] == nil else { return }
            do { latest[first.id] = try await api.request("emby.listLatest", ["parent_id": .string(first.id), "limit": .number(16)]).decode([MediaItem].self); bannerError = nil }
            catch is CancellationError {} catch { bannerError = error.localizedDescription }
        }
        .task(id: reload) {
            do {
                let settings = try await api.request("prefs.getHomeSettings")
                guard let enabled = settings["collections_enabled"].bool else { throw CoreFailure(code: "E_PROTOCOL") }
                collectionsEnabled = enabled; configError = nil
            } catch is CancellationError {} catch { configError = error.localizedDescription }
        }
        }
    }
}

struct MediaRow: View {
    @EnvironmentObject private var model: AppModel
    let items: [MediaItem]
    var wide = false
    var inset: CGFloat = 64
    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 20) {
                ForEach(items) { item in
                    NavigationLink(value: item) { MediaCard(item: item, images: model.images, wide: wide) }
                        .buttonStyle(TVCardStyle()).focusEffectDisabled().id(item.id)
                }
            }.padding(.horizontal, inset).padding(.vertical, 20)
        }.frame(height: wide ? 335 : 485).focusSection()
    }
}

struct MediaSection: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let title: String
    let command: String
    let args: [String: JSONValue]
    var library: MediaItem? = nil
    var onLoaded: (([MediaItem]) -> Void)? = nil
    @State private var items: [MediaItem]?
    @State private var error: String?
    @State private var reload = 0
    @State private var loadedKey = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
        if command != "emby.listLatest" || items?.isEmpty != true || error != nil {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                TVSectionHeading(title)
                Spacer()
                if let library { NavigationLink(value: library) { Label("全部", systemImage: "chevron.right").font(.system(size: 22)) }.buttonStyle(TVCardStyle()).focusEffectDisabled() }
            }.padding(.horizontal, 64)
            if let error { RetryMessage(text: error) { reload += 1 } }
            if let items {
                if items.isEmpty { Text("暂无内容").foregroundStyle(.secondary).padding(.horizontal, 64) }
                else { MediaRow(items: items) }
            } else if error == nil { ScrollView(.horizontal) {
                HStack(spacing: 20) {
                    ForEach(0..<6) { _ in RoundedRectangle(cornerRadius: 12).fill(.primary.opacity(0.05)).frame(width: 250, height: 375) }
                }.padding(.horizontal, 64)
            }.frame(height: 485).allowsHitTesting(false).accessibilityLabel("正在加载") }
        }
        }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            let key = "\(reload):\(model.userStateRevision)"
            guard loadedKey != key else { return }
            do { let result = try await api.request(command, args, snapshot: { value in if let cached = try? value.decode([MediaItem].self) { items = cached; onLoaded?(cached) } }).decode([MediaItem].self); items = result; loadedKey = key; onLoaded?(result); error = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { items = nil; onLoaded?([]) }; self.error = error.localizedDescription }
        }
    }
}

// 每条路由独立持有分页与焦点；游标依据核心原页推进，不能用去重后的卡片数量。
@MainActor final class ItemPager: ObservableObject {
    @Published var items: [MediaItem] = []
    @Published var error: String?
    @Published var loading = false
    @Published var more = true
    private var cursor = 0

    private var generation = UUID()
    private var revision = -1

    func load(api: MediaAPI, parent: String, episodes: Bool, query: BrowseQuery, revision: Int) async {
        let refresh = self.revision != revision
        guard refresh || more || error != nil else { return }
        let request = UUID(); generation = request
        loading = true
        defer { if generation == request { loading = false } }
        do {
            var next = refresh ? 0 : cursor
            var result = refresh ? [] : items
            let target = refresh ? max(cursor, 40) : cursor + 40
            var hasMore = true
            repeat {
                let prefix = result
                let show: (MediaPage) -> Void = { page in
                    guard self.generation == request, self.items.isEmpty else { return }
                    var seen = Set(prefix.map(\.id))
                    self.items = prefix + page.items.filter { seen.insert($0.id).inserted }
                }
                let page: MediaPage
                if episodes { page = try await api.episodes(parent: parent, start: next, snapshot: show) }
                else { page = try await api.items(parent: parent, start: next, query: query, snapshot: show) }
                next += page.items.count
                var seen = Set(result.map(\.id))
                result += page.items.filter { seen.insert($0.id).inserted }
                hasMore = !page.items.isEmpty && next < page.total
            } while refresh && hasMore && next < target
            try Task.checkCancellation()
            guard generation == request else { return }
            items = result; cursor = next; more = hasMore; self.revision = revision; error = nil
        } catch is CancellationError {} catch {
            if generation == request { if invalidatesDisplay(error) { items = []; cursor = 0; more = true; self.revision = -1 }; self.error = error.localizedDescription }
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let item: MediaItem
    private let defaultQuery = BrowseQuery(sort: "DateLastContentAdded", descending: true)
    @State private var query = BrowseQuery(sort: "DateLastContentAdded", descending: true)
    @State private var filters: MediaFilters?
    @State private var filterError: String?
    @State private var reload = 0
    @State private var showFilters = false
    @State private var loaded = 0
    @State private var more = true
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.name).font(.system(size: 38, weight: .semibold))
                    Text("已加载 \(loaded)" + (more ? "+" : "")).font(.system(size: 22)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { showFilters = true } label: { Label("筛选与排序", systemImage: "slider.horizontal.3") }
                    .buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
            }.padding(.horizontal, 64)
            if query != defaultQuery {
                ScrollView(.horizontal) {
                    HStack(spacing: 16) {
                        if !query.genre.isEmpty { filterChip(query.genre) { query.genre = "" } }
                        if query.ratingMin > 0 { filterChip("评分 ≥ \(query.ratingMin)") { query.ratingMin = 0 } }
                        if !query.tag.isEmpty { filterChip(query.tag) { query.tag = "" } }
                        if !query.year.isEmpty { filterChip(query.year) { query.year = "" } }
                        if !query.played.isEmpty { filterChip(query.played == "true" ? "已看" : "未看") { query.played = "" } }
                        Button("重置全部") { query = defaultQuery }.buttonStyle(TVActionStyle(compact: true))
                    }.padding(.horizontal, 64).padding(.vertical, 4)
                }.focusEffectDisabled()
            }
            if let filterError { RetryMessage(text: filterError) { reload += 1 } }
            ItemGrid(api: api, parent: item.id, episodes: false, query: query, onLoaded: { count, hasMore in loaded = count; more = hasMore }, clearFilters: query == defaultQuery ? nil : { query = defaultQuery })
                .id(String(describing: query))
        }.padding(.top, 28).toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal)
        .onChange(of: query) { _, _ in loaded = 0; more = true }
        .sheet(isPresented: $showFilters) {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    TVSectionHeading("筛选与排序")
                    TVChoice(title: "排序", selection: $query.sort, options: [("DateLastContentAdded", "更新日期"), ("PremiereDate", "上映日期"), ("SortName", "名称"), ("CommunityRating", "评分"), ("ProductionYear", "年份"), ("DateCreated", "入库时间")])
                    Button(query.descending ? "降序" : "升序") { query.descending.toggle() }
                    TVChoice(title: "评分下限", selection: $query.ratingMin, options: [(0, "不限"), (9, "9 分以上"), (8, "8 分以上"), (7, "7 分以上"), (6, "6 分以上")])
                    TVChoice(title: "类型", selection: $query.genre, options: [("", "全部类型")] + (filters?.genres ?? []).map { ($0, $0) })
                    TVChoice(title: "标签", selection: $query.tag, options: [("", "全部标签")] + (filters?.tags ?? []).map { ($0, $0) })
                    TVChoice(title: "年份", selection: $query.year, options: [("", "全部年份")] + (filters?.years ?? []).map { (String($0), String($0)) })
                    TVChoice(title: "已看", selection: $query.played, options: [("", "全部"), ("true", "已看"), ("false", "未看")])
                    Button("完成") { showFilters = false }
                }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled().padding(64)
            }.onExitCommand { showFilters = false }
        }
        .task(id: reload) {
            do {
                let result = try await api.request("emby.getFilters", ["parent_id": .string(item.id)], snapshot: { value in
                    if let cached = try? value.decode(MediaFilters.self) { filters = cached }
                }).decode(MediaFilters.self)
                filters = result
                filterError = (result.unavailable ?? []).isEmpty ? nil : "部分筛选项暂时无法获取。"
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { filters = nil }; filterError = error.localizedDescription }
        }
    }
    private func filterChip(_ title: String, clear: @escaping () -> Void) -> some View {
        Button(action: clear) { Label(title, systemImage: "xmark") }.buttonStyle(TVActionStyle(primary: true, compact: true))
    }

}

struct ItemGrid: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let parent: String
    let episodes: Bool
    var query = BrowseQuery()
    var onLoaded: ((Int, Bool) -> Void)? = nil
    var clearFilters: (() -> Void)? = nil
    @StateObject private var pager = ItemPager()
    @FocusState private var focus: String?
    @State private var lastFocus: String?
    @State private var reload = 0

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 250), spacing: 36)], alignment: .leading, spacing: 40) {
                    ForEach(pager.items) { item in
                        NavigationLink(value: item) { MediaCard(item: item, images: model.images) }
                            .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focus, equals: item.id).id(item.id)
                    }
                }.padding(.horizontal, 64).padding(.vertical, 28)
                if let error = pager.error { RetryMessage(text: error) { reload += 1 } }
                else if pager.loading { TVLoading("正在加载") }
                else if pager.items.isEmpty {
                    VStack(spacing: 20) {
                        Text("暂无内容").foregroundStyle(.secondary)
                        if let clearFilters { Button("清除筛选", action: clearFilters).buttonStyle(TVActionStyle()).focusEffectDisabled() }
                    }
                }
                else if pager.more { Button("加载更多") { reload += 1 }.buttonStyle(TVActionStyle()).focusEffectDisabled().padding(30) }
            }
            .onChange(of: focus) { _, next in
                if let next {
                    lastFocus = next
                    if let index = pager.items.firstIndex(where: { $0.id == next }),
                       index >= max(0, pager.items.count - 8), pager.more, !pager.loading, pager.error == nil { reload += 1 }
                }
            }
            .onAppear {
                if let id = lastFocus, pager.items.contains(where: { $0.id == id }) { proxy.scrollTo(id); focus = id }
            }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            await pager.load(api: api, parent: parent, episodes: episodes, query: query, revision: model.userStateRevision)
            guard !Task.isCancelled else { return }
            onLoaded?(pager.items.count, pager.more)
        }
    }
}

struct DetailView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let item: MediaItem
    @State private var detail: MediaDetail?
    @State private var presentation: DetailPresentation?
    @State private var versions: [MediaVersion] = []
    @State private var version = ""
    @State private var seasons: [MediaSeason]?
    @State private var confirmedSeasons: [String: MediaPage] = [:]
    @State private var calibrationRevision = -1
    @State private var season = ""
    @FocusState private var focusedSeason: String?
    @FocusState private var focusedStream: Int?
    @State private var error: String?
    @State private var mediaError: String?
    @State private var versionsReady = false
    @State private var reload = 0
    @State private var mediaReload = 0
    @State private var writing = false
    @State private var actionError: String?
    @State private var selectedSubtitle: Int?
    @State private var selectedAudio: Int?
    @State private var playbackItem: MediaItem?
    private var current: MediaItem { playbackItem ?? item }
    private var selectedVersion: MediaVersion? { versions.first { $0.id == version } ?? versions.first { $0.preferred } ?? versions.first }
    private var series: Bool { item.type_ == "Series" }
    @State private var expandedOverview = false
    private var artwork: MediaItem {
        guard let shown = presentation else { return current }
        return MediaItem(id: current.id, name: shown.name, type_: current.type_, is_folder: current.is_folder,
            has_primary: shown.has_primary, runtime_secs: current.runtime_secs, resume_secs: current.resume_secs,
            series_name: current.series_name, episode_no: current.episode_no, year: shown.year,
            season_no: current.season_no, series_id: current.series_id, season_id: current.season_id,
            has_backdrop: shown.has_backdrop, played: current.played, is_favorite: current.is_favorite,
            rating: shown.rating, DoubanRating: shown.DoubanRating)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ZStack(alignment: .bottomLeading) {
                    Backdrop(item: artwork, loader: model.images, height: 460, primaryEnabled: presentation?.has_primary)
                    LinearGradient(colors: [.clear, TVPalette.background(model.privateSpace)], startPoint: .top, endPoint: .bottom)
                    VStack(alignment: .leading, spacing: 16) {
                        if current.type_ == "Episode", let id = current.series_id ?? detail?.series_id {
                            NavigationLink(value: MediaItem.route(id: id, name: current.series_name ?? "返回剧集", type: "Series")) {
                                Label(current.series_name ?? "返回剧集", systemImage: "chevron.left").font(.system(size: 28))
                            }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
                        }
                        Text((current.type_ == "Episode" ? episodeNumber : "") + (presentation?.name ?? current.name))
                            .font(.system(size: 44, weight: .semibold)).lineLimit(3)
                    }.padding(.horizontal, 64).padding(.bottom, 28)
                }.frame(height: 460)
                VStack(alignment: .leading, spacing: 22) {
                        if let detail = presentation {
                            HStack(spacing: 22) {
                                if let score = artwork.ratingText() { Label(score, systemImage: "star.fill").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                                if let score = artwork.ratingText(douban: true) { Text("豆瓣 " + score).foregroundStyle(.green) }
                                if let date = detail.premiere_date, !date.isEmpty { Text(String(date.prefix(10))) }
                                else if let year = detail.year { Text(String(year)) }
                                if let time = current.resumeTimeText { Text(time) }
                                if let rating = detail.official_rating, !rating.isEmpty { Text(rating) }
                                if let status = detail.status, !status.isEmpty { Text(status) }
                            }.font(.system(size: 24)).foregroundStyle(.secondary)
                            ScrollView(.horizontal) {
                                HStack(spacing: 16) {
                                    ForEach(detail.genres ?? [], id: \.self) { value in facetLink(value, query: BrowseQuery(genre: value)) }
                                    ForEach(detail.tags ?? [], id: \.self) { value in facetLink(value, query: BrowseQuery(tag: value)) }
                                    ForEach(detail.studios ?? []) { studio in facetLink(studio.name, query: BrowseQuery(studioID: studio.id), available: !studio.id.isEmpty) }
                                }.padding(.vertical, 10)
                            }
                        } else if error == nil { TVLoading("正在加载详情") }
                        if let error { RetryMessage(text: error) { reload += 1 } }
                        else if detail == nil && presentation != nil { TVLoading("正在刷新详情") }
                        if !series {
                            if let mediaError { RetryMessage(text: mediaError) { mediaReload += 1 } }
                            else if !versionsReady { TVLoading("正在获取播放信息") }
                            else if versions.isEmpty { Text("暂无可播放版本").foregroundStyle(.secondary) }
                            else {
                                VStack(alignment: .leading, spacing: 18) {
                                    Text("版本").font(.system(size: 22)).foregroundStyle(.secondary)
                                    TVChoice(title: selectedVersion?.container?.uppercased() ?? "版本", selection: $version,
                                        options: [("", "自动（核心偏好）")] + versions.map { ($0.id, $0.name) })
                                    if let selectedVersion {
                                        HStack(spacing: 20) {
                                            Text(selectedVersion.name)
                                            if let size = selectedVersion.size_bytes { Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) }
                                            if let rate = selectedVersion.bitrate { Text(String(format: "%.1f Mbps", Double(rate) / 1_000_000)) }
                                        }.font(.system(size: 22)).foregroundStyle(.secondary)
                                        if let path = selectedVersion.displayPath {
                                            Text(path).font(.system(size: 21)).foregroundStyle(.secondary).lineLimit(3)
                                        }
                                    }
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                                    .overlay { RoundedRectangle(cornerRadius: 8).strokeBorder(.primary.opacity(0.2), lineWidth: 1) }
                                if let selectedVersion, let streams = selectedVersion.streams {
                                    ForEach(streams.filter { $0.type_ == "Video" }) { stream in
                                        Label(stream.summary, systemImage: "video").font(.system(size: 24)).foregroundStyle(.secondary)
                                    }
                                    if streams.contains(where: { $0.type_ == "Audio" }) {
                                        TVChoice(title: "音轨", selection: $selectedAudio,
                                            options: [(nil, "按偏好自动选择")] + streams.filter { $0.type_ == "Audio" }.map { (Optional($0.index), $0.label) })
                                    }
                                    TVChoice(title: "字幕", selection: $selectedSubtitle,
                                        options: [(nil, "按偏好自动选择"), (-1, "关闭字幕")] + streams.filter { $0.type_ == "Subtitle" && ["srt", "subrip", "vtt", "webvtt", "mov_text", "text"].contains($0.codec.lowercased()) }.map { (Optional($0.index), $0.label) })
                                    ScrollView(.horizontal) {
                                        LazyHStack(alignment: .top, spacing: 20) {
                                            ForEach(streams) { stream in
                                                VStack(alignment: .leading, spacing: 12) {
                                                    Text(stream.type_ == "Video" ? "视频" : stream.type_ == "Audio" ? "音轨" : "字幕").font(.system(size: 22)).foregroundStyle(.secondary)
                                                    Text(stream.label).font(.system(size: 25, weight: .semibold)).lineLimit(2)
                                                    Text(stream.summary).font(.system(size: 22)).foregroundStyle(.secondary)
                                                    if stream.is_external { Text("外挂").font(.system(size: 20)).foregroundStyle(.secondary) }
                                                }.frame(width: 360, alignment: .leading).padding(24)
                                                    .background(.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 14))
                                                    .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(.primary.opacity(focusedStream == stream.index ? 0.65 : 0), lineWidth: 2) }
                                                    .focusable().focused($focusedStream, equals: stream.index).focusEffectDisabled()
                                            }
                                        }.padding(.vertical, 6)
                                    }
                                }
                                if let account = model.account, (account.lines?.count ?? 0) > 1, !model.privateSpace {
                                    NavigationLink("线路 · " + account.name) { AccountEditor(api: api, account: account) }
                                }

                            }
                        }
                        if !series && versionsReady && !versions.isEmpty {
                                HStack(spacing: 28) {
                                    Button((detail?.resume_secs ?? current.resume_secs) > 0 ? "继续播放" : "播放") { play(fromStart: false) }
                                        .buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled()
                                    if (detail?.resume_secs ?? current.resume_secs) > 0 { Button("从头播放") { play(fromStart: true) }.buttonStyle(TVActionStyle()).focusEffectDisabled() }
                                }.disabled(model.playback != nil || detail == nil)
                        }
                        if let detail {
                            HStack(spacing: 28) {
                                Button(detail.is_favorite ? "取消收藏" : "收藏") { update("emby.setFavorite", key: "fav", value: !detail.is_favorite) }
                                Button(detail.played ? "标为未看" : "标为已看") { update("emby.setPlayed", key: "played", value: !detail.played) }
                                if detail.resume_secs > 0 { Button("从继续观看中隐藏") { update("emby.hideResume", key: "hide", value: true) } }
                            }.buttonStyle(TVActionStyle()).focusEffectDisabled().disabled(writing)
                        }
                        if let actionError { Text(actionError).foregroundStyle(.orange) }
                }.buttonStyle(TVActionStyle()).focusEffectDisabled().padding(.horizontal, 64)
                if let shown = presentation, !shown.overview.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        if let tagline = shown.tagline, !tagline.isEmpty { Text(tagline).font(.system(size: 26, weight: .medium)) }
                        Button { expandedOverview.toggle() } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(shown.overview).font(.system(size: 25)).lineSpacing(7).lineLimit(expandedOverview ? nil : 3)
                                Text(expandedOverview ? "收起简介" : "展开简介").font(.system(size: 22)).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                    }.padding(.horizontal, 64)
                }
                if series {
                    if let seasons {
                        EpisodeBrowser(api: api, parent: season.isEmpty ? current.id : season, origin: item.id, seasonName: seasons.first { $0.id == season }?.name ?? "",
                            onComplete: { parent, page, revision in
                                guard parent == season, revision == model.userStateRevision else { return }
                                if calibrationRevision != revision { confirmedSeasons = [:]; calibrationRevision = revision }
                                confirmedSeasons[parent] = page
                            })
                            .id(season)
                        if !seasons.isEmpty {
                            TVSectionHeading("季").padding(.horizontal, 64)
                            ScrollView(.horizontal) {
                                HStack(alignment: .top, spacing: 24) {
                                    ForEach(seasons) { raw in
                                        let entry = calibrated(raw)
                                        Button { season = entry.id } label: {
                                            VStack(alignment: .leading, spacing: 10) {
                                                MediaArtwork(item: entry.artwork(series: item.id), loader: model.images, width: 190, height: 285)
                                                    .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(focusedSeason == entry.id ? TVPalette.foreground(model.privateSpace).opacity(0.65) : TVPalette.accent(model.privateSpace, palette: model.colorScheme).opacity(season == entry.id ? 1 : 0), lineWidth: 2) }
                                                    .scaleEffect(focusedSeason == entry.id ? 1.04 : 1)
                                                Text(entry.name).font(.system(size: 24, weight: .medium)).lineLimit(2)
                                                    .foregroundStyle(season == entry.id ? TVPalette.accent(model.privateSpace, palette: model.colorScheme) : TVPalette.foreground(model.privateSpace))
                                                if let status = entry.statusText { Text(status).font(.system(size: 20)).foregroundStyle(.secondary) }
                                            }.frame(width: 190)
                                        }.buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focusedSeason, equals: entry.id)
                                            .contextMenu {
                                                Button(entry.played == true ? "整季标记为未看" : "整季标记为已看") {
                                                    model.setItemState(entry.id, played: entry.played != true)
                                                }.disabled(model.writingItems.contains(entry.id))
                                            }
                                    }
                                }.padding(.horizontal, 64).padding(.vertical, 20)
                            }
                        }
                    } else if let mediaError { RetryMessage(text: mediaError) { mediaReload += 1 } }
                    else { TVLoading("正在加载分季") }
                }
                if let people = presentation?.people, !people.isEmpty {
                    TVSectionHeading("演职人员").padding(.horizontal, 64)
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 28) {
                            ForEach(people) { person in
                                NavigationLink(value: MediaItem.route(id: person.id, name: person.name, type: "Person")) {
                                    VStack {
                                        Poster(id: person.id, enabled: person.has_primary, loader: model.images, width: 176, height: 176).clipShape(Circle())
                                        Text(person.name).font(.system(size: 26, weight: .bold)).lineLimit(2)
                                        if let role = person.role, !role.isEmpty { Text(role).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(2) }
                                    }.frame(width: 192).multilineTextAlignment(.center)
                                }.buttonStyle(TVCardStyle()).focusEffectDisabled().disabled(person.id.isEmpty)
                            }
                        }.padding(.horizontal, 64)
                    }.frame(height: 330)
                }
                MediaSection(api: api, title: "相似内容", command: "emby.similarItems", args: ["item_id": .string(current.id), "limit": .number(16)])
            }
        }
        .ignoresSafeArea(.container)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: "\(current.id):\(reload):\(model.userStateRevision)") {
            detail = nil
            if presentation?.id != current.id { presentation = nil }
            do {
                let value = try await api.request("emby.itemDetail", ["item_id": .string(current.id), "with_children": .bool(false)], snapshot: { value in
                    if let cached = try? value.decode(DetailPresentation.self), cached.id == current.id { presentation = cached }
                })
                let live = try value.decode(MediaDetail.self)
                let shown = try value.decode(DetailPresentation.self)
                detail = live; presentation = shown; error = nil
            } catch is CancellationError {} catch {
                if invalidatesDisplay(error) { presentation = nil }
                self.error = error.localizedDescription
            }
        }
        .onChange(of: model.returnRevision) { _, _ in
            if item.type_ == "Episode", model.returnedOrigin == item.id, let returned = model.returnedItem {
                playbackItem = returned; selectedSubtitle = nil; selectedAudio = nil; version = ""; versions = []; versionsReady = false
            }
        }
        .onChange(of: version) { _, _ in selectedSubtitle = nil; selectedAudio = nil }
        .task(id: "\(current.id):\(mediaReload):\(model.userStateRevision)") {
            mediaError = nil
            do {
                if series {
                    let list = try await api.request("emby.seriesSeasons", ["series_id": .string(current.id)]).decode([MediaSeason].self)
                    seasons = list; if season.isEmpty { season = list.first?.id ?? "" }
                } else {
                    let prefs = try await api.core.call("prefs.getPrefs", [:])
                    versions = try await api.request("emby.itemMedia", ["item_id": .string(current.id), "version_regex": prefs["version_regex"]]).decode([MediaVersion].self)
                    versionsReady = true
                }
            } catch is CancellationError {} catch { mediaError = error.localizedDescription }
        }
    }

    private var episodeNumber: String {
        let season = current.season_no ?? detail?.season_no
        let episode = current.episode_no ?? detail?.episode_no
        guard let episode else { return "" }
        return (season.map { "S\($0)" } ?? "") + "E\(episode)："
    }

    private func calibrated(_ season: MediaSeason) -> MediaSeason {
        guard calibrationRevision == model.userStateRevision, let page = confirmedSeasons[season.id] else { return season }
        return season.calibrated(page) ?? season
    }

    @ViewBuilder private func facetLink(_ label: String, query: BrowseQuery, available: Bool = true) -> some View {
        if detail != nil && detail?.capabilities?["filters"] != false && available {
            NavigationLink {
                ItemGrid(api: api, parent: "", episodes: false, query: query).navigationTitle(label)
            } label: { Text(label).font(.system(size: 22)) }
                .buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
        } else { Text(label).font(.system(size: 22)).foregroundStyle(.secondary) }
    }

    private func update(_ command: String, key: String, value: Bool) {
        guard !writing else { return }
        writing = true
        Task {
            defer { writing = false }
            do { _ = try await api.request(command, ["item_id": .string(current.id), key: .bool(value)]); actionError = nil }
            catch is CancellationError {} catch { actionError = error.localizedDescription }
        }
    }

    private func play(fromStart: Bool) {
        var subtitle: InitialSubtitle?
        if let selectedSubtitle, let selectedVersion {
            subtitle = InitialSubtitle(version: selectedVersion.id, stream: selectedVersion.streams?.first { $0.type_ == "Subtitle" && $0.index == selectedSubtitle })
        }
        let audio = selectedAudio.flatMap { index in selectedVersion.flatMap { source in
            source.streams?.first { $0.type_ == "Audio" && $0.index == index }.map { InitialAudio(version: source.id, stream: $0) }
        } }
        model.openPlayer(item: current.id, title: current.title, version: version.isEmpty ? nil : version, fromStart: fromStart, subtitle: subtitle, audio: audio, origin: item.id)
    }
}

struct EpisodeBrowser: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let parent: String
    let origin: String
    var seasonName = ""
    var onComplete: ((String, MediaPage, Int) -> Void)? = nil
    @FocusState private var focusedEpisode: String?
    @State private var positioned = false
    @State private var episodes: [MediaItem] = []
    @State private var staged: [MediaItem] = []
    @State private var cursor = 0
    @State private var total: Int?
    @State private var stagedRevision = -1
    @State private var complete = false
    @State private var loading = true
    @State private var error: String?
    @State private var reload = 0
    @State private var generation = UUID()
    private var next: MediaItem? {
        guard complete else { return nil }
        if model.returnedOrigin == origin, let returned = model.returnedItem,
           let item = episodes.first(where: { $0.id == returned.id }) { return item }
        return episodes.first { $0.played != true } ?? episodes.first
    }
    private var currentID: String? {
        guard model.returnedOrigin == origin, let id = model.returnedItem?.id,
              episodes.contains(where: { $0.id == id }) else { return nil }
        return id
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack {
                TVSectionHeading("继续观看")
                Spacer()
                Text(seasonName).font(.system(size: 24, weight: .medium)).foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme))
            }
            Button(next.map { ($0.resume_secs > 0 ? "继续播放 · " : "播放 · ") + $0.name } ?? (loading ? "正在准备播放" : "暂无可播放分集")) {
                if let next {
                    model.openPlayer(item: next.id, title: next.title, version: nil, fromStart: false, origin: origin)
                }
            }.buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled().disabled(next == nil || model.playback != nil)
            Text(complete ? "共 \(episodes.count) 集 · 已看 \(episodes.filter { $0.played == true }.count) 集 · 未看 \(episodes.filter { $0.played != true }.count) 集" : "已加载 \(episodes.count) 集")
                .font(.system(size: 22)).foregroundStyle(.secondary)
            HStack { if loading { TVLoading("正在加载当前季") }; Spacer() }.frame(height: 40)
            if let error { RetryMessage(text: error) { reload += 1 } }
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 28) {
                        ForEach(episodes) { episode in
                            NavigationLink(value: episode) { MediaCard(item: episode, images: model.images, wide: true, episodeCaption: true, currentEpisode: episode.id == currentID, targetEpisode: episode.id == next?.id) }
                                .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focusedEpisode, equals: episode.id).id(episode.id)
                        }
                    }.padding(.vertical, 16)
                }.frame(height: 380).focusSection()
                .onChange(of: complete) { _, value in
                    if value && !positioned {
                        positioned = true
                        if focusedEpisode == nil, let id = currentID ?? next?.id { proxy.scrollTo(id, anchor: .leading) }
                    }
                }
            }
            if complete && episodes.isEmpty { Text("当前季暂无分集").foregroundStyle(.secondary) }
        }.padding(.horizontal, 64).padding(.vertical, 28)
        .task(id: "\(model.userStateRevision):\(reload)") {
            let revision = model.userStateRevision
            if stagedRevision != revision { staged = []; cursor = 0; total = nil; stagedRevision = revision }
            complete = false; loading = true; error = nil
            let request = UUID(); generation = request
            defer { if generation == request { loading = false } }
            do {
                while total == nil || cursor < total! {
                    let page = try await api.episodes(parent: parent, start: cursor)
                    try Task.checkCancellation()
                    guard generation == request, revision == model.userStateRevision else { return }
                    if page.items.isEmpty && cursor < page.total { throw CoreFailure(code: "E_PROTOCOL") }
                    staged = try MediaAPI.appendEpisodes(staged, page: page.items)
                    cursor += page.items.count; total = page.total
                    episodes = staged
                    if page.items.isEmpty { break }
                }
                try Task.checkCancellation()
                guard generation == request, revision == model.userStateRevision else { return }
                guard staged.count == total else { throw CoreFailure(code: "E_PROTOCOL") }
                episodes = staged; complete = true
                onComplete?(parent, MediaPage(items: staged, total: total ?? 0), revision)
            } catch is CancellationError {} catch { if generation == request { self.error = error.localizedDescription } }
        }
    }
}
