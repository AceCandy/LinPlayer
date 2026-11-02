import SwiftUI
import UIKit

// 浏览页保留同主题的输入入口，编辑时使用系统键盘与安全输入。
struct TVTextInput: View {
    let title: String
    @Binding var text: String
    var secure = false
    var contentType: UITextContentType? = nil
    var onSubmit: (() -> Void)? = nil
    @State private var showing = false
    @State private var draft = ""
    @FocusState private var editing: Bool
    init(_ title: String, text: Binding<String>, secure: Bool = false, contentType: UITextContentType? = nil, onSubmit: (() -> Void)? = nil) {
        self.title = title; _text = text; self.secure = secure; self.contentType = contentType; self.onSubmit = onSubmit
    }
    var body: some View {
        Button { draft = text; showing = true } label: {
            HStack(spacing: 24) {
                Text(title)
                Spacer()
                Text(text.isEmpty ? "点击输入" : secure ? "••••••••" : text).lineLimit(1).foregroundStyle(.secondary)
                Image(systemName: "pencil").foregroundStyle(.secondary)
            }
        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
        .sheet(isPresented: $showing, onDismiss: { draft = "" }) {
            VStack(alignment: .leading, spacing: 28) {
                TVSectionHeading(title)
                Group {
                    if secure { SecureField(title, text: $draft) }
                    else { TextField(title, text: $draft) }
                }.textContentType(contentType).autocorrectionDisabled().focused($editing)
                    .onSubmit { apply() }
                HStack(spacing: 24) {
                    Button("完成") { apply() }.buttonStyle(TVActionStyle(primary: true))
                    Button("取消") { showing = false }.buttonStyle(TVActionStyle())
                }.focusEffectDisabled()
            }.padding(64).onAppear { editing = true }.tvExitCommand { showing = false }
        }
    }
    private func apply() { text = draft; showing = false; onSubmit?() }
}

// 筛选和设置共用紧凑入口；长选项在独立列表中选择，避免系统分段控件撑满页面。
struct TVChoice<Value: Hashable>: View {
    let title: String
    @Binding var selection: Value
    let options: [(Value, String)]
    @State private var showing = false
    var body: some View {
        Button { showing = true } label: {
            HStack(spacing: 16) {
                Text(title).foregroundStyle(.secondary)
                Text(options.first { $0.0 == selection }?.1 ?? "未选择").lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 18))
            }
        }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
        .sheet(isPresented: $showing) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    TVSectionHeading(title)
                    ForEach(options, id: \.0) { option in
                        Button { selection = option.0; showing = false } label: {
                            HStack { Text(option.1); Spacer(); if selection == option.0 { Image(systemName: "checkmark") } }
                        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                    }
                    Button("返回") { showing = false }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                }.padding(64)
            }.tvExitCommand { showing = false }
        }
    }
}

// 栏目共用导航栈，详情覆盖栏目；跨服条目只由根栈消费一次。
struct ProductRoot: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    @State private var tab = "home"
    @State private var path: [MediaItem] = []
    @State private var visited: Set<String> = ["home"]
    @State private var homeOffset: CGFloat = 0
    @State private var homeRefresh = 0
    @State private var homeTop = 0
    @State private var topFocus = ""
    @State private var topFocusRequest = 0
    @State private var topFocusRow = UUID()
    init(api: MediaAPI, initialTab: String = "home") {
        self.api = api
        _tab = State(initialValue: initialTab)
        _visited = State(initialValue: [initialTab])
    }
    private let tabs = ["servers", "home", "favorites", "aggregate", "search", "settings"]
    var body: some View {
        GeometryReader { geometry in
        NavigationStack(path: $path) {
            ZStack(alignment: .top) {
                ZStack {
                    // 栏目按需创建，保留已访问页面的筛选、滚动和焦点状态。
                    ForEach(tabs.filter { visited.contains($0) }, id: \.self) { key in
                        page(key).padding(.top, key == "home" ? 0 : 96).opacity(tab == key ? 1 : 0).disabled(tab != key)
                            .allowsHitTesting(tab == key).accessibilityHidden(tab != key)
                            .environment(\.tvPageActive, tab == key && path.isEmpty && model.playback == nil)
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity).focusSection()
                if path.isEmpty { HStack(spacing: 16) {
                    TVNavigationButton(title: model.account?.name ?? "服务器", icon: "chevron.down", selected: tab == "servers", server: true, width: 180, selectOnFocus: false,
                                       requestFocus: topFocusRequest, focusKey: "servers", focusRequestKey: topFocus,
                                       focusChanged: { focused in
                                           if focused { topFocus = "servers" }
                                           else if topFocus == "servers" { topFocus = "" }
                                       }) {
                        if model.privateSpace { Task { do { try await model.changePrivateSpace(false) } catch { model.notice = error.localizedDescription } } }
                        else { select("servers") }
                    }
                    .contextMenu {
                        if !model.privateSpace {
                            Button("进入隐私空间") {
                                Task {
                                    do { try await model.changePrivateSpace(true); select("home") }
                                    catch {
                                        model.notice = (error as? CoreFailure)?.code == "E_UNSUPPORTED"
                                            ? "当前服务器未提供成人库分类，无法进入隐私空间" : error.localizedDescription
                                    }
                                }
                            }
                        }
                    }
                    TVNavigationButton(title: "首页", selected: tab == "home", requestFocus: topFocusRequest, focusKey: "home", focusRequestKey: topFocus,
                                       focusAction: { select("home") }, focusChanged: { focused in
                                           if focused { topFocus = "home" }
                                           else if topFocus == "home" { topFocus = "" }
                                       }) {
                        if tab == "home" { homeRefresh += 1 } else { select("home") }
                    }
                    TVNavigationButton(title: "收藏", selected: tab == "favorites", requestFocus: topFocusRequest, focusKey: "favorites", focusRequestKey: topFocus,
                                       focusChanged: { focused in
                                           if focused { topFocus = "favorites" }
                                           else if topFocus == "favorites" { topFocus = "" }
                                       }) { select("favorites") }
                    TVNavigationButton(title: "聚合视界", selected: tab == "aggregate", width: 180, requestFocus: topFocusRequest,
                                       focusKey: "aggregate", focusRequestKey: topFocus, focusChanged: { focused in
                                           if focused { topFocus = "aggregate" }
                                           else if topFocus == "aggregate" { topFocus = "" }
                                       }) { select("aggregate") }
                    Spacer()
                    TVNavigationButton(title: "搜索", icon: "magnifyingglass", iconOnly: true, selected: tab == "search", width: 72, selectOnFocus: false,
                                       requestFocus: topFocusRequest, focusKey: "search", focusRequestKey: topFocus,
                                       focusChanged: { focused in
                                           if focused { topFocus = "search" }
                                           else if topFocus == "search" { topFocus = "" }
                                       }) { select("search") }
                    if !model.privateSpace {
                        TVNavigationButton(title: "设置", icon: "gearshape", iconOnly: true, selected: tab == "settings", width: 72, selectOnFocus: false,
                                           requestFocus: topFocusRequest, focusKey: "settings", focusRequestKey: topFocus,
                                           focusChanged: { focused in
                                               if focused { topFocus = "settings" }
                                               else if topFocus == "settings" { topFocus = "" }
                                           }) { select("settings") }
                    }
                }
                .padding(.horizontal, 48).frame(height: 72).padding(.top, 20)
                .background {
                    LinearGradient(stops: [
                        .init(color: TVPalette.background(model.privateSpace).opacity(0.34), location: 0),
                        .init(color: TVPalette.background(model.privateSpace).opacity(0.16), location: 0.65),
                        .init(color: TVPalette.background(model.privateSpace).opacity(0), location: 1)
                    ], startPoint: .top, endPoint: .bottom)
                }.focusSection().environment(\.tvFocusRow, topFocusRow)
                    .offset(y: tab == "home" ? -min(92, max(0, homeOffset)) : 0) }
            }.ignoresSafeArea(.container)
            .navigationDestination(for: MediaItem.self) { item in
                if item.type_ == "Person" { PersonView(api: api, item: item) }
                else if item.type_ == "BoxSet" { CollectionView(api: api, item: item) }
                else if item.opensFolder { LibraryView(api: api, item: item) }
                else { DetailView(api: api, item: item) }
            }
        }
        // 普通空间顶栏已有焦点时不拦截返回，由 tvOS 直接回到主屏幕。
        .tvExitCommand(perform: path.isEmpty && !topFocus.isEmpty && !model.privateSpace ? nil : {
            if !path.isEmpty {
                path.removeLast()
            } else if topFocus.isEmpty {
                topFocus = tab
                topFocusRequest += 1
            } else {
                Task {
                    do { try await model.changePrivateSpace(false) }
                    catch { model.notice = error.localizedDescription }
                }
            }
        })
        .onAppear {
            if let item = model.pendingItem { path = [item]; model.pendingItem = nil }
            model.remoteNavigation = { key in
                switch key {
                case "home": path = []; select("home"); homeTop += 1; topFocus = "home"; topFocusRequest += 1; return true
                case "menu": guard path.isEmpty, !model.privateSpace else { return false }; select("settings"); return true
                case "back": guard !path.isEmpty else { return false }; path.removeLast(); return true
                default: return false
                }
            }
        }
        .onChange(of: model.pendingItem) { _, item in if let item { path = [item]; model.pendingItem = nil } }
        .environment(\.tvPosterWidth, tvPosterWidth(geometry.size.width - 128))
        }
    }
    private func select(_ key: String) {
        if key == "home", tab != key { homeTop += 1 }
        visited.insert(key); tab = key
    }
    @ViewBuilder private func page(_ key: String) -> some View {
        switch key {
        case "servers": ServersView(api: api)
        case "favorites": FavoritesView(api: api)
        case "aggregate": AggregateView(api: api)
        case "search": SearchView(api: api)
        case "settings": SettingsView(api: api)
        default: HomeView(api: api, active: tab == "home" && path.isEmpty, refreshToken: homeRefresh, topToken: homeTop,
                          onScroll: { homeOffset = $0 }, onTop: { topFocus = "home"; topFocusRequest += 1 })
        }
    }
}

