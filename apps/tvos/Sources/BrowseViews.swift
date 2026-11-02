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
    @State private var updateChecked = false
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
        .fullScreenCover(isPresented: Binding(get: { model.playback != nil || model.liuying != nil }, set: { value in
            if !value { Task { await model.closePlayer() } }
        })) {
            if let session = model.liuying { LiuYingStage(session: session).environmentObject(model) }
            else if let controller = model.playback { PlayerView(controller: controller).environmentObject(model).id(ObjectIdentifier(controller)) }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                Text(toast).font(.system(size: 26, weight: .medium)).padding(.horizontal, 32).padding(.vertical, 16)
                    .background(.black.opacity(0.8), in: Capsule()).foregroundStyle(.white).padding(.bottom, 80)
                    .allowsHitTesting(false)
                    .task(id: toast) {
                        do { try await Task.sleep(for: .seconds(2)) } catch { return }
                        model.toast = nil
                    }
            }
        }
        .alert("提示", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("知道了") { model.notice = nil }
        } message: { Text(model.notice ?? "") }
        // 启动时自动检查更新：登录后延后执行，失败静默；tvOS 只提示，不提供安装器。
        .task(id: model.api != nil) {
            guard let api = model.api, !updateChecked else { return }
            updateChecked = true
            do { try await Task.sleep(for: .seconds(6)) } catch { return }
            guard (try? await api.request("prefs.getUpdateSettings"))?["auto_check"].bool == true,
                  let result = try? await api.request("system.checkUpdate"), result["has_update"].bool == true,
                  model.playback == nil, model.liuying == nil, model.notice == nil else { return }
            model.notice = "发现新版本 \(result["update"]["version"].string ?? "")。Apple TV 请通过原安装渠道更新应用。"
        }
    }
}

private struct TVPageActiveKey: EnvironmentKey { static let defaultValue = true }
extension EnvironmentValues {
    var tvPageActive: Bool {
        get { self[TVPageActiveKey.self] }
        set { self[TVPageActiveKey.self] = newValue }
    }
    var tvPosterWidth: CGFloat {
        get { self[TVPosterWidthKey.self] }
        set { self[TVPosterWidthKey.self] = newValue }
    }
}
private struct TVPosterWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 240 }

// 按实际内容宽度排布；搜索右栏与全宽网格沿用同一刻度。
func tvPosterWidth(_ contentWidth: CGFloat) -> CGFloat {
    let columns = max(1, Int(ceil((max(1, contentWidth) + 20) / 296)))
    return max(1, (contentWidth - CGFloat(columns - 1) * 20) / CGFloat(columns))
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
    @State private var showingCompanion = false
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    var body: some View {
        HStack(alignment: .top, spacing: 64) {
        VStack(spacing: 28) {
            Text("LinPlayer").font(.largeTitle.bold())
            Text("连接 Emby 服务器 · 推荐用手机扫右边的码").foregroundStyle(.secondary)
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
            Button("使用手机添加服务器") { showingCompanion = true }.buttonStyle(TVActionStyle())
        }.frame(maxWidth: 720)
        CompanionPairBlock().padding(.top, 24)
        }.padding(60).sheet(isPresented: $showingCompanion) { CompanionView() }
    }
}

func invalidatesDisplay(_ error: Error) -> Bool {
    guard let failure = error as? CoreFailure else { return false }
    return ["E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_UNSUPPORTED", "E_INVALID"].contains(failure.code)
}

