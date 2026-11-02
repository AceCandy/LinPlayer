import SwiftUI

@MainActor final class AppModel: ObservableObject {
    @Published var colorScheme = UserDefaults.standard.string(forKey: "colorScheme") ?? "amber" {
        didSet { UserDefaults.standard.set(colorScheme, forKey: "colorScheme") }
    }
    @Published var loadingStyle = UserDefaults.standard.integer(forKey: "loadingStyle") {
        didSet { UserDefaults.standard.set(loadingStyle, forKey: "loadingStyle") }
    }
    @Published var api: MediaAPI?
    @Published var account: MediaAccount?
    @Published var ready = false
    @Published var needsLogin = false
    @Published var fatalError: String?
    @Published var notice: String?
    // 成功结果的短暂提示（对应 Android toast）；错误仍用 notice 弹窗。
    @Published var toast: String?
    @Published var busy = false
    @Published var identity = UUID()
    @Published var userStateRevision = 0
    @Published private(set) var writingItems = Set<String>()
    @Published var privateSpace = false
    @Published var backupEnabled = false
    @Published var backupExport: JSONValue = .null
    @Published var backupPreview: JSONValue = .null
    var backupContent: String?
    @Published var companionStatus: JSONValue = .null
    var remoteEpoch = ""
    var remoteNavigation: ((String) async -> Bool)?
    var remotePlayback: ((String) async -> Bool)?
    private var companionTasks: [String: Task<Void, Never>] = [:]
    @Published var pendingItem: MediaItem?
    @Published var liuying: TVLiuYingSession?
    @Published var playback: PlaybackController?
    let trackMemory = SessionTrackMemory()
    let remoteFocus = TVRemoteFocus()
    let images: ImageLoader

    init(imageConfiguration: URLSessionConfiguration = .ephemeral) {
        images = ImageLoader(configuration: imageConfiguration)
    }
    private var core: CoreClient?
    private var started = false
    @Published var switchingPlayback = false
    @Published var sceneActive = true
    private var exitingPlayback = false
    private var playbackOrigin = ""
    @Published var returnedOrigin = ""
    @Published var returnedItem: MediaItem?
    @Published var returnRevision = 0

    // 写入归宿主所有，懒列表回收单张卡片不能取消已确认的操作。
    // 卡片菜单写入；server 为跨服搜索结果的来源服务器，成功提示与 Android cardActions 一致。
    func setItemState(_ id: String, favorite: Bool? = nil, played: Bool? = nil, server: String? = nil) {
        guard let api, !id.isEmpty, !writingItems.contains(id) else { return }
        let epoch = api.epoch
        writingItems.insert(id)
        Task {
            defer { writingItems.remove(id) }
            do {
                var args: [String: JSONValue] = ["item_id": .string(id)]
                if let server { args["server_id"] = .string(server) }
                if let favorite {
                    args["fav"] = .bool(favorite); _ = try await api.request("emby.setFavorite", args)
                    if api.epoch == epoch { toast = favorite ? "已加入收藏" : "已取消收藏" }
                } else if let played {
                    args["played"] = .bool(played); _ = try await api.request("emby.setPlayed", args)
                    if api.epoch == epoch { toast = played ? "已标为已看" : "已标为未看" }
                }
                // 成功刷新由核心 data.userState 通知，不再重复推进代次。
            } catch is CancellationError {} catch {
                if api.epoch == epoch { notice = error.localizedDescription }
            }
        }
    }