private struct TVNavigationButton: View {
    @EnvironmentObject private var model: AppModel
    let title: String
    var icon: String? = nil
    var iconOnly = false
    let selected: Bool
    var server = false
    var width: CGFloat = 132
    var selectOnFocus = true
    var requestFocus = 0
    var focusKey = ""
    var focusRequestKey: String? = nil
    var focusAction: (() -> Void)? = nil
    var focusChanged: ((Bool) -> Void)? = nil
    let action: () -> Void
    @FocusState private var focused: Bool
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                if !iconOnly { Text(title).lineLimit(1) }
                if let icon { Image(systemName: icon).font(.system(size: iconOnly ? 20 : 16)) }
            }
            .font(.system(size: 22, weight: selected ? .semibold : .medium))
            .foregroundStyle(server && focused ? TVPalette.background(model.privateSpace) : TVPalette.foreground(model.privateSpace).opacity(selected || focused ? 1 : 0.62))
            .frame(width: width, height: 40)
            .background(focused ? TVPalette.foreground(model.privateSpace).opacity(server ? 1 : 0.10) : .clear, in: Capsule())
            .shadow(color: .black.opacity(focused ? 0.25 : 0), radius: 12, y: 6)
            .scaleEffect(focused ? 1.04 : 1)
            .animation(.easeOut(duration: 0.16), value: focused)
            .overlay(alignment: .bottom) {
                if selected && !server { Capsule().fill(TVPalette.accent(model.privateSpace, palette: model.colorScheme)).frame(width: 14, height: 2).padding(.bottom, 3) }
            }
        }.buttonStyle(TVCardStyle()).focused($focused).focusEffectDisabled()
            .onChange(of: focused) { _, value in
                focusChanged?(value)
                if value && selectOnFocus && !selected { (focusAction ?? action)() }
            }
            .onChange(of: requestFocus) { _, _ in
                if focusRequestKey == focusKey { focused = true }
            }
            .accessibilityLabel(title).accessibilityAddTraits(selected ? .isSelected : [])
    }

}

struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    @State private var query = ""
    // 聚合搜索只在提交或打开聚合时执行（Android aggQuery）；单服按关键词防抖自动搜索。
    @State private var submitted = ""
    @State private var aggregate = false
    @State private var type = "Movie"
    @State private var history: [String] = []
    @State private var items: [MediaItem] = []
    @State private var groups: [ServerResults] = []
    @State private var accounts: [MediaAccount] = []
    @State private var error: String?
    @State private var loading = false
    @State private var reload = 0
    @State private var generation = UUID()
    var body: some View {
        GeometryReader { geometry in
            let controlsWidth = min(560, geometry.size.width * 0.32)
            let width = tvPosterWidth(geometry.size.width - controlsWidth - 164)
            HStack(alignment: .top, spacing: 36) {
                ScrollView { VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 24) {
                    TVTextInput("搜片名、剧名或演员", text: $query, onSubmit: submit)
                }
                ScrollView(.horizontal) {
                    HStack(spacing: 16) {
                        if !model.privateSpace {
                            Button(aggregate ? "聚合搜索" : "当前服务器") { aggregate.toggle(); if aggregate { submit() } }
                                .buttonStyle(TVActionStyle(primary: aggregate, compact: true))
                        }
                        ForEach([("Movie", "电影"), ("Series", "剧集"), ("Person", "人物")], id: \.0) { value in
                            Button(value.1) { type = value.0 }
                                .buttonStyle(TVActionStyle(primary: type == value.0, compact: true)).disabled(aggregate)
                        }
                    }.padding(.vertical, 4)
                }
                if !model.privateSpace && !history.isEmpty {
                    TVSectionHeading("最近搜过")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            ForEach(history, id: \.self) { term in
                                Button(term) { query = term }.buttonStyle(TVActionStyle(compact: true))
                            }
                        }.padding(.vertical, 4)
                    }
                }
                }.padding(.vertical, 28) }.frame(width: controlsWidth).focusSection()
                ScrollView { LazyVStack(alignment: .leading, spacing: 30) {
                if loading { TVLoading("正在搜索") }
                if let error { RetryMessage(text: error) { reload += 1 } }
                if !searchText.isEmpty && !loading && error == nil && items.isEmpty && groups.isEmpty { Text("没有找到内容").foregroundStyle(.secondary) }
                if !aggregate && items.count >= 50 { Text("最多显示 50 条，请缩小关键词").font(.system(size: 22)).foregroundStyle(.secondary) }
                if aggregate && submitted.isEmpty { Text("在所有来源里搜：输入关键词后完成输入，每个来源各占一行。").foregroundStyle(.secondary) }
                if aggregate {
                    ForEach(groups) { group in
                        TVSectionHeading(group.server_name)
                        if let error = group.error { Text(error).foregroundStyle(.orange) }
                        if let warning = group.warning, !warning.isEmpty { Text(warning).foregroundStyle(.secondary) }
                        CrossServerRow(server: group.server_id, lineURL: accounts.first { $0.server == group.server_id }?.line_url, items: group.items)
                    }
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: width), spacing: 20)], alignment: .leading, spacing: 40) {
                        ForEach(items.filter { $0.type_ != "Episode" }) { item in
                            NavigationLink(value: item) { MediaCard(item: item, images: model.images) }
                                .buttonStyle(TVCardStyle()).focusEffectDisabled()
                        }
                    }
                }
                if !aggregate, items.contains(where: { $0.type_ == "Episode" }) {
                    TVSectionHeading("分集")
                    MediaRow(items: items.filter { $0.type_ == "Episode" }, wide: true, inset: 0)
                }
                }.padding(.vertical, 28) }.frame(maxWidth: .infinity).focusSection()
                    .environment(\.tvPosterWidth, width)
            }.padding(.horizontal, 64)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
        .task {
            guard !model.privateSpace else { return }
            history = UserDefaults.standard.stringArray(forKey: "searchHistory") ?? []
        }
        .task(id: aggregate) {
            guard aggregate, !model.privateSpace else { return }
            do { accounts = try await api.request("account.listAccounts").decode([MediaAccount].self) }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
        .task(id: "\(searchText):\(aggregate):\(type):\(reload):\(model.userStateRevision)") {
            let text = searchText
            guard !text.isEmpty else { items = []; groups = []; return }
            if !aggregate { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
            let request = UUID(); generation = request
            loading = true; error = nil; items = []; groups = []
            defer { if generation == request { loading = false } }
            do {
                if aggregate { groups = try await api.aggregateSearch(text) { rows in if generation == request { groups = rows } } }
                else {
                    items = try await api.request("emby.search", ["query": .string(text), "types": .array([.string(type)])], snapshot: { value in
                        if generation == request, let cached = try? value.decode([MediaItem].self) { items = cached }
                    }).decode([MediaItem].self)
                }
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { items = []; groups = [] }; self.error = error.localizedDescription }
        }
    }
    private var searchText: String { aggregate ? submitted : query.trimmingCharacters(in: .whitespacesAndNewlines) }
    // 历史只在提交时记录（Android recordSearch：去重、最新在前、最多 10 条，隐私空间不记）。
    private func submit() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        submitted = text; reload += 1
        guard !text.isEmpty, !model.privateSpace else { return }
        history = Array(([text] + history.filter { $0 != text }).prefix(10))
        UserDefaults.standard.set(history, forKey: "searchHistory")
    }
}