// 换源：在其它允许聚合的服务器里找同一部，边找边出；选中后切服并打开目标详情，私密空间不跨服。
struct SwitchSourceView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let api: MediaAPI
    let title: String
    let year: Int?
    @State private var rows: [SwitchCandidate] = []
    @State private var done = false
    @State private var failure: String?
    @State private var reload = 0
    @State private var picking = false
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            TVSectionHeading("换源")
            if !done { TVLoading("正在查找其它服务器…") }
            else if rows.isEmpty && failure == nil { Text("其它服务器里都没找到这部片。可以给更多服务器打开「允许聚合」。").foregroundStyle(.secondary) }
            if picking { Text("正在切换…").foregroundStyle(.secondary) }
            if let failure { RetryMessage(text: failure) { reload += 1 } }
            ScrollView { LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(rows) { row in
                    Button { pick(row) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(row.emby_item?.name ?? "")
                            Text(row.server_name + " · " + (row.tier == 0 ? "同一部" : row.tier == 1 ? "可能是" : "不像")).font(.system(size: 22)).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(TVActionStyle()).focusEffectDisabled().disabled(picking || model.busy || row.emby_item == nil)
                }
            } }
        }.padding(64).frame(width: 1100, alignment: .leading)
            .task(id: reload) {
                rows = []; done = false; failure = nil
                do { rows = try await api.switchCandidates(title: title, year: year) { rows = $0 } }
                catch is CancellationError { return } catch { failure = error.localizedDescription }
                done = true
            }
            .tvExitCommand { if !picking { dismiss() } }
    }
    private func pick(_ row: SwitchCandidate) {
        guard !picking, !model.privateSpace, let item = row.emby_item else { return }
        let server = api.session?.server
        picking = true
        Task {
            defer { picking = false }
            do {
                guard model.api?.session?.server == server else { throw CoreFailure(code: "E_INVALID") }
                try await model.switchServer(row.server_id, opening: item)
                dismiss()
            } catch { failure = error.localizedDescription }
        }
    }
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
        Text(title).font(.system(size: 30, weight: .semibold))
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
    // 只有续播行显示剩余时长角标（Android Cards.kt resume）。
    var resume = false
    var onHideResume: (() -> Void)? = nil
    // 跨服搜索结果的来源服务器：菜单写入带 server_id。
    var serverID: String? = nil
    @EnvironmentObject private var model: AppModel
    @Environment(\.isFocused) private var focused
    @Environment(\.tvPosterWidth) private var posterWidth
    private var width: CGFloat { wide ? posterWidth * 1.4 : posterWidth }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MediaArtwork(item: item, loader: images, width: width, height: wide ? width * 9 / 16 : width * 1.5, server: imageServer)
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
                    if resume, let time = item.resumeTimeText {
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
                .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(focused ? Color.white.opacity(0.95) : TVPalette.accent(model.privateSpace, palette: model.colorScheme).opacity(currentEpisode || targetEpisode ? 1 : 0), lineWidth: focused ? 3 : 2) }
                .shadow(color: .black.opacity(focused ? 0.65 : 0), radius: 24, y: 16)
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
            .scaleEffect(focused ? 1 + min(0.1, 16 / width) : 1, anchor: UnitPoint(x: 0.5, y: 0.7))
            .offset(y: focused ? -12 : 0)
            .animation(.easeOut(duration: 0.16), value: focused)
            .contextMenu {
                if item.type_ != "Person", imageServer == nil || imageServer == model.api?.session?.server || serverID != nil {
                    Button(item.played == true ? "标为未看" : "标为已看") {
                        model.setItemState(item.id, played: item.played != true, server: serverID)
                    }.disabled(model.writingItems.contains(item.id))
                    // 分集横轨与 Android 分集行一致不提供收藏。
                    if !episodeCaption {
                        Button(item.is_favorite == true ? "取消收藏" : "收藏") {
                            model.setItemState(item.id, favorite: item.is_favorite != true, server: serverID)
                        }.disabled(model.writingItems.contains(item.id))
                    }
                    if let onHideResume { Button("取消观看记录", action: onHideResume) }
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
struct TVCardStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        TVRemoteButton(action: configuration.trigger) { configuration.label }
    }
}

struct TVActionStyle: PrimitiveButtonStyle {
    var primary = false
    var compact = false
    func makeBody(configuration: Configuration) -> some View {
        TVRemoteButton(action: configuration.trigger) {
            TVActionLabel(primary: primary, compact: compact, destructive: configuration.role == .destructive) { configuration.label }
        }
    }
}