    func start() async {
        guard !started else { return }; started = true
        do {
            let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("LinPlayer")
            let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
            let client = try await Task.detached { try NativeCore.initialize(dataRoot: root, version: version) }.value
            core = client
            client.onEvent = { [weak self] name, data in
                if name == "data.userState" {
                    Task { @MainActor in self?.api?.userStateChanged(data) }
                }
                if name == "companion.request" { Task { @MainActor in self?.startCompanionTask(data) } }
                if name == "companion.cancel" { Task { @MainActor in self?.cancelCompanionTask(data) } }
                if name == "companion.status" { Task { @MainActor in self?.companionStatus = data } }
                if name == "localserve.ready" {
                    Task { @MainActor in self?.images.setChannel(base: data["baseUrl"].string, token: data["token"].string) }
                }
            }
            client.start()
            let caps = try await client.systemCapabilities()
            guard let device = caps["device_id"].string, !device.isEmpty else { throw CoreFailure(code: "E_STORAGE") }
            let service = MediaAPI(core: client); service.deviceID = device
            service.onAuthExpired = { [weak self] in
                guard let self else { return }
                self.remoteEpoch = ""
                self.playback?.suspend()
                self.api?.use(nil)
                self.images.clearIdentity()
                self.needsLogin = true
                Task { await self.closePlayer() }
            }
            service.onUserStateChanged = { [weak self] in self?.userStateRevision += 1 }
            api = service
            try await restoreSession()
            ready = true
        } catch {
            fatalError = "无法恢复应用必要状态。请检查系统存储与钥匙串后重新启动；现有数据未重置。"
        }
    }

    private func startCompanionTask(_ event: JSONValue) {
        guard let id = event["request_id"].string else { return }
        companionTasks[id]?.cancel()
        companionTasks[id] = Task { @MainActor [weak self] in
            await self?.handleCompanion(event)
            self?.companionTasks[id] = nil
        }
    }

    private func cancelCompanionTask(_ event: JSONValue) {
        guard let id = event["request_id"].string else { return }
        companionTasks[id]?.cancel()
        companionTasks[id] = nil
    }

    private func restoreSession(refreshCompanion: Bool = true) async throws {
        guard let core, let api else { return }
        let value = try await core.embyCurrentSession()
        let accounts = try await core.accountListAccounts().decode([MediaAccount].self)
        account = accounts.first(where: \.active)
        if let s = try MediaSession.restore(value) {
            api.use(s); images.use(server: s.server); needsLogin = false
        } else {
            api.use(nil); needsLogin = true
        }
        privateSpace = false
        identity = UUID()
        if refreshCompanion { try await refreshCompanionSession(privateSpace: false) }
    }

    func companion(_ command: String, _ args: [String: JSONValue] = [:]) async throws -> JSONValue {
        guard let core else { throw CoreFailure(code: "E_SESSION") }
        return try await core.call(command, args)
    }

    func changePrivateSpace(_ enabled: Bool) async throws {
        guard let api, !busy else { return }
        busy = true; defer { busy = false }
        await closePlayer()
        let previous = privateSpace
        try await refreshCompanionSession(privateSpace: true)
        do {
            try await api.setPrivateSpace(enabled)
            privateSpace = enabled
            try await refreshCompanionSession(privateSpace: enabled)
        } catch {
            try? await refreshCompanionSession(privateSpace: previous)
            throw error
        }
        if let server = api.session?.server { images.use(server: server) }
        pendingItem = nil; identity = UUID()
    }

    func switchServer(_ server: String, opening item: MediaItem? = nil, refreshCompanion: Bool = true) async throws {
        guard !privateSpace else { throw CoreFailure(code: "E_UNSUPPORTED") }
        guard let core, !busy else { throw CoreFailure(code: "E_BUSY") }
        busy = true; defer { busy = false }
        if refreshCompanion { remoteEpoch = "" }
        await closePlayer()
        if refreshCompanion { try await refreshCompanionSession(privateSpace: false) }
        let accounts = try await core.call("account.setActiveServer", ["server_id": .string(server)]).decode([MediaAccount].self)
        guard let target = accounts.first(where: { $0.server == server && $0.active }) else { throw CoreFailure(code: "E_PROTOCOL") }
        try await restoreSession(refreshCompanion: refreshCompanion)
        guard api?.session?.server == target.line_url, api?.session?.user_id == target.user_id else { throw CoreFailure(code: "E_PROTOCOL") }
        pendingItem = item
    }

    func reloadAccounts(refreshCompanion: Bool = true) async throws { try await restoreSession(refreshCompanion: refreshCompanion) }