struct FavoriteGroup: Identifiable {
    let id: String
    let title: String
    let items: [MediaItem]
    let wide: Bool
    static func make(items: [MediaItem], libraries: [MediaItem]) -> [FavoriteGroup] {
        let known = Set(libraries.map(\.id))
        let groups = libraries.compactMap { library -> FavoriteGroup? in
            let contents = items.filter { ($0.library_ids ?? []).contains(library.id) }
            return contents.isEmpty ? nil : FavoriteGroup(id: "library:" + library.id, title: library.name, items: contents, wide: false)
        }
        let rest = items.filter { Set($0.library_ids ?? []).isDisjoint(with: known) }
        return groups + [("Movie", "电影"), ("Series", "剧集"), ("Episode", "分集"), ("Other", "其他收藏")].compactMap { kind, title in
            let contents = rest.filter { kind == "Other" ? !["Movie", "Series", "Episode"].contains($0.type_) : $0.type_ == kind }
            return contents.isEmpty ? nil : FavoriteGroup(id: "type:" + kind, title: title, items: contents, wide: kind == "Episode")
        }
    }
}

struct FavoritesView: View {
    @Environment(\.tvPosterWidth) private var posterWidth
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    var groupID: String? = nil
    var title = "收藏"
    @State private var libraries: [MediaItem] = []
    private var groups: [FavoriteGroup] { FavoriteGroup.make(items: items, libraries: libraries) }
    private var visible: [MediaItem] {
        if let groupID, groupID.hasPrefix("library:") {
            return items.filter { ($0.library_ids ?? []).contains(String(groupID.dropFirst(8))) }
        }
        return groupID.flatMap { id in groups.first { $0.id == id }?.items } ?? (groupID == nil ? items : [])
    }
    @State private var items: [MediaItem] = []
    @State private var loading = false
    @State private var error: String?
    @State private var reload = 0
    @FocusState private var focus: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                if groupID != nil { Text(title).font(.system(size: 30, weight: .semibold)) }
                if groupID != nil {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: groupID == "type:Episode" ? posterWidth * 1.4 : posterWidth), spacing: 20)], alignment: .leading, spacing: 40) {
                        ForEach(visible) { item in
                            NavigationLink(value: item) { MediaCard(item: item, images: model.images, wide: groupID == "type:Episode") }
                                .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focus, equals: item.id)
                        }
                    }
                } else {
                    ForEach(groups) { group in
                        HStack {
                            TVSectionHeading(group.title)
                            Spacer()
                            NavigationLink { FavoritesView(api: api, groupID: group.id, title: group.title) } label: {
                                Label("全部", systemImage: "chevron.right")
                            }.buttonStyle(TVActionStyle(compact: true))
                        }
                        MediaRow(items: group.items, wide: group.wide, inset: 0)
                    }
                }
                if let error { RetryMessage(text: error) { reload += 1 } }
                if loading { TVLoading() }
                else if visible.isEmpty { Text("暂无收藏").foregroundStyle(.secondary) }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
        .ignoresSafeArea(.container, edges: .horizontal)
        .task(id: items.contains { !($0.library_ids ?? []).isEmpty }) {
            guard items.contains(where: { !($0.library_ids ?? []).isEmpty }) else { return }
            do { libraries = try await api.request("emby.views").decode([MediaItem].self) }
            catch is CancellationError {} catch { /* 库名只增强分组，失败仍按类型展示全部收藏。 */ }
        }
        .task(id: "\(model.userStateRevision):\(reload)") {
            await load()
        }
    }
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let value = try await api.request("emby.listFavorites", [
                "start_index": .number(0), "limit": .number(150),
                "sort": .string("更新时间")
            ], snapshot: { value in
                if let page = try? value.decode(MediaPage.self), items.isEmpty { items = page.items }
            })
            items = try value.decode(MediaPage.self).items
            error = nil
        } catch is CancellationError {} catch {
            if invalidatesDisplay(error) { items = [] }
            self.error = error.localizedDescription
        }
    }
}

struct CrossServerRow: View {
    @EnvironmentObject private var model: AppModel
    let server: String
    let lineURL: String?
    let items: [MediaItem]
    // 聚合页续播行显示剩余时长、仅当前服可写；搜索结果按来源服写入。
    var resume = false
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading) {
            if let error { Text(error).foregroundStyle(.orange) }
            ScrollView(.horizontal) {
                LazyHStack(spacing: 30) {
                    ForEach(items) { item in
                        Button {
                            // 当前服直接打开；切服会结束播放并重建会话。
                            if model.account?.server == server { model.pendingItem = item; return }
                            Task {
                                do { try await model.switchServer(server, opening: item) }
                                catch { self.error = error.localizedDescription }
                            }
                        } label: {
                            MediaCard(item: item, images: model.images, wide: true, imageServer: lineURL ?? "", resume: resume, serverID: resume ? nil : server)
                        }.buttonStyle(TVCardStyle()).focusEffectDisabled().disabled(model.busy)
                    }
                }.padding(.vertical, 12)
            }
        }
    }
}

struct AggregateView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    @State private var servers: [ServerOverview] = []
    @State private var error: String?
    @State private var reload = 0
    @State private var loading = true
    @State private var generation = UUID()
    var body: some View {
        if model.privateSpace {
            HStack(spacing: 30) {
                NavigationLink("流影") { LiuYingView(api: api) }
                NavigationLink("排行榜") { DiscoveryView(api: api, calendar: false) }
                Spacer()
            }.padding(.horizontal, 64).padding(.vertical, 28).buttonStyle(TVActionStyle())
        } else {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 36) {
                HStack(spacing: 30) {
                    NavigationLink("排行榜") { DiscoveryView(api: api, calendar: false) }
                    NavigationLink("追剧日历") { DiscoveryView(api: api, calendar: true) }
                }
                if let error { RetryMessage(text: error) { reload += 1 } }
                if loading { TVLoading() }
                ForEach(servers) { server in
                    VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 18) {
                        TVSectionHeading(server.server_name)
                        if server.active == true { Text("当前").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                        if server.aggregate_primary == true { Text("主服").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                    }
                    if server.counts_known {
                        Text("电影 \(server.counts.movie) · 剧集 \(server.counts.series) · 分集 \(server.counts.episode) · 合集 \(server.counts.boxset)").foregroundStyle(.secondary)
                    }
                    if let error = server.counts_error { Text(error).foregroundStyle(.orange) }
                    if let error = server.error { Text(error).foregroundStyle(.orange) }
                    CrossServerRow(server: server.server_id, lineURL: server.line_url, items: server.resume ?? [], resume: true)
                    }.padding(.vertical, 8)
                }
                if !loading && servers.isEmpty && error == nil { Text("暂无参与聚合的服务器").foregroundStyle(.secondary) }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
        .task(id: "\(reload):\(model.userStateRevision)") {
            guard !model.privateSpace else { return }
            let request = UUID(); generation = request
            loading = true; defer { if generation == request { loading = false } }
            do { servers = try await api.request("emby.aggregateOverview", snapshot: { value in if generation == request, let cached = try? value.decode([ServerOverview].self) { servers = cached } }).decode([ServerOverview].self); error = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { servers = [] }; self.error = error.localizedDescription }
        }
        }
    }
}