private struct TVActionLabel<Content: View>: View {
    let primary: Bool
    let compact: Bool
    let destructive: Bool
    @Environment(\.tvPressed) private var pressed
    @ViewBuilder let content: () -> Content
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    @EnvironmentObject private var model: AppModel
    var body: some View {
        content().font(.system(size: 25, weight: .medium)).padding(.horizontal, compact ? 18 : 28).frame(height: compact ? 46 : 72)
            .foregroundStyle(destructive ? Color.red : focused || primary ? TVPalette.background(model.privateSpace) : TVPalette.foreground(model.privateSpace))
            .background(focused ? TVPalette.foreground(model.privateSpace) : primary ? TVPalette.accent(model.privateSpace, palette: model.colorScheme) : TVPalette.foreground(model.privateSpace).opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(focused ? 0.25 : 0), radius: 10, y: 6)
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
    @State private var overviews: [String: String] = [:]
    @State private var progress: CGFloat = 0
    @State private var selected = ""
    // 播放顺序：到末尾整体重洗，首张与当前相同则与第二张互换（Android HomePage advance）。
    @State private var order: [String] = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var ids: [String] {
        let current = Set(items.map(\.id))
        let kept = order.filter(current.contains)
        return kept + items.map(\.id).filter { !kept.contains($0) }
    }
    private var item: MediaItem { items.first { $0.id == selected } ?? items[0] }
    private var timerIdentity: String { "\(item.id):\(active):\(model.sceneActive):\(reduceMotion):\(items.count):\(model.playback != nil)" }
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Backdrop(item: item, loader: model.images, height: height, whiteForeground: true)
            VStack(alignment: .leading, spacing: 18) {
                if let logo {
                    Image(uiImage: logo).resizable().scaledToFit().frame(maxWidth: 620, maxHeight: 180, alignment: .leading)
                        .accessibilityLabel(item.name)
                } else {
                    Text(item.type_ == "Episode" ? item.series_name ?? item.name : item.name)
                        .font(.system(size: 52, weight: .bold)).lineLimit(2)
                }
                HStack(spacing: 18) {
                    if let score = item.ratingText() { Label(score, systemImage: "star.fill").foregroundStyle(.yellow) }
                    if (item.DoubanRating ?? 0) > 0, let score = item.ratingText(douban: true) { Text("豆瓣 " + score).foregroundStyle(.green) }
                    Text(([item.year.map(String.init)].compactMap { $0 } + (item.genres ?? []).prefix(2)).joined(separator: " · "))
                        .foregroundStyle(.white.opacity(0.8))
                }.font(.system(size: 22)).lineLimit(1)
                if let overview = overviews[item.id], !overview.isEmpty {
                    Text(overview).font(.system(size: 22)).foregroundStyle(.white.opacity(0.8)).lineLimit(2).frame(maxWidth: 900, alignment: .leading)
                }
            }.padding(.horizontal, 64).padding(.bottom, 58)
        }.frame(height: height).foregroundStyle(.white).allowsHitTesting(false)
        .task(id: "\(item.id):\(item.has_logo == true):\(model.images.revision)") {
            logo = nil
            if item.has_logo == true {
                let image = await model.images.load(id: item.id, kind: "Logo", height: 240)
                guard !Task.isCancelled else { return }; logo = image
            }
        }
        // 列表数据不含简介；当前与下一张一起读取，轮换时文字不再跳动。
        .task(id: item.id) {
            let ids = self.ids
            guard let api = model.api, let index = ids.firstIndex(of: item.id) else { return }
            let upcoming = ids.count > 1 ? items.filter { $0.id == ids[(index + 1) % ids.count] } : []
            for entry in [item] + upcoming where overviews[entry.id] == nil {
                let value = try? await api.request("emby.itemDetail", ["item_id": .string(entry.series_id ?? entry.id), "with_children": .bool(false)],
                    snapshot: { cached in if let text = cached["overview"].string { overviews[entry.id] = text } })
                guard !Task.isCancelled else { return }
                if let value { overviews[entry.id] = value["overview"].string ?? "" }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if items.count > 1 {
                // 指示点最多 6 个，按顺序位置取模（Android home.hero.indicators）。
                let count = min(items.count, 6)
                let active = (ids.firstIndex(of: item.id) ?? 0) % count
                HStack(spacing: 14) {
                    ForEach(0..<count, id: \.self) { page in
                        Capsule().fill(.white.opacity(0.25)).frame(width: page == active ? 72 : 32, height: 6)
                            .overlay(alignment: .leading) {
                                if page == active { Capsule().fill(.white).frame(width: 72 * progress, height: 6) }
                            }
                    }
                }.padding(.trailing, 64).padding(.bottom, 24).accessibilityHidden(true)
            }
        }
        .task(id: timerIdentity) {
            progress = 0
            guard active, model.sceneActive, !reduceMotion, items.count > 1, model.playback == nil else { return }
            do {
                withAnimation(.linear(duration: 5)) { progress = 1 }
                try await Task.sleep(for: .seconds(5))
                try Task.checkCancellation()
                let ids = self.ids
                let index = ids.firstIndex(of: item.id) ?? 0
                if index == ids.count - 1 {
                    var next = ids.shuffled()
                    if next.first == item.id { next.swapAt(0, 1) }
                    order = next; selected = next[0]
                } else { order = ids; selected = ids[index + 1] }
            } catch {}
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    var active = true
    var refreshToken = 0
    var topToken = 0
    var onScroll: (CGFloat) -> Void = { _ in }
    var onTop: () -> Void = {}
    @State private var resumeLoading = true
    @State private var librariesLoading = true
    @State private var heroVisible = true
    @State private var scrolling = false
    @AppStorage("hideLibraryPosters") private var hideLibraryPosters = false
    @State private var libraries: [MediaItem] = []
    @State private var librariesLoaded = false
    @State private var bannerError: String?
    @State private var bannerResolved = false
    @State private var resume: [MediaItem] = []
    @State private var resumeError: String?
    @State private var canHideResume = false
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
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                Color.clear.frame(height: 0).id("home-top")
                if !banners.isEmpty { HomeBanner(items: banners, active: active && heroVisible && !scrolling, height: max(560, min(760, geometry.size.height * 0.64))) }
                else if bannerResolved { Color.clear.frame(height: 140) }
                else { RoundedRectangle(cornerRadius: 0).fill(.primary.opacity(0.035)).frame(height: max(560, min(760, geometry.size.height * 0.64))) }
                if let bannerError { RetryMessage(text: bannerError) { reload += 1 } }
                if let libraryError { RetryMessage(text: libraryError) { reload += 1 } }
                else if librariesLoaded && libraries.isEmpty && !hideLibraryPosters {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("这个账号下没有媒体库").font(.system(size: 30, weight: .semibold))
                        Text("在服务器上建一个库，或者换一台服务器试试。").foregroundStyle(.secondary)
                    }.padding(.horizontal, 64)
                }
                if !hideLibraryPosters { ScrollView(.horizontal) {
                    LazyHStack(spacing: 20) {
                        ForEach(libraries) { library in
                            NavigationLink(value: library) {
                                LibraryCard(item: library, images: model.images)
                            }.buttonStyle(TVCardStyle()).focusEffectDisabled().accessibilityLabel(library.name)
                        }
                    }.padding(.horizontal, 64).padding(.vertical, 12)
                }.onMoveCommand { direction in
                    if direction == .up { proxy.scrollTo("home-top", anchor: .top); onTop() }
                } }
                if !resume.isEmpty {
                    if !hideLibraryPosters { TVSectionHeading("继续观看").padding(.horizontal, 64) }
                    MediaRow(items: resume, wide: true, resume: true, onHideResume: canHideResume ? hideResume : nil)
                        .onMoveCommand { direction in
                            if direction == .up && (hideLibraryPosters || libraries.isEmpty) {
                                proxy.scrollTo("home-top", anchor: .top); onTop()
                            }
                        }
                }
                if let resumeError { RetryMessage(text: resumeError) { reload += 1 } }
                ForEach(libraries) { library in
                    MediaSection(api: api, title: library.name, command: "emby.listLatest",
                        args: ["parent_id": .string(library.id), "limit": .number(16)], library: library,
                        onLoaded: { items in latest[library.id] = items }, refreshToken: reload)
                }
                if collectionsEnabled == true { MediaSection(api: api, title: "合集", command: "emby.listCollections", args: [:], onLoaded: { collections = $0 }, refreshToken: reload) }
                if let configError { RetryMessage(text: configError) { reload += 1 } }
            }.padding(.bottom, 40)
        }
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, value in heroVisible = value < 500; onScroll(value) }
        .onScrollPhaseChange { _, phase in scrolling = phase != .idle }
        .onChange(of: topToken) { _, _ in proxy.scrollTo("home-top", anchor: .top) }
        .onChange(of: refreshToken) { _, _ in
            guard !resumeLoading, !librariesLoading else { return }
            resumeLoading = true; librariesLoading = true; reload += 1
        }
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
                banners = retained + candidates.filter { !ids.contains($0.id) }.shuffled()
                bannerError = nil; bannerResolved = true
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { banners = [] }; bannerError = error.localizedDescription; bannerResolved = true }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            resumeLoading = true; defer { resumeLoading = false }
            do {
                resume = try await api.request("emby.listResume", ["limit": .number(12)], snapshot: { value in if let cached = try? value.decode([MediaItem].self) { resume = cached } }).decode([MediaItem].self)
                resumeError = nil
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { resume = [] }; resumeError = error.localizedDescription }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            librariesLoading = true; defer { librariesLoading = false }
            do { libraries = try await api.request("emby.views", ["home": .bool(true)], snapshot: { value in if let cached = try? value.decode([MediaItem].self) { libraries = cached } }).decode([MediaItem].self); librariesLoaded = true; libraryError = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { libraries = []; latest = [:] }; librariesLoaded = true; libraryError = error.localizedDescription }
        }
        .task {
            // 与 Android 一致：权限读取失败不显示；服务端未声明能力时按可用处理。
            guard let permissions = try? await api.request("emby.permissions") else { return }
            canHideResume = permissions["capabilities"]["hide_resume"].bool != false
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
    // 只移出继续观看，不改已看状态和进度。
    private func hideResume(_ item: MediaItem) {
        Task {
            do {
                _ = try await api.request("emby.hideResume", ["item_id": .string(item.id), "hide": .bool(true)])
                resume.removeAll { $0.id == item.id }
                model.toast = "已从继续观看中移除"
            } catch is CancellationError {} catch { model.notice = error.localizedDescription }
        }
    }
}