    func testConnection(server: String, username: String, password: String) async throws -> JSONValue {
        guard let core, let api else { throw CoreFailure(code: "E_SESSION") }
        return try await core.call("account.testConnection", ["server": .string(server.trimmingCharacters(in: .whitespacesAndNewlines)),
            "username": .string(username), "password": .string(password), "device_id": .string(api.deviceID)])
    }

    func login(server: String, username: String, password: String, name: String = "") async throws {
        guard let core, let api, !busy else { return }
        busy = true; defer { busy = false }
        if let playback { await playback.close(); self.playback = nil }
        _ = try await core.embyLogin(["server": .string(server.trimmingCharacters(in: .whitespacesAndNewlines)),
            "username": .string(username), "password": .string(password), "device_id": .string(api.deviceID)])
        try await restoreSession()
        let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !displayName.isEmpty, let account {
            _ = try await api.request("account.updateAccount", ["server_id": .string(account.server), "name": .string(displayName)])
            try await restoreSession()
        }
    }

    func logout() async {
        guard let core, let api, let account, !busy else { return }
        busy = true; defer { busy = false }
        if let playback { await playback.close(); self.playback = nil }
        var serverOK = false
        do { serverOK = try await api.request("emby.logout", ["server_id": .string(account.server)])["server_ok"].bool == true }
        catch { serverOK = false }
        do {
            _ = try await core.accountRemoveAccount(["server_id": .string(account.server)])
            api.use(nil); self.account = nil; needsLogin = true; identity = UUID(); images.clearIdentity()
            try await refreshCompanionSession(privateSpace: false)
            notice = serverOK ? "已退出账号。" : "本机账号已移除；服务端未确认登出。"
        } catch {
            notice = "本机账号移除失败，尚未完成退出。请重试。"
        }
    }

    func openPlayer(item: String, title: String, version: String?, fromStart: Bool, subtitle: InitialSubtitle? = nil, audio: InitialAudio? = nil, origin: String? = nil, speedScope: String = "normal") {
        guard playback == nil, let api else { return }
        playbackOrigin = origin ?? item
        trackMemory.clear()
        playback = PlaybackController(api: api, item: item, title: title, version: version, fromStart: fromStart, subtitle: subtitle, audio: audio, speedScope: speedScope, trackMemory: trackMemory)
    }

    func switchPlayer(item: String, title: String, version: String? = nil, fromStart: Bool = false) async {
        guard let old = playback, let api, !switchingPlayback, !exitingPlayback else { return }
        switchingPlayback = true; defer { switchingPlayback = false }
        let prepared = old.takePrepared(for: item)
        await old.close()
        guard playback === old, !exitingPlayback else { return }
        // 切换版本时旧轨道身份不再可信。
        if version != nil && item == old.item && version != old.version { trackMemory.clear() }
        // 同条目同版本重试时保留详情页显式预选的轨道。
        let retry = item == old.item && version == old.version
        let next = PlaybackController(api: api, item: item, title: title, version: version, fromStart: fromStart,
            subtitle: retry ? old.initialSubtitle : nil, audio: retry ? old.initialAudio : nil, speedScope: old.speedScope, trackMemory: trackMemory, preparedID: prepared)
        if !sceneActive { next.suspend() }
        playback = next
        userStateRevision += 1
        if let warning = old.syncWarning { next.syncWarning = warning }
    }

    func closePlayer() async {
        guard let player = playback else {
            await liuying?.stop(); liuying = nil; return
        }
        exitingPlayback = true
        defer { exitingPlayback = false }
        trackMemory.clear()
        let wasLiuYing = liuying != nil
        await liuying?.stop(); liuying = nil
        await player.close()
        if playback === player {
            if !wasLiuYing {
                returnedOrigin = playbackOrigin
                returnedItem = .route(id: player.item, name: player.title, type: "Episode")
                returnRevision += 1
            }
            playback = nil
        }
        userStateRevision += 1
        if let message = player.syncWarning { notice = message }
    }
}