struct ServersView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    @State private var accounts: [MediaAccount] = []
    @State private var error: String?
    @State private var reload = 0
    @State private var adding = false
    @State private var working = false
    @State private var probes: [String: Bool] = [:]
    @State private var deleting: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 30) {
                HStack { Spacer(); Button("添加服务器") { adding = true } }
                if let error { RetryMessage(text: error) { reload += 1 } }
                ForEach(accounts, id: \.server) { account in
                    VStack(alignment: .leading, spacing: 20) {
                        HStack(spacing: 24) {
                            RemoteArtwork(url: account.icon_url, loader: model.images, width: 80, height: 80)
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(spacing: 20) {
                                    Text(account.name).font(.system(size: 30, weight: .semibold)).lineLimit(1)
                                    if account.active { Text("当前").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                                    if account.aggregate_primary == true { Text("主服").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                                }
                                Text(account.user_name).foregroundStyle(.secondary)
                                if let remark = account.remark, !remark.isEmpty { Text(remark).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(2) }
                            }
                            Spacer()
                            if let online = probes[account.server] {
                                Label(online ? "可连接" : "不可达", systemImage: online ? "circle.fill" : "exclamationmark.circle")
                                    .font(.system(size: 22)).foregroundStyle(online ? Color.green : Color.orange)
                            } else { Text("检测中…").font(.system(size: 22)).foregroundStyle(.secondary) }
                        }
                        HStack {
                            Button("切换") {
                                Task {
                                    do { try await model.switchServer(account.server) }
                                    catch { self.error = error.localizedDescription }
                                }
                            }.disabled(account.active || model.busy || working)
                            NavigationLink("编辑与线路") { AccountEditor(api: api, account: account) { reload += 1 } }
                            Button(account.aggregate == true ? "退出聚合" : "参与聚合") {
                                mutate("source.setAggregate", ["server_id": .string(account.server), "allow": .bool(account.aggregate != true)])
                            }
                            Button(account.aggregate_primary == true ? "取消主服" : "设为主服") {
                                mutate("account.setAggregatePrimaryServer", ["server_id": .string(account.aggregate_primary == true ? "" : account.server)])
                            }
                            Button("移除", role: .destructive) { deleting = account.server }
                        }.buttonStyle(TVActionStyle(compact: true)).disabled(working)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(28).background(.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 22))
                    .task(id: account.line_url) {
                        do {
                            let result = try await api.request("account.probeLine", ["server_id": .string(account.server), "index": .number(Double(account.active_line ?? 0))])
                            probes[account.server] = (result["ms"].number ?? -1) >= 0
                        } catch is CancellationError {} catch { probes[account.server] = false }
                    }
                }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
        .task(id: reload) {
            do { accounts = try await api.request("account.listAccounts").decode([MediaAccount].self); error = nil }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
        .sheet(isPresented: $adding) { LoginView() }
        .confirmationDialog("移除此服务器的本机账号？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("移除", role: .destructive) {
                if let server = deleting { mutate("account.removeAccount", ["server_id": .string(server)], restore: server == model.account?.server) }
                deleting = nil
            }
        }
    }
    private func mutate(_ command: String, _ args: [String: JSONValue], restore: Bool = false) {
        guard !working else { return }; working = true
        Task {
            defer { working = false }
            do {
                _ = try await api.request(command, args)
                if restore { try await model.reloadAccounts() }
                reload += 1; error = nil
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}

struct AccountEditor: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let account: MediaAccount
    var onSaved: (() -> Void)? = nil
    @State private var name = ""
    @State private var remark = ""
    @State private var username = ""
    @State private var password = ""
    @State private var renaming: Int?
    @State private var renameText = ""
    @State private var icons: JSONValue = .null
    @State private var iconQuery = ""
    @State private var iconSource = ""
    @State private var iconURL: String?
    @State private var lines: [MediaLine] = []
    @State private var lineName = ""
    @State private var lineURL = ""
    @State private var activeLine = 0
    @State private var probes: [Int: String] = [:]
    @State private var error: String?
    @State private var working = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("编辑服务器").font(.system(size: 38, weight: .semibold))
                TVSettingsSection("资料") {
                TVTextInput("名称", text: $name)
                TVTextInput("账号", text: $username, contentType: .username)
                TVTextInput("密码（留空则不改）", text: $password, secure: true, contentType: .password)
                TVTextInput("备注", text: $remark)
                Button("保存资料") { saveProfile() }
                }
                TVSettingsSection("图标") {
                    iconPicker
                }
                TVSettingsSection("线路") {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                    HStack {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(line.name?.isEmpty == false ? line.name! : "线路 \(index + 1)")
                                if index == activeLine { Label("生效中", systemImage: "checkmark.circle.fill").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                            }
                            Text(line.url).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Text(probes[index] ?? "").foregroundStyle(.secondary)
                        Spacer()
                        Button("改名") { renaming = index; renameText = line.name ?? "" }
                        Button("测速") {
                            Task {
                                do {
                                    let result = try await api.request("account.probeLine", ["server_id": .string(account.server), "index": .number(Double(index))])
                                    probes[index] = result["ms"].number.map { "\(Int($0)) ms" } ?? "不可达"
                                } catch is CancellationError {} catch { self.error = error.localizedDescription }
                            }
                        }
                        Button("使用") { perform("account.setActiveLine", ["index": .number(Double(index))], restore: account.active) }.disabled(index == activeLine)
                        Button("删除", role: .destructive) {
                            var revised = lines; revised.remove(at: index); saveLines(revised)
                        }.disabled(lines.count <= 1)
                    }
                    if renaming == index {
                        HStack {
                            TVTextInput("线路名称", text: $renameText)
                            // 核心按 url 找回生效线路，整表送回不会切走当前线路。
                            Button("保存") {
                                var revised = lines
                                revised[index] = MediaLine(id: line.id, name: renameText, url: line.url, remark: line.remark)
                                saveLines(revised)
                            }
                            Button("取消") { renaming = nil }
                        }
                    }
                }
                }
                TVSettingsSection("添加线路") {
                TVTextInput("新线路名称", text: $lineName)
                TVTextInput("新线路地址", text: $lineURL, contentType: .URL)
                HStack {
                    Button("添加线路") {
                        saveLines(lines + [MediaLine(id: nil, name: lineName, url: lineURL.trimmingCharacters(in: .whitespacesAndNewlines), remark: nil)])
                    }.disabled(lineURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("从服务器同步线路") { perform("account.syncLines", [:]) }
                }
                }
                if let error { Text(error).foregroundStyle(.orange) }
            }.padding(.horizontal, 64).padding(.vertical, 28).disabled(working)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
        .toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal)
        .task {
            name = account.name; remark = account.remark ?? ""; username = account.user_name; iconURL = account.icon_url
            lines = account.lines ?? []; activeLine = account.active_line ?? 0
            await loadIcons(force: false)
        }
        .onDisappear { password = "" }
    }
    @ViewBuilder private var iconPicker: some View {
        let entries = icons["items"].array.filter { iconQuery.isEmpty || ($0["name"].string ?? "").localizedCaseInsensitiveContains(iconQuery) }
        let sources = icons["sources"].array.compactMap(\.string)
        let builtin = Int(icons["builtin"].number ?? 0)
        HStack {
            RemoteArtwork(url: iconURL, loader: model.images, width: 80, height: 80)
            Button("恢复默认") { setIcon("") }
        }
        TVTextInput("搜索图标名", text: $iconQuery)
        if icons == .null { TVLoading("正在获取图标库") }
        else if entries.isEmpty { Text(builtin == 0 && sources.isEmpty ? "还没有任何图标源，可以在下面添加网络源。" : "没有匹配的图标。").foregroundStyle(.secondary) }
        else {
            ScrollView(.horizontal) {
                LazyHStack(spacing: 20) {
                    ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                        Button { setIcon(entry["url"].string ?? "") } label: {
                            RemoteArtwork(url: entry["url"].string, loader: model.images, width: 96, height: 96)
                        }.buttonStyle(TVCardStyle()).accessibilityLabel(entry["name"].string ?? "图标")
                    }
                }.padding(.vertical, 12)
            }
        }
        // 内置源地址随构建注入，只报条数不显示地址。
        Text(builtin > 0 ? "内置图标源 \(builtin) 个" : "这个构建没有内置图标源。").font(.system(size: 22)).foregroundStyle(.secondary)
        ForEach(sources, id: \.self) { source in
            HStack {
                Text(source).font(.system(size: 22)).lineLimit(1)
                Spacer()
                Button("移除") { saveIconSources(sources.filter { $0 != source }) }
            }
        }
        HStack {
            TVTextInput("图标源 JSON 地址", text: $iconSource, contentType: .URL)
            Button("添加源") {
                let source = iconSource.trimmingCharacters(in: .whitespacesAndNewlines)
                guard source.hasPrefix("http://") || source.hasPrefix("https://") else { error = "网络源要以 http:// 或 https:// 开头"; return }
                saveIconSources(sources + [source])
            }.disabled(iconSource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
    private func loadIcons(force: Bool) async {
        do { icons = try await api.request("prefs.iconLibrary", ["force": .bool(force)]) }
        catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
    private func saveIconSources(_ sources: [String]) {
        guard !working else { return }; working = true
        Task {
            defer { working = false }
            do {
                _ = try await api.request("prefs.setIconSources", ["sources": .array(sources.map { .string($0) })])
                iconSource = ""; icons = .null; error = nil
                await loadIcons(force: true)
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    // 先清缓存再写地址，否则旧图标仍命中缓存。
    private func setIcon(_ url: String) {
        guard !working else { return }; working = true
        Task {
            defer { working = false }
            do {
                _ = try await api.request("account.clearAccountIcon", ["server_id": .string(account.server)])
                _ = try await api.request("account.updateAccount", ["server_id": .string(account.server), "icon_url": .string(url)])
                iconURL = url.isEmpty ? nil : url; error = nil; onSaved?()
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    // 改账号或密码必须在资料保存后走 emby.relogin 换 token，只改字段会留下旧用户的 token。
    private func saveProfile() {
        guard !working else { return }; working = true
        Task {
            defer { working = false }
            do {
                _ = try await api.request("account.updateAccount", ["server_id": .string(account.server), "name": .string(name), "remark": .string(remark)])
                if !password.isEmpty || username != account.user_name {
                    _ = try await api.request("emby.relogin", ["server_id": .string(account.server), "username": .string(username), "password": .string(password)])
                    password = ""
                    if account.active { try await model.reloadAccounts() }
                }
                error = nil; onSaved?()
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    private func saveLines(_ revised: [MediaLine]) {
        let values = revised.map { line -> JSONValue in
            var value: [String: JSONValue] = ["url": .string(line.url)]
            if let id = line.id { value["id"] = .string(id) }
            if let name = line.name { value["name"] = .string(name) }
            if let remark = line.remark { value["remark"] = .string(remark) }
            return .object(value)
        }
        perform("account.setLines", ["lines": .array(values)])
    }
    private func perform(_ command: String, _ args: [String: JSONValue], restore: Bool = false) {
        guard !working else { return }; working = true
        Task {
            defer { working = false }
            do {
                var args = args; args["server_id"] = .string(account.server)
                _ = try await api.request(command, args)
                let accounts = try await api.request("account.listAccounts").decode([MediaAccount].self)
                let updated = accounts.first { $0.server == account.server }
                lines = updated?.lines ?? []
                activeLine = updated?.active_line ?? 0
                lineURL = ""; lineName = ""; probes = [:]; renaming = nil; error = nil; onSaved?()
                if restore { try await model.reloadAccounts() }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}

struct TVRowStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        TVRemoteButton(action: configuration.trigger) {
            TVRowLabel(destructive: configuration.role == .destructive) { configuration.label }
        }
    }
}

private struct TVRowLabel<Content: View>: View {
    let destructive: Bool
    @Environment(\.tvPressed) private var pressed
    @ViewBuilder let content: () -> Content
    @EnvironmentObject private var model: AppModel
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        content().font(.system(size: 26)).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 8)
            .foregroundStyle(destructive ? Color.red : focused ? TVPalette.background(model.privateSpace) : TVPalette.foreground(model.privateSpace))
            .background(TVPalette.foreground(model.privateSpace).opacity(focused ? 1 : 0), in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: .black.opacity(focused ? 0.25 : 0), radius: 10, y: 6)
            .opacity(enabled ? (pressed ? 0.7 : 1) : 0.45)
            .animation(.easeOut(duration: 0.14), value: focused)
    }
}

struct TVSettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    init(_ title: String, @ViewBuilder content: @escaping () -> Content) { self.title = title; self.content = content }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 24, weight: .medium)).foregroundStyle(.secondary).padding(.horizontal, 20)
            VStack(alignment: .leading, spacing: 8, content: content)
                .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 22))
        }
    }
}

struct TVToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack {
                configuration.label
                Spacer()
                Image(systemName: configuration.isOn ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(configuration.isOn ? Color.accentColor : Color.secondary)
            }
        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
            .accessibilityValue(configuration.isOn ? "已开启" : "已关闭")
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    @State private var group = "播放与外观"
    @FocusState private var focusedGroup: String?
    var body: some View {
        HStack(alignment: .top, spacing: 48) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(["播放与外观", "手机遥控", "网络", "内容与数据", "关于"], id: \.self) { title in
                        Button(title) { group = title }.focused($focusedGroup, equals: title)
                    }
                }
            }.frame(width: 220).focusSection()
                .onChange(of: focusedGroup) { _, value in if let value { group = value } }
            ScrollView {
            VStack(alignment: .leading, spacing: 30) {
            if group == "播放与外观" {
            TVSettingsSection("播放与外观") {
                settingsLink("外观", icon: "paintpalette", page: .appearance)
                settingsLink("播放器", icon: "play.rectangle", page: .player)
                NavigationLink("弹幕") { TVDanmakuSettings(api: api) }
            }
            }
            if !model.privateSpace && group == "手机遥控" {
                TVSettingsSection("手机遥控") {
                    NavigationLink("手机扫码遥控") { CompanionView() }
                }
            }
            if !model.privateSpace && group == "网络" {
                TVSettingsSection("网络") {
                    settingsLink("多线程加载", icon: "network", page: .prefetch)
                    settingsLink("主进度服", icon: "arrow.triangle.2.circlepath", page: .progress)
                }
            }
            if !model.privateSpace && group == "内容与数据" {
                TVSettingsSection("内容与数据") {
                    NavigationLink { BlockedView(api: api) } label: { HStack { Label("已屏蔽的内容", systemImage: "eye.slash"); Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) } }
                    NavigationLink("备份与还原") { TVBackupView() }
                    settingsLink("存储与数据目录", icon: "internaldrive", page: .storage)
                }
            }
            if group == "关于" { TVSettingsSection("关于") {
                NavigationLink("关于与更新") { TVAboutView(api: api) }
            }
            }
            }
            }.frame(maxWidth: .infinity).focusSection()
        }.padding(.horizontal, 64).padding(.vertical, 28).buttonStyle(TVRowStyle()).focusEffectDisabled()
    }
    private func settingsLink(_ title: String, icon: String, page: SettingsPane) -> some View {
        NavigationLink { SettingsDetailView(api: api, pane: page, title: title) } label: { HStack { Label(title, systemImage: icon); Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) } }
    }
}