struct MediaRow: View {
    @EnvironmentObject private var model: AppModel
    let items: [MediaItem]
    var wide = false
    var inset: CGFloat = 64
    var resume = false
    var onHideResume: ((MediaItem) -> Void)? = nil
    @Environment(\.tvPosterWidth) private var posterWidth
    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 20) {
                ForEach(items) { item in
                    NavigationLink(value: item) { MediaCard(item: item, images: model.images, wide: wide, resume: resume, onHideResume: onHideResume == nil ? nil : { onHideResume?(item) }) }
                        .buttonStyle(TVCardStyle()).focusEffectDisabled().id(item.id)
                }
            }.padding(.horizontal, inset).padding(.vertical, 20)
        }.frame(height: (wide ? posterWidth * 1.4 * 9 / 16 : posterWidth * 1.5) + 112).focusSection()
    }
}

struct MediaSection: View {
    @Environment(\.tvPosterWidth) private var posterWidth
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let title: String
    let command: String
    let args: [String: JSONValue]
    var library: MediaItem? = nil
    var onLoaded: (([MediaItem]) -> Void)? = nil
    var refreshToken = 0
    @State private var items: [MediaItem]?
    @State private var error: String?
    @State private var reload = 0
    @State private var loadedKey = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
        if command != "emby.listLatest" || items?.isEmpty != true || error != nil {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                if let library {
                    NavigationLink(value: library) { HStack { TVSectionHeading(title); Image(systemName: "chevron.right").font(.system(size: 22)) } }
                        .buttonStyle(TVCardStyle()).focusEffectDisabled()
                } else { TVSectionHeading(title) }
                Spacer()
            }.padding(.horizontal, 64)
            if let error { RetryMessage(text: error) { reload += 1 } }
            if let items {
                if items.isEmpty { Text("暂无内容").foregroundStyle(.secondary).padding(.horizontal, 64) }
                else { MediaRow(items: items) }
            } else if error == nil { ScrollView(.horizontal) {
                HStack(spacing: 20) {
                    ForEach(0..<6) { _ in RoundedRectangle(cornerRadius: 12).fill(.primary.opacity(0.05)).frame(width: posterWidth, height: posterWidth * 1.5) }
                }.padding(.horizontal, 64)
            }.frame(height: posterWidth * 1.5 + 112).allowsHitTesting(false).accessibilityLabel("正在加载") }
        }
        }
        }
        .task(id: "\(reload):\(refreshToken):\(model.userStateRevision)") {
            let key = "\(reload):\(refreshToken):\(model.userStateRevision)"
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
    private let query = BrowseQuery(sort: "DateLastContentAdded", descending: true)
    @State private var loaded = 0
    @State private var more = true
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.name).font(.system(size: 38, weight: .semibold))
                    Text("已加载 \(loaded)" + (more ? "+" : "")).font(.system(size: 22)).foregroundStyle(.secondary)
                }
            }.padding(.horizontal, 64)
            ItemGrid(api: api, parent: item.id, episodes: false, query: query, onLoaded: { count, hasMore in loaded = count; more = hasMore })
        }.padding(.top, 28).toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal)
    }

}