enum SettingsPane { case appearance, player, prefetch, progress, storage }

struct SettingsDetailView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let pane: SettingsPane
    let title: String
    @AppStorage("appearance") private var appearance = "dark"
    @AppStorage("hideLibraryPosters") private var hideLibraryPosters = false
    @AppStorage("tvSeekStep") private var seekStep = 10
    @AppStorage("tvHoldSpeed") private var holdSpeed = 3
    @State private var skipIntro = false
    @State private var skipOutro = false
    @State private var skipAuto = false
    @State private var prefetch = false
    @State private var threads = 3
    @State private var cacheMiB = 512
    @State private var mediaMiB = 1024
    @State private var selectedServer = ""
    @State private var progress: PrimaryProgressSettings?
    @State private var accounts: [MediaAccount] = []
    @State private var bytes: Double = 0
    @State private var dataPaths: JSONValue = .null
    @State private var error: String?
    @State private var message: String?
    @State private var ready = false
    @State private var working = false
    @State private var reload = 0
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
            Text(title).font(.system(size: 38, weight: .semibold))
            if ready {
                switch pane {
                case .appearance:
                    TVSettingsSection("外观") {
                        TVChoice(title: "主题", selection: $appearance, options: [("system", "跟随系统"), ("dark", "深色"), ("light", "浅色")])
                        TVChoice(title: "色系", selection: $model.colorScheme, options: [("amber", "橙金"), ("blue", "蓝色"), ("green", "绿色"), ("purple", "紫色")])
                        TVChoice(title: "加载动画", selection: $model.loadingStyle, options: TVLoading.styles.map { ($0, $0 == 0 ? "系统默认" : "样式 \($0)") })
                        TVLoading("加载动画预览")
                        Toggle("隐藏媒体库海报展示", isOn: $hideLibraryPosters)
                    }
                case .player:
                    TVSettingsSection("播放器") {
                        Text("播放内核：AetherEngine")
                        Toggle("跳过片头", isOn: saved($skipIntro) { value in _ = try await api.request("player.setPlaybackPrefs", ["settings": .object(["skip_intro": .bool(value)])]) })
                        Toggle("跳过片尾", isOn: saved($skipOutro) { value in _ = try await api.request("player.setPlaybackPrefs", ["settings": .object(["skip_outro": .bool(value)])]) })
                        Toggle("到了就自己跳", isOn: saved($skipAuto) { value in _ = try await api.request("player.setPlaybackPrefs", ["settings": .object(["skip_auto": .bool(value)])]) })
                        TVChoice(title: "快进步长", selection: $seekStep, options: [5, 10, 15, 30].map { ($0, "\($0) 秒") })
                        TVChoice(title: "长按倍速", selection: $holdSpeed, options: [2, 3, 4].map { ($0, "\($0)×") })
                        Text("播放时按住确认键临时加速，松开恢复。视频最高 2×，超出部分按引擎上限播放。")
                        Text("关闭自动跳过时，在播放页显示跳过按钮。")
                    }
                case .prefetch:
                    TVSettingsSection("多线程加载") {
                        Toggle("当前服务器启用", isOn: saved($prefetch) { value in try await savePrefetch(enabled: value, threads: threads, cache: cacheMiB) }).disabled(model.account == nil)
                        TVChoice(title: "并发连接数", selection: saved($threads) { value in try await savePrefetch(enabled: prefetch, threads: value, cache: cacheMiB) }, options: (2...4).map { ($0, "\($0) 个连接") })
                    }
                case .progress:
                    TVSettingsSection("主进度服") {
                        Text("相同资源以主服进度为准；独有资源各服保留。主服不可用或匹配不确定时正常播放，切换主服不迁移历史。")
                        TVChoice(title: "服务器", selection: saved($selectedServer) { server in
                            let value = try await api.request("prefs.setPrimaryProgressServer", ["server_id": .string(server)]).decode(PrimaryProgressSettings.self)
                            progress = value; selectedServer = value.server
                        }, options:
                            [("", "未指定")] + accounts.filter { !$0.user_id.isEmpty }.map { ($0.server, $0.name) } +
                            (!selectedServer.isEmpty && !accounts.contains { $0.server == selectedServer && !$0.user_id.isEmpty } ? [(selectedServer, "原主服已不可用")] : []))
                        if let progress {
                            Text("待同步：\(progress.pending) · 冲突：\(progress.conflicts)")
                            if !progress.server.isEmpty && !progress.valid { Text("主服绑定已失效，请重新选择账号").foregroundStyle(.orange) }
                            if let issue = progress.error, !issue.isEmpty { Text(issue).foregroundStyle(.orange) }
                        }
                        Button("重试同步") { perform {
                            let value = try await api.request("prefs.retryPrimaryProgressSync").decode(PrimaryProgressSettings.self)
                            progress = value
                            if let issue = value.error, !issue.isEmpty { throw CoreFailure(code: "E_SYNC") }
                        } }.disabled(progress?.valid != true)
                        Button("刷新状态") { reload += 1 }
                    }
                case .storage:
                    TVSettingsSection("存储与数据目录") {
                        Text("缓存：\(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file))")
                        capacityPicker("媒体缓存上限", selection: saved($mediaMiB) { value in
                            _ = try await api.request("prefs.setPrefetchSettings", ["settings": .object(["media_cache_bytes": .number(Double(value) * 1024 * 1024)])])
                        }, allowOff: true)
                        Button("清理缓存") { perform {
                            _ = try await api.request("system.clearCache")
                            if let server = api.session?.server { model.images.use(server: server) }
                            bytes = try await api.request("system.cacheSize")["bytes"].number ?? 0
                        } }
                    }
                    // tvOS 无文件选择器，不提供 Android 的「导出日志」；其余项与 Android 目录与日志分组一致。
                    TVSettingsSection("目录与日志") {
                        Text("数据目录：" + (dataPaths["root"].string ?? "读取失败"))
                        Text("日志目录：" + (dataPaths["logs"].string ?? "读取失败"))
                        Button("发送日志给开发者（地址、账号与令牌会先抹掉）") { sendLogs() }
                    }
                }
            } else { TVLoading("正在读取设置") }
            if let error { RetryMessage(text: error) { reload += 1 } }
            if let message { Text(message).foregroundStyle(.secondary) }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVRowStyle()).toggleStyle(TVToggleStyle()).focusEffectDisabled()
        .toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal).disabled(working)
        .task(id: reload) {
            ready = false; message = nil
            do {
                switch pane {
                case .appearance: break
                case .player:
                    let playback = try await api.request("player.getPlaybackPrefs")
                    skipIntro = playback["skip_intro"].bool ?? false; skipOutro = playback["skip_outro"].bool ?? false
                    skipAuto = playback["skip_auto"].bool ?? false
                case .prefetch:
                    let value = try await api.request("prefs.getPrefetchSettings").decode(PrefetchSettings.self)
                    prefetch = value.servers.contains(model.account?.server ?? "")
                    threads = value.threads; cacheMiB = value.cache_bytes / (1024 * 1024)
                case .progress:
                    async let accountValue = api.request("account.listAccounts")
                    async let progressValue = api.request("prefs.getPrimaryProgressServer")
                    let values = try await (accountValue, progressValue)
                    let status = try values.1.decode(PrimaryProgressSettings.self)
                    accounts = try values.0.decode([MediaAccount].self)
                    progress = status; selectedServer = status.server
                case .storage:
                    async let settingsValue = api.request("prefs.getPrefetchSettings")
                    async let sizeValue = api.request("system.cacheSize")
                    let values = try await (settingsValue, sizeValue)
                    mediaMiB = try values.0.decode(PrefetchSettings.self).media_cache_bytes / (1024 * 1024)
                    bytes = values.1["bytes"].number ?? 0
                    dataPaths = (try? await api.request("system.dataPaths")) ?? .null
                }
                ready = true; error = nil
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    private func saved<Value>(_ binding: Binding<Value>, write: @escaping @MainActor (Value) async throws -> Void) -> Binding<Value> {
        Binding(get: { binding.wrappedValue }, set: { value in
            guard ready, !working else { return }
            let previous = binding.wrappedValue; binding.wrappedValue = value
            perform {
                do { try await write(value) }
                catch { binding.wrappedValue = previous; throw error }
            }
        })
    }
    private func savePrefetch(enabled: Bool, threads: Int, cache: Int) async throws {
        guard let server = model.account?.server else { throw CoreFailure(code: "E_SESSION") }
        let current = try await api.request("prefs.getPrefetchSettings").decode(PrefetchSettings.self)
        _ = try await api.request("prefs.setPrefetchSettings", current.arguments(server: server, enabled: enabled, threads: threads, cacheMiB: cache))
    }
    private func capacityPicker(_ label: String, selection: Binding<Int>, allowOff: Bool) -> some View {
        let values = Array(Set([64, 128, 256, 512, 1024, 2048, 4096] + (allowOff ? [0] : []) + [selection.wrappedValue])).sorted()
        return TVChoice(title: label, selection: selection, options: values.map { ($0, $0 == 0 ? "关闭" : "\($0) MiB") })
    }
    // 与 Android Report.feedback 同一命令；tvOS 宿主无本地日志，附核心诊断与核心日志目录内文件，由核心脱敏截断。
    private func sendLogs() {
        guard !working else { return }; working = true; message = nil; error = nil
        Task {
            defer { working = false }
            do {
                var log = "== 诊断 ==\n"
                if let value = try? await api.request("system.exportDiagnostics"), let data = try? JSONEncoder().encode(value) { log += String(decoding: data, as: UTF8.self) }
                else { log += "取不到" }
                if let dir = dataPaths["logs"].string, let names = try? FileManager.default.contentsOfDirectory(atPath: dir) {
                    for name in names.sorted() where name.hasSuffix(".log") {
                        if let text = try? String(contentsOfFile: (dir as NSString).appendingPathComponent(name), encoding: .utf8) { log += "\n== \(name) ==\n" + text }
                    }
                }
                _ = try await api.request("system.sendReport", ["kind": .string("feedback"), "crash": .string(""), "log": .string(log), "dry": .bool(false)])
                message = "已发给开发者，谢谢"
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !working else { return }; working = true; message = nil; error = nil
        Task {
            defer { working = false }
            do { try await action(); message = "已完成" }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}

struct BlockedView: View {
    struct Blocked: Decodable, Identifiable { let id: String; let name: String }
    let api: MediaAPI
    @State private var items: [Blocked] = []
    @State private var error: String?
    @State private var reload = 0
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("屏蔽名单").font(.system(size: 38, weight: .semibold))
            ForEach(items) { item in
                HStack {
                    Text(item.name); Spacer()
                    Button("解除屏蔽") {
                        Task {
                            do { _ = try await api.request("emby.setBlocked", ["id": .string(item.id), "name": .string(item.name), "blocked": .bool(false)]); reload += 1 }
                            catch is CancellationError {} catch { self.error = error.localizedDescription }
                        }
                    }.buttonStyle(TVActionStyle(compact: true))
                }
            }
            if let error { RetryMessage(text: error) { reload += 1 } }
            else if items.isEmpty { Text("暂无屏蔽内容") }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
        .toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal)
        .task(id: reload) {
            do { items = try await api.request("emby.blockedList").decode([Blocked].self); error = nil }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}

struct DiscoveryCategory: Decodable, Identifiable {
    let id: String
    let label: String
    let source: String
    let enabled: Bool
}

struct DiscoveryEntry: Decodable, Identifiable {
    let id: String
    let source: String
    let external_id: String
    let media_type: String
    let title: String
    let subtitle: String?
    var image_url: String? = nil
    let rank: Int?
    let rating: Double?
    var score: Int64? = nil
    let weekday: Int?
    let broadcast_time: String?
    let timezone: String?
    // 与 Android 一致：番号榜显示番号与战力，不显示评分。
    private var isJav: Bool { source == "javranking" }
    var shownRating: Double? { isJav ? nil : rating.flatMap { $0 > 0 ? $0 : nil } }
    var meta: String? {
        let text = [subtitle, isJav ? external_id : nil, isJav ? score.map { "战力 \($0)" } : nil]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        return text.isEmpty ? nil : text
    }
    var explicitTime: String? {
        guard timezone == "Asia/Tokyo", let value = broadcast_time else { return nil }
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts.allSatisfy({ $0.count == 2 }),
              let hour = Int(parts[0]), let minute = Int(parts[1]), (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return value
    }
}

struct DiscoveryView: View {
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let calendar: Bool
    @State private var categories: [DiscoveryCategory] = []
    @State private var category = ""
    @State private var primary = ""
    @State private var rows: [DiscoveryEntry] = []
    @State private var page = 0
    @State private var requestedPage = 1
    @State private var day = 0
    @State private var error: String?
    @State private var actionError: String?
    @State private var reload = 0
    @State private var loading = false
    @State private var opening = false
    @State private var ended = false
    private var scope: String { "\(primary):\(category)" }
    @State private var loadedScope = ""
    @State private var generation = UUID()
    private var targetPage: Int { scope == loadedScope && page > 0 ? requestedPage : 1 }
    private var hasPodium: Bool { rows.count >= 3 && rows.prefix(3).map(\.rank) == [1, 2, 3] }
    init(api: MediaAPI, calendar: Bool) {
        self.api = api; self.calendar = calendar
        var dates = Calendar(identifier: .gregorian); dates.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        _day = State(initialValue: calendar ? (dates.component(.weekday, from: Date()) + 5) % 7 + 1 : 0)
    }
    var body: some View {
        Group { if calendar { calendarBody } else { rankingBody } }
        .toolbar(.hidden, for: .navigationBar).ignoresSafeArea(.container, edges: .horizontal)
        .task {
            do {
                if model.privateSpace { primary = model.account?.server ?? "" }
                else {
                    let accounts = try await api.request("account.listAccounts").decode([MediaAccount].self)
                    primary = accounts.first { $0.aggregate_primary == true }?.server ?? model.account?.server ?? ""
                }
                if !calendar {
                    categories = try await api.request("emby.extRankings", ["server_id": .string(primary), "include_disabled": .bool(true)], snapshot: { value in if let cached = try? value.decode([DiscoveryCategory].self) { categories = cached } }).decode([DiscoveryCategory].self)
                    category = categories.first { $0.enabled }?.id ?? categories.first?.id ?? ""
                }
            } catch is CancellationError {} catch { if invalidatesDisplay(error) { rows = []; categories = [] }; self.error = error.localizedDescription }
        }
        .task(id: "\(scope):\(targetPage):\(reload)") {
            guard !primary.isEmpty, calendar || !category.isEmpty else { return }
            let requestedScope = scope, target = targetPage
            if requestedScope != loadedScope { rows = []; page = 0 }
            let prefix = target > 1 ? rows : []
            let request = UUID(); generation = request
            loading = true; defer { if generation == request { loading = false } }
            do {
                var args: [String: JSONValue] = ["server_id": .string(primary)]
                if !calendar { args["category_id"] = .string(category); args["page"] = .number(Double(target)) }
                let list = try await api.request(calendar ? "emby.extCalendar" : "emby.extRankingItems", args, snapshot: { value in
                    guard generation == request, target == 1, rows.isEmpty, let cached = try? value.decode([DiscoveryEntry].self) else { return }
                    var seen = Set<String>(); rows = cached.filter { seen.insert($0.id).inserted }
                }).decode([DiscoveryEntry].self)
                try Task.checkCancellation()
                guard generation == request else { return }
                var seen = Set(prefix.map(\.id)); rows = prefix + list.filter { seen.insert($0.id).inserted }
                // 与 Android 一致：空页、没有新条目或到达第 500 页时停止续页。
                loadedScope = requestedScope; page = target; requestedPage = target
                ended = list.isEmpty || (target > 1 && rows.count == prefix.count) || target >= 500; error = nil
            } catch is CancellationError {} catch {
                guard generation == request else { return }
                if invalidatesDisplay(error) { rows = []; page = 0; ended = false }
                self.error = error.localizedDescription
            }
        }
    }
    private var calendarBody: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                Text("追剧日历").font(.system(size: 38, weight: .semibold))
                weekBar
                if let error { RetryMessage(text: error) { reload += 1 } }
                if let actionError { Text(actionError).foregroundStyle(.orange) }
                ForEach(Array(Set(visibleRows.map { $0.explicitTime ?? "未提供" })).sorted(), id: \.self) { time in
                    HStack(alignment: .top, spacing: 24) {
                        VStack(spacing: 10) {
                            Text(time).font(.system(size: 24, weight: .semibold)).monospacedDigit()
                            Capsule().fill(TVPalette.accent(model.privateSpace, palette: model.colorScheme).opacity(0.35)).frame(width: 3, height: 130)
                        }.frame(width: 110)
                        VStack(spacing: 20) {
                            ForEach(visibleRows.filter { ($0.explicitTime ?? "未提供") == time }) { entry in discoveryRow(entry) }
                        }
                    }
                }
                if visibleRows.isEmpty && !loading && error == nil { Text("这一天没有放送").foregroundStyle(.secondary) }
                Text("周历：日本时区 · 未提供的时间不作推算").font(.system(size: 22)).foregroundStyle(.secondary)
                if loading { TVLoading() }
                else if rows.isEmpty && error == nil { Text("暂无内容").foregroundStyle(.secondary) }
            }.padding(.horizontal, 64).padding(.vertical, 28)
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
    }
    private var rankingSources: [String] { categories.map(\.source).reduce(into: []) { if !$0.contains($1) { $0.append($1) } } }
    private static func sourceLabel(_ source: String) -> String {
        switch source {
        case "tmdb": return "TMDb"
        case "douban": return "豆瓣"
        case "bangumi": return "Bangumi"
        case "hongguo": return "红果"
        case "huangguoai": return "黄果 AI"
        case "javranking": return "JavRanking"
        default: return source.isEmpty ? "其它" : source
        }
    }
    // Android TV 榜单墙：左侧按来源分组目录，右侧前三名大卡及两列紧凑行，靠近末尾自动续页。
    private var rankingBody: some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("排行榜").font(.system(size: 38, weight: .semibold))
                    ForEach(rankingSources, id: \.self) { source in
                        Text(Self.sourceLabel(source)).font(.system(size: 20)).foregroundStyle(.secondary).padding(.top, 14)
                        ForEach(categories.filter { $0.source == source }) { entry in
                            Button(entry.label + (entry.enabled ? "" : " · 未开启")) { category = entry.id }
                                .buttonStyle(TVActionStyle(primary: category == entry.id, compact: true)).focusEffectDisabled()
                        }
                    }
                }.padding(.horizontal, 48).padding(.vertical, 28)
            }.frame(width: 400).focusSection()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 24) {
                    if let selected = categories.first(where: { $0.id == category }) {
                        Text(Self.sourceLabel(selected.source) + " · " + selected.label).font(.system(size: 26)).foregroundStyle(.secondary)
                    }
                    if let error { RetryMessage(text: error) { reload += 1 } }
                    if let actionError { Text(actionError).foregroundStyle(.orange) }
                    if hasPodium {
                        HStack(alignment: .bottom, spacing: 32) {
                            podium(rows[1], place: 2)
                            podium(rows[0], place: 1)
                            podium(rows[2], place: 3)
                        }.padding(.vertical, 20)
                    }
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 20), GridItem(.flexible())], alignment: .leading, spacing: 20) {
                        ForEach(rankRest) { entry in
                            discoveryRow(entry).onAppear { if rankRest.suffix(4).contains(where: { $0.id == entry.id }) { loadMore() } }
                        }
                    }
                    if loading { TVLoading() }
                    else if rows.isEmpty && error == nil { Text("暂无内容").foregroundStyle(.secondary) }
                }.padding(.horizontal, 48).padding(.vertical, 28)
            }.focusSection()
        }.buttonStyle(TVActionStyle()).focusEffectDisabled()
    }
    private var rankRest: [DiscoveryEntry] { hasPodium ? Array(rows.dropFirst(3)) : rows }
    private func loadMore() {
        guard !loading, error == nil, !ended, page > 0, scope == loadedScope else { return }
        requestedPage = page + 1
    }
    private var visibleRows: [DiscoveryEntry] { rows.filter { day == 0 || $0.weekday == day } }
    private var weekBar: some View {
        HStack(spacing: 16) {
            Button("全部") { day = 0 }.buttonStyle(TVActionStyle(primary: day == 0, compact: true))
            ForEach(1...7, id: \.self) { weekday in
                Button { day = weekday } label: {
                    VStack(spacing: 10) {
                        Text(weekday == todayWeekday ? "今天" : "星期" + ["一", "二", "三", "四", "五", "六", "日"][weekday - 1])
                        Text(dateLabel(weekday)).font(.system(size: 32, weight: .semibold)).monospacedDigit()
                        Text("\(rows.filter { $0.weekday == weekday }.count) 部").font(.system(size: 20))
                    }.frame(maxWidth: .infinity)
                }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                    .background(day == weekday ? TVPalette.accent(model.privateSpace, palette: model.colorScheme).opacity(0.18) : Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 18))
            }
        }
    }
    private var japanCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!; return calendar
    }
    private var todayWeekday: Int { (japanCalendar.component(.weekday, from: Date()) + 5) % 7 + 1 }
    private func dateLabel(_ day: Int) -> String {
        let date = japanCalendar.date(byAdding: .day, value: day - todayWeekday, to: Date())!
        return String(format: "%02d", japanCalendar.component(.day, from: date))
    }
    private func podium(_ entry: DiscoveryEntry, place: Int) -> some View {
        Button { open(entry) } label: {
            VStack(spacing: 18) {
                Text(place == 1 ? "冠军" : place == 2 ? "亚军" : "季军").foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme))
                RemoteArtwork(url: entry.image_url, loader: model.images, width: place == 1 ? 250 : 220, height: place == 1 ? 375 : 330)
                Text(entry.title).font(.system(size: 26, weight: .semibold)).lineLimit(2)
                if let score = entry.shownRating { Text(String(format: "%.1f", score)).foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
                if let meta = entry.meta { Text(meta).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1) }
            }.frame(maxWidth: .infinity).padding(24)
        }.buttonStyle(TVRowStyle()).focusEffectDisabled().disabled(opening || model.busy)
    }
    private func discoveryRow(_ entry: DiscoveryEntry) -> some View {
        Button { open(entry) } label: {
            HStack(spacing: 24) {
                if !calendar, let rank = entry.rank { Text(String(rank)).font(.system(size: 30, weight: .semibold)).frame(width: 56) }
                RemoteArtwork(url: entry.image_url, loader: model.images, width: 112, height: 168)
                VStack(alignment: .leading, spacing: 12) {
                    Text(entry.title).font(.system(size: 26, weight: .medium)).lineLimit(2)
                    if let meta = entry.meta { Text(meta).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer()
                if let rating = entry.shownRating { Text(String(format: "%.1f", rating)).foregroundStyle(TVPalette.accent(model.privateSpace, palette: model.colorScheme)) }
            }.padding(.vertical, 12)
        }.buttonStyle(TVRowStyle()).focusEffectDisabled().background(.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 14)).disabled(opening || model.busy)
    }
    private func open(_ entry: DiscoveryEntry) {
        guard !opening else { return }; opening = true
        let server = primary
        Task {
            defer { opening = false }
            do {
                let result = try await api.request("emby.extResolve", ["server_id": .string(server), "source": .string(entry.source),
                    "external_id": .string(entry.external_id), "media_type": .string(entry.media_type)])
                guard let id = result["item_id"].string, let type = result["item_type"].string else { throw CoreFailure(code: "E_PROTOCOL") }
                let target = MediaItem.route(id: id, name: entry.title, type: type)
                if model.account?.server == server { model.pendingItem = target }
                else { try await model.switchServer(server, opening: target) }
            } catch is CancellationError {} catch { actionError = error.localizedDescription }
        }
    }
}

struct PersonView: View {
    struct Person: Decodable {
        let name: String
        let overview: String
        let birthday: String?
        let death_day: String?
        let birth_place: String?
        let has_primary: Bool
    }
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let item: MediaItem
    @State private var detail: Person?
    @State private var error: String?
    @State private var reload = 0
    @State private var expanded = false
    @State private var type = "Movie"
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .top, spacing: 28) {
                    Poster(id: item.id, enabled: detail?.has_primary ?? item.has_primary, loader: model.images, width: 176, height: 176)
                        .clipShape(Circle())
                    VStack(alignment: .leading, spacing: 16) {
                        Text(detail?.name ?? item.name).font(.system(size: 38, weight: .semibold))
                        if let detail {
                            HStack(spacing: 20) {
                                if let birthday = detail.birthday { Text("出生：" + birthday.prefix(10)) }
                                if let death = detail.death_day { Text("逝世：" + death.prefix(10)) }
                                if let place = detail.birth_place { Text(place) }
                            }.font(.system(size: 22)).foregroundStyle(.secondary)
                            if !detail.overview.isEmpty {
                                Button { expanded.toggle() } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(detail.overview).lineLimit(expanded ? nil : 3)
                                        Text(expanded ? "收起简介" : "展开简介").font(.system(size: 22)).foregroundStyle(.secondary)
                                    }
                                }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                            }
                        }
                        if let error { RetryMessage(text: error) { reload += 1 } }
                    }
                }.padding(.horizontal, 64).padding(.vertical, 28)
                HStack(spacing: 20) {
                    ForEach([("Movie", "电影"), ("Series", "剧集")], id: \.0) { option in
                        Button(option.1) { type = option.0 }.buttonStyle(TVActionStyle(primary: type == option.0, compact: true))
                    }
                }.focusEffectDisabled().padding(.horizontal, 64)
                PersonWorksView(api: api, person: item.id, type: type).id(type)
            }
        }.ignoresSafeArea(.container, edges: .horizontal).toolbar(.hidden, for: .navigationBar)
        .task(id: reload) {
            do { detail = try await api.request("emby.personDetail", ["person_id": .string(item.id)], snapshot: { value in if let cached = try? value.decode(Person.self) { detail = cached } }).decode(Person.self); error = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { detail = nil }; self.error = error.localizedDescription }
        }
    }
}