struct ItemGrid: View {
    @Environment(\.tvPosterWidth) private var posterWidth
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let parent: String
    let episodes: Bool
    var query = BrowseQuery()
    var onLoaded: ((Int, Bool) -> Void)? = nil
    @StateObject private var pager = ItemPager()
    @FocusState private var focus: String?
    @State private var lastFocus: String?
    @State private var reload = 0

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: posterWidth), spacing: 20)], alignment: .leading, spacing: 40) {
                    ForEach(pager.items) { item in
                        NavigationLink(value: item) { MediaCard(item: item, images: model.images) }
                            .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focus, equals: item.id).id(item.id)
                    }
                }.padding(.horizontal, 64).padding(.vertical, 28)
                if let error = pager.error { RetryMessage(text: error) { reload += 1 } }
                else if pager.loading { TVLoading("正在加载") }
                else if pager.items.isEmpty { Text("暂无内容").foregroundStyle(.secondary) }
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

private struct TVOverview: View {
    let text: String
    @State private var fullHeight: CGFloat = 0
    @State private var shortHeight: CGFloat = 0
    @State private var showing = false
    private var label: some View {
        Text(text).font(.system(size: 25)).lineSpacing(7).lineLimit(2)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { shortHeight = $0 }
            .background(alignment: .topLeading) {
                Text(text).font(.system(size: 25)).lineSpacing(7).fixedSize(horizontal: false, vertical: true)
                    .hidden().onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
            }
    }
    var body: some View {
        Group {
            if fullHeight > shortHeight + 1 {
                Button { showing = true } label: { label }.buttonStyle(TVCardStyle()).accessibilityHint("查看完整简介")
            } else { label }
        }.sheet(isPresented: $showing) {
            ScrollView { VStack(alignment: .leading, spacing: 28) {
                TVSectionHeading("简介")
                Text(text).font(.system(size: 28)).lineSpacing(10)
                Button("返回") { showing = false }.buttonStyle(TVActionStyle())
            }.padding(64) }.tvExitCommand { showing = false }
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
    @State private var switchingSource = false
    private var current: MediaItem { playbackItem ?? item }
    private var selectedVersion: MediaVersion? { versions.first { $0.id == version } ?? versions.first { $0.preferred } ?? versions.first }
    private var series: Bool { ["Series", "Season"].contains(item.type_) }
    @State private var episodeTarget: MediaItem?
    @State private var mediaItemID = ""
    private var mediaItem: MediaItem? { series ? episodeTarget : current }
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
        GeometryReader { geometry in
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(alignment: .leading, spacing: 22) {
                    if current.type_ == "Episode", let id = current.series_id ?? detail?.series_id {
                        NavigationLink(value: MediaItem.route(id: id, name: current.series_name ?? "返回剧集", type: "Series")) {
                            Text(current.series_name ?? "返回剧集").font(.system(size: 28))
                        }.buttonStyle(TVActionStyle(compact: true))
                    }
                    Text((current.type_ == "Episode" ? episodeNumber : "") + (presentation?.name ?? current.name))
                        .font(.system(size: 52, weight: .semibold)).fixedSize(horizontal: false, vertical: true)
                    if let tagline = presentation?.tagline, !tagline.isEmpty {
                        Text(tagline).font(.system(size: 28)).foregroundStyle(.white.opacity(0.85))
                    }
                    if let shown = presentation {
                        HStack(spacing: 22) {
                            // Android TV 详情数据行只有普通评分，分集不显示。
                            if item.type_ != "Episode", let score = artwork.ratingText() { Label(score, systemImage: "star.fill").foregroundStyle(.yellow) }
                            if let date = shown.premiere_date, !date.isEmpty { Text(String(date.prefix(10))) }
                            else if let year = shown.year { Text(String(year)) }
                            if let time = current.resumeTimeText { Text(time) }
                            if item.type_ == "Series", let seasons, !seasons.isEmpty { Text("共 \(seasons.count) 季") }
                        }.font(.system(size: 24)).foregroundStyle(.white.opacity(0.85))
                        ScrollView(.horizontal) {
                            HStack(spacing: 16) {
                                ForEach(shown.genres ?? [], id: \.self) { facetLink($0, query: BrowseQuery(genre: $0)) }
                                ForEach(shown.tags ?? [], id: \.self) { facetLink($0, query: BrowseQuery(tag: $0)) }
                                ForEach(shown.studios ?? []) { facetLink($0.name, query: BrowseQuery(studioID: $0.id), available: !$0.id.isEmpty) }
                                // 分级与完结状态只展示，不能按它筛选。
                                ForEach([shown.official_rating, shown.status].compactMap { $0 }.filter { !$0.isEmpty }, id: \.self) {
                                    Text($0).font(.system(size: 22)).foregroundStyle(.secondary)
                                }
                            }.padding(.vertical, 6)
                        }
                        if !shown.overview.isEmpty { TVOverview(text: shown.overview).id(current.id) }
                    }
                    if let error { RetryMessage(text: error) { reload += 1 } }
                    else if detail == nil { TVLoading("正在加载详情") }
                    HStack(spacing: 20) {
                        Button((mediaItem?.resume_secs ?? 0) > 0 ? "继续播放" : "播放") { play(fromStart: false) }
                            .buttonStyle(TVActionStyle(primary: true))
                            .disabled(detail == nil || mediaItem == nil || !versionsReady || versions.isEmpty || model.playback != nil)
                        if (mediaItem?.resume_secs ?? 0) > 0 {
                            Menu { Button("从头开始播放") { play(fromStart: true) } } label: {
                                Image(systemName: "chevron.down").accessibilityLabel("播放选项")
                            }.buttonStyle(TVActionStyle()).disabled(!versionsReady || mediaItem == nil)
                        }
                        if let detail {
                            // 与 Android TV 详情工具行一致：先已看，分集不提供收藏，详情页不提供隐藏续播。
                            Button { update("emby.setPlayed", key: "played", value: !detail.played) } label: {
                                Image(systemName: detail.played ? "checkmark.circle.fill" : "checkmark.circle").accessibilityLabel(detail.played ? "标未看" : "标已看")
                            }.disabled(writing)
                            if item.type_ != "Episode" {
                                Button { update("emby.setFavorite", key: "fav", value: !detail.is_favorite) } label: {
                                    Image(systemName: detail.is_favorite ? "heart.fill" : "heart").accessibilityLabel(detail.is_favorite ? "取消收藏" : "收藏")
                                }.disabled(writing)
                            }
                            if !model.privateSpace {
                                Button { switchingSource = true } label: {
                                    Image(systemName: "arrow.triangle.swap").accessibilityLabel("换源")
                                }.disabled(model.busy)
                            }
                        }
                    }.buttonStyle(TVActionStyle()).focusEffectDisabled()
                    if series, let target = mediaItem { Text(target.title).font(.system(size: 22)).foregroundStyle(.white.opacity(0.8)) }
                    if let actionError { Text(actionError).foregroundStyle(.orange) }
                    if let mediaError { RetryMessage(text: mediaError) { mediaReload += 1 } }
                    else if !versionsReady { TVLoading("正在获取播放信息") }
                    else if let source = selectedVersion {
                        ScrollView(.horizontal) {
                            HStack(spacing: 16) {
                                TVChoice(title: "版本", selection: $version,
                                    options: [("", "自动（核心偏好）")] + versions.map { ($0.id, $0.displayName) })
                                if let video = source.streams?.first(where: { $0.type_ == "Video" }) {
                                    Label(video.summary, systemImage: "video").font(.system(size: 22))
                                }
                                if let streams = source.streams, streams.filter({ $0.type_ == "Audio" }).count > 1 {
                                    TVChoice(title: "音轨", selection: Binding(get: { selectedAudio }, set: { value in
                                        selectedAudio = value
                                        if let lang = streams.first(where: { $0.type_ == "Audio" && $0.index == value })?.language { savePrefs(["audio_lang": .string(lang)]) }
                                    }),
                                        options: [(nil, "自动")] + streams.filter { $0.type_ == "Audio" }.map { (Optional($0.index), $0.label) })
                                }
                                if let streams = source.streams, streams.contains(where: { $0.type_ == "Subtitle" }) {
                                    TVChoice(title: "字幕", selection: Binding(get: { selectedSubtitle }, set: { value in
                                        selectedSubtitle = value
                                        if value == -1 { savePrefs(["sub_lang": .string(""), "sub_enabled": .bool(false)]) }
                                        else if let stream = streams.first(where: { $0.type_ == "Subtitle" && $0.index == value }) {
                                            savePrefs(["sub_lang": .string(stream.language ?? ""), "sub_enabled": .bool(true)])
                                        }
                                    }),
                                        options: [(nil, "自动"), (-1, "关闭字幕")] + streams.filter { $0.type_ == "Subtitle" }.map { (Optional($0.index), $0.label) })
                                }
                                if let account = model.account, (account.lines?.count ?? 0) > 1, !model.privateSpace {
                                    NavigationLink(account.name + " · " + account.activeLineName) { AccountEditor(api: api, account: account) }
                                        .buttonStyle(TVActionStyle(compact: true))
                                }
                            }.padding(.vertical, 8)
                        }
                    } else { Text("暂无可播放版本").foregroundStyle(.white.opacity(0.8)) }
                }.frame(width: max(320, geometry.size.width * 0.64 - 64), alignment: .leading)
                    .padding(.horizontal, 64).padding(.top, 64).padding(.bottom, 36)
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height * (series ? 0.64 : 0.68), alignment: .leading)
                    .foregroundStyle(.white)
                    .background {
                        GeometryReader { header in
                            Backdrop(item: artwork, loader: model.images, height: header.size.height, primaryEnabled: presentation?.has_primary, whiteForeground: true)
                            LinearGradient(colors: [.black.opacity(0.7), .black.opacity(0.2), .clear], startPoint: .leading, endPoint: .trailing)
                        }
                    }
                if current.type_ == "Episode", let seasonID = current.season_id ?? detail?.season_id, !seasonID.isEmpty {
                    EpisodeBrowser(api: api, parent: seasonID, origin: item.id,
                        seasonName: (current.season_no ?? detail?.season_no).map { "第 \($0) 季" } ?? "", showPrimary: false, current: current.id)
                        .id(seasonID)
                }
                if series {
                    if let seasons {
                        if seasons.count > 1 {
                            TVChoice(title: "季", selection: $season, options: seasons.map { ($0.id, $0.name) }).padding(.horizontal, 64)
                        }
                        EpisodeBrowser(api: api, parent: season.isEmpty ? current.id : season, origin: item.id, seasonName: seasons.first { $0.id == season }?.name ?? "", showPrimary: false,
                            onComplete: { parent, page, revision in
                                guard parent == (season.isEmpty ? current.id : season), revision == model.userStateRevision else { return }
                                if calibrationRevision != revision { confirmedSeasons = [:]; calibrationRevision = revision }
                                confirmedSeasons[parent] = page
                                episodeTarget = page.items.first { $0.id == model.returnedItem?.id && model.returnedOrigin == item.id }
                                    ?? page.items.first { $0.played != true } ?? page.items.first
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
                if let source = selectedVersion, let streams = source.streams {
                    VStack(alignment: .leading, spacing: 16) {
                        TVSectionHeading("媒体信息")
                        Text(source.displayName).font(.system(size: 24))
                        if let path = source.displayPath { Text(path).font(.system(size: 21)).foregroundStyle(.secondary) }
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
                    }.padding(.horizontal, 64)
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
        .sheet(isPresented: $switchingSource) {
            SwitchSourceView(api: api, title: item.series_name.flatMap { $0.isEmpty ? nil : $0 } ?? detail?.name ?? item.name, year: detail?.year)
        }
        .onChange(of: season) { _, _ in episodeTarget = nil; versionsReady = false; versions = []; version = ""; selectedAudio = nil; selectedSubtitle = nil }
        .task(id: "\(current.id):\(mediaReload)") {
            guard series else { return }
            do {
                if current.type_ == "Season" {
                    seasons = [MediaSeason(id: current.id, name: current.name, index_no: current.season_no)]
                    season = current.id
                } else {
                    let list = try await api.request("emby.seriesSeasons", ["series_id": .string(current.id)]).decode([MediaSeason].self)
                    seasons = list; if season.isEmpty { season = list.first?.id ?? "" }
                }
            } catch is CancellationError {} catch { mediaError = error.localizedDescription }
        }
        .task(id: "\(mediaItem?.id ?? ""):\(mediaReload):\(model.userStateRevision)") {
            let target = mediaItem
            if mediaItemID != target?.id { version = ""; selectedAudio = nil; selectedSubtitle = nil }
            versions = []; versionsReady = false; mediaError = nil; mediaItemID = ""
            guard let target else { return }
            do {
                let values: [MediaVersion]
                do {
                    values = try await api.request("prefs.preloadItem", ["item_id": .string(target.id), "return_media": .bool(true)]).decode([MediaVersion].self)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    let prefs = try await api.request("prefs.getPrefs")
                    values = try await api.request("emby.itemMedia", ["item_id": .string(target.id), "version_regex": prefs["version_regex"]]).decode([MediaVersion].self)
                }
                try Task.checkCancellation()
                guard mediaItem?.id == target.id else { return }
                versions = values; mediaItemID = target.id; versionsReady = true
            } catch is CancellationError {} catch { mediaError = error.localizedDescription }
        }
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

    // 与 Android 详情页一致：选择即写入全局语言偏好（核心只改传入的键）；「自动」不写。
    private func savePrefs(_ args: [String: JSONValue]) {
        Task {
            do { _ = try await api.request("prefs.setPrefs", args) }
            catch is CancellationError {} catch { actionError = error.localizedDescription }
        }
    }

    private func play(fromStart: Bool) {
        guard let target = mediaItem, mediaItemID == target.id, versionsReady, detail != nil else { return }
        var subtitle: InitialSubtitle?
        if let selectedSubtitle, let selectedVersion {
            subtitle = InitialSubtitle(version: selectedVersion.id, stream: selectedVersion.streams?.first { $0.type_ == "Subtitle" && $0.index == selectedSubtitle })
        }
        let audio = selectedAudio.flatMap { index in selectedVersion.flatMap { source in
            source.streams?.first { $0.type_ == "Audio" && $0.index == index }.map { InitialAudio(version: source.id, stream: $0) }
        } }
        model.openPlayer(item: target.id, title: target.title, version: version.isEmpty ? nil : version, fromStart: fromStart, subtitle: subtitle, audio: audio, origin: item.id)
    }
}

struct EpisodeBrowser: View {
    @Environment(\.tvPosterWidth) private var posterWidth
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let parent: String
    let origin: String
    var seasonName = ""
    var showPrimary = true
    // 分集详情页传入本集 ID：高亮并定位到当前集。
    var current: String? = nil
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
        if let current, let item = episodes.first(where: { $0.id == current }) { return item }
        if model.returnedOrigin == origin, let returned = model.returnedItem,
           let item = episodes.first(where: { $0.id == returned.id }) { return item }
        return episodes.first { $0.played != true } ?? episodes.first
    }
    private var currentID: String? {
        if let current, episodes.contains(where: { $0.id == current }) { return current }
        guard model.returnedOrigin == origin, let id = model.returnedItem?.id,
              episodes.contains(where: { $0.id == id }) else { return nil }
        return id
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack {
                TVSectionHeading(seasonName.isEmpty ? "分集" : seasonName)
                Spacer()
            }
            if showPrimary { Button(next.map { ($0.resume_secs > 0 ? "继续播放 · " : "播放 · ") + $0.name } ?? (loading ? "正在准备播放" : "暂无可播放分集")) {
                if let next {
                    model.openPlayer(item: next.id, title: next.title, version: nil, fromStart: false, origin: origin)
                }
            }.buttonStyle(TVActionStyle(primary: true)).focusEffectDisabled().disabled(next == nil || model.playback != nil) }
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
                }.frame(height: posterWidth * 1.4 * 9 / 16 + 112).focusSection()
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