private struct PersonWorksView: View {
    @Environment(\.tvPosterWidth) private var posterWidth
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let person: String
    let type: String
    @State private var state = PersonWorksState()
    @State private var loading = false
    @State private var error: String?
    @State private var reload = 0
    @State private var revision = -1
    @State private var generation = UUID()
    @FocusState private var focus: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("已加载 \(state.items.count)" + (state.more ? "+" : "") + (state.total.map { " · 共 \($0) 部" } ?? ""))
                .font(.system(size: 22)).foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: posterWidth), spacing: 20)], alignment: .leading, spacing: 40) {
                ForEach(state.items) { entry in
                    NavigationLink(value: entry) { MediaCard(item: entry, images: model.images) }
                        .buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focus, equals: entry.id)
                }
            }
            if let error { RetryMessage(text: error) { reload += 1 } }
            else if loading { TVLoading("正在加载作品") }
            else if state.items.isEmpty && !state.more { Text("暂无作品").foregroundStyle(.secondary) }
            if state.more && !loading && error == nil {
                Button("加载更多") { reload += 1 }.buttonStyle(TVActionStyle()).focusEffectDisabled()
            }
        }.padding(.horizontal, 64).padding(.bottom, 40)
        .onChange(of: focus) { _, id in
            if let id, let index = state.items.firstIndex(where: { $0.id == id }),
               index >= max(0, state.items.count - 5), state.more, !loading, error == nil { reload += 1 }
        }
        .task(id: "\(reload):\(model.userStateRevision)") {
            let requestedRevision = model.userStateRevision
            let refresh = revision != requestedRevision
            guard refresh || state.more else { return }
            let request = UUID(); generation = request
            loading = true; defer { if generation == request { loading = false } }
            do {
                var next = refresh ? PersonWorksState() : state
                let target = refresh ? max(state.cursor, 30) : state.cursor + 30
                repeat {
                    let page = try await api.request("emby.personItemsPage", ["person_id": .string(person), "type": .string(type),
                        "start_index": .number(Double(next.cursor)), "limit": .number(30)], snapshot: { value in
                            if generation == request, state.items.isEmpty, let page = try? value.decode(PersonWorksPage.self) { state.items = page.items }
                        }).decode(PersonWorksPage.self)
                    try Task.checkCancellation()
                    try next.append(page)
                } while refresh && next.more && next.cursor < target
                guard generation == request else { return }
                state = next; revision = requestedRevision; error = nil
            } catch is CancellationError {} catch {
                guard generation == request else { return }
                if invalidatesDisplay(error) { state = PersonWorksState() }
                self.error = error.localizedDescription
            }
        }
    }
}

struct CollectionView: View {
    struct Members: Decodable { let movies: [MediaItem]; let series: [MediaItem]; let others: [MediaItem] }
    @EnvironmentObject private var model: AppModel
    let api: MediaAPI
    let item: MediaItem
    @State private var members: Members?
    @State private var error: String?
    @State private var reload = 0
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Text(item.name).font(.system(size: 38, weight: .semibold)).padding(.horizontal, 64)
                if let error { RetryMessage(text: error) { reload += 1 } }
                if let members {
                    if !members.movies.isEmpty { TVSectionHeading("电影 · \(members.movies.count)").padding(.horizontal, 64); MediaRow(items: members.movies) }
                    if !members.series.isEmpty { TVSectionHeading("剧集 · \(members.series.count)").padding(.horizontal, 64); MediaRow(items: members.series) }
                    if !members.others.isEmpty { TVSectionHeading("其它 · \(members.others.count)").padding(.horizontal, 64); MediaRow(items: members.others) }
                    if members.movies.isEmpty && members.series.isEmpty && members.others.isEmpty { Text("合集暂无内容").padding(.horizontal, 64).padding(.vertical, 28) }
                } else if error == nil { TVLoading() }
            }.padding(.vertical, 40)
        }.ignoresSafeArea(.container, edges: .horizontal).toolbar(.hidden, for: .navigationBar)
        .task(id: "\(reload):\(model.userStateRevision)") {
            do { members = try await api.request("emby.collectionItems", ["item_id": .string(item.id)], snapshot: { value in if let cached = try? value.decode(Members.self) { members = cached } }).decode(Members.self); error = nil }
            catch is CancellationError {} catch { if invalidatesDisplay(error) { members = nil }; self.error = error.localizedDescription }
        }
    }
}
