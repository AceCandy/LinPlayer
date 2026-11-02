import AetherEngine
import Combine
import SwiftUI

@MainActor final class PlaybackController: ObservableObject {
    let api: MediaAPI
    let danmaku: TVDanmakuSession
    let item: String
    let title: String
    let version: String?
    let fromStart: Bool
    let speedScope: String
    @Published private(set) var engine: AetherEngine?
    @Published var message: String?
    // 无法继续播放的失败，与倍率保存、音轨切换等可恢复提示分开呈现。
    @Published var failure: String?
    @Published private(set) var autoRetried = false
    @Published var syncWarning: String?
    @Published var loading = true
    @Published var closing = false
    @Published var changingTrack = false
    @Published var bufferedPosition: Double = 0
    @Published var position: Double = 0
    @Published var subtitleText = ""
    @Published var subtitleNote: String?
    @Published var subtitleStyle: JSONValue = .null
    @Published var subtitleStyleError: String?
    @Published private var subtitleStyleWork: Task<Void, Never>?
    @Published var episodes: [MediaItem] = []
    @Published var queueLoading = true
    @Published var queueError: String?
    @Published var ended = false
    @Published var speed: Float = 1
    @Published var pictureMode = "fit"
    @Published var versions: [MediaVersion] = []
    @Published var versionError: String?
    @Published var skipInfo: PlaybackSkip?
    @Published var skipError: String?
    @Published var markers: SkipMarkerSelection?
    @Published var markerBusy = false
    @Published var markerError: String?
    @Published var skipIntroEnabled: Bool?
    @Published var skipOutroEnabled: Bool?
    private var markerWork: Task<Void, Never>?
    private var skipWork: Task<Void, Never>?
    private var skipSeekWork: Task<Void, Never>?
    private var skippedEnds = Set<Double>()
    private var suppressSavedSkip = false
    private var queueWork: Task<Void, Never>?
    private var versionWork: Task<Void, Never>?
    let initialAudio: InitialAudio?
    let initialSubtitle: InitialSubtitle?
    @Published private(set) var actualVersion = ""
    private var trackPrefs: JSONValue = .null
    private var manualAudio = false
    private var manualSubtitle = false
    private let trackMemory: SessionTrackMemory
    private var manualChoices: [String: SessionTrackMemory.Choice] = [:]
    private var restoredAudio = false
    private var restoredSubtitle = false
    private var appliedAudio: [TrackInfo] = []
    private var appliedSubtitles: [TrackInfo] = []
    private var subscriptions = Set<AnyCancellable>()
    private var startWork: Task<Void, Never>?
    private var reportWork: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var audioPreferenceWork: Task<Void, Never>?
    private var subtitlePreferenceWork: Task<Void, Never>?
    private var trackWork: Task<Void, Never>?
    private var closeWork: Task<Void, Never>?
    @Published private var speedWork: Task<Void, Never>?
    private var suspended = false
    private var resolved = false
    private var loadingEngine = false
    private var trackWasPaused = false

    init(api: MediaAPI, item: String, title: String, version: String?, fromStart: Bool, subtitle: InitialSubtitle? = nil, audio: InitialAudio? = nil, speedScope: String = "normal", trackMemory: SessionTrackMemory = SessionTrackMemory()) {
        self.speedScope = speedScope
        self.trackMemory = trackMemory
        danmaku = TVDanmakuSession(api: api, item: item)
        initialAudio = audio; initialSubtitle = subtitle
        self.api = api; self.item = item; self.title = title; self.version = version; self.fromStart = fromStart
        do {
            let player = try AetherEngine()
            player.deactivatesAudioSessionOnStop = true
            engine = player
            player.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &subscriptions)
            player.$audioTracks.sink { [weak self] _ in
                Task { @MainActor in self?.applyTrackPreferences() }
            }.store(in: &subscriptions)
            player.$subtitleTracks.sink { [weak self] _ in
                Task { @MainActor in self?.applyTrackPreferences() }
            }.store(in: &subscriptions)
            player.clock.$currentTime.sink { [weak self] value in
                guard let self else { return }
                if value.isFinite && value >= 0 && abs(self.position - value) >= 1 { self.position = value }
                Task { @MainActor in self.updateSkip() }
            }.store(in: &subscriptions)
            player.clock.$bufferedPosition.sink { [weak self] value in
                guard let self, value.isFinite, value >= 0 else { return }
                if value == 0 || abs(self.bufferedPosition - value) >= 1 { self.bufferedPosition = value }
            }.store(in: &subscriptions)
            player.clock.$sourceTime.sink { [weak self] time in self?.refreshSubtitles(at: time) }.store(in: &subscriptions)
            player.$subtitleCues.sink { [weak self] _ in
                // Published 在赋值前发送；下一轮读取引擎，避免读取上一批 cue。
                Task { @MainActor in self?.refreshSubtitles() }
            }.store(in: &subscriptions)
            player.$state.sink { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    if self.suspended || self.closing || (self.changingTrack && self.trackWasPaused) {
                        if state == .playing { self.engine?.pause() }
                    }
                    if case .error = state { self.failure = "播放失败，请重试或返回。" }
                    if state == .ended {
                        let duration = self.engine?.duration ?? 0
                        let position = max(self.position, self.engine?.currentTime ?? 0)
                        if duration.isFinite && duration > 0 && position > 0 && position >= duration - 5 {
                            self.ended = true; self.message = "播放结束"
                        } else { self.failure = "播放提前中断，请重试。" }
                        self.report()
                    }
                }
            }.store(in: &subscriptions)
        } catch { failure = "播放器初始化失败，请返回后重试。"; loading = false }
    }

    var paused: Bool { engine?.state != .playing }
    var canSeek: Bool { engine?.canSeek == true && !changingTrack && !closing }
    var audioSelectable: Bool {
        guard !loading, !closing, let engine else { return false }
        return engine.audioTracks.count > 1 && engine.videoRoute != .remoteBypass && engine.canSeek
    }
    var textTracks: [TrackInfo] {
        engine?.subtitleTracks ?? []
    }
    @Published private(set) var unsupportedSubtitleCount = 0

    func start() async {
        guard startWork == nil, !closing, engine != nil else { return }
        loadQueue()
        loadVersions()
        startWork = Task { await self.load() }
        await startWork?.value
    }

    private func load() async {
        guard let engine else { return }
        loading = true; message = nil
        do {
            let prefs = try await api.request("prefs.getPrefs")
            trackPrefs = prefs
            loadSubtitleStyle()
            let result = try await api.play(item: item, version: version, fromStart: fromStart, speedScope: speedScope)
            resolved = true
            actualVersion = result.media_source_id
            guard !closing else { loading = false; return }
            guard let url = URL(string: result.play_url), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { throw CoreFailure(code: "E_INVALID") }
            var unsupported = 0
            let external = (result.external_subs ?? []).compactMap { sub -> ExternalSubtitleTrack? in
                guard let url = URL(string: sub.url), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { unsupported += 1; return nil }
                let hint: String?
                switch sub.mime_type?.lowercased() {
                case "text/vtt": hint = "vtt"
                case "application/x-subrip": hint = "srt"
                case "text/x-ass", "text/x-ssa", "application/x-ass", "application/x-ssa": hint = "ass"
                default: hint = ["srt", "vtt", "ass", "ssa"].contains(url.pathExtension.lowercased()) ? url.pathExtension.lowercased() : nil
                }
                guard let hint else { unsupported += 1; return nil }
                // 核心地址自带资源鉴权；禁止把主服 token 额外加到跨源字幕请求。
                return ExternalSubtitleTrack(url: url, name: sub.title, language: sub.lang, isDefault: false, httpHeaders: [:], formatHint: hint)
            }
            unsupportedSubtitleCount = unsupported
            if unsupported > 0 { subtitleNote = "部分外挂字幕格式无法识别。" }
            let options = LoadOptions(httpHeaders: [:], preserveASSMarkup: true, externalSubtitles: external, autoplay: false)
            loadingEngine = true
            defer { loadingEngine = false }
            _ = try await engine.load(url: url, startPosition: result.resume_secs, options: options)
            try Task.checkCancellation()
            engine.clearSubtitle()
            let preferredSpeed = Float(prefs[speedScope == "liuying" ? "liuying_speed" : "default_speed"].number ?? 1)
            speed = min(max(preferredSpeed, 0.25), engine.maxSupportedRate)
            engine.setRate(speed)
            loading = false
            applyTrackPreferences()
            loadSkip()
            if speedScope != "liuying" { danmaku.resolve() }
            if !closing && !suspended { engine.play() }
            if !closing {
                ticker = Task { [weak self] in
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(10)) } catch { return }
                        guard let self, !self.closing else { return }; self.report()
                    }
                }
            }
        } catch {
            // 与 Android TV 一致：核心标记可重试的起播错误先自动重来一次。
            if !autoRetried, !closing, !Task.isCancelled, (error as? CoreFailure)?.retryable == true {
                autoRetried = true; await load(); return
            }
            loading = false
            failure = (error as? CoreFailure)?.errorDescription ?? "无法开始播放，请检查网络后重试。"
        }
    }

    var previousEpisode: MediaItem? {
        guard let index = episodes.firstIndex(where: { $0.id == item }), index > 0 else { return nil }
        return episodes[index - 1]
    }
    var nextEpisode: MediaItem? {
        guard let index = episodes.firstIndex(where: { $0.id == item }), index + 1 < episodes.count else { return nil }
        return episodes[index + 1]
    }

    func loadQueue() {
        queueWork?.cancel(); queueLoading = true; queueError = nil
        queueWork = Task {
            do {
                let list = try await api.playbackSeason(item: item)
                try Task.checkCancellation()
                guard !closing else { return }
                episodes = list; queueLoading = false
                // 剧集身份确定后进入记忆作用域，补记先于队列完成的手选，再尝试沿用上一集手选。
                trackMemory.enter(trackScope)
                if let scope = trackScope { manualChoices.forEach { trackMemory.record(scope, kind: $0.key, $0.value) } }
                restoreSessionTracks()
            } catch is CancellationError {} catch {
                guard !closing else { return }
                queueError = "无法加载当前季，请重试。"; queueLoading = false
            }
        }
    }

    func loadVersions() {
        versionWork?.cancel(); versionError = nil
        versionWork = Task {
            do {
                let prefs = try await api.request("prefs.getPrefs")
                let list = try await api.request("emby.itemMedia", ["item_id": .string(item), "version_regex": prefs["version_regex"]]).decode([MediaVersion].self)
                try Task.checkCancellation()
                guard !closing else { return }
                versions = list
            } catch is CancellationError {} catch {
                guard !closing else { return }
                versionError = "无法获取版本，请重试。"
            }
        }
    }

    var activeSkip: PlaybackSkip.Range? {
        guard !loading, !closing, let engine else { return nil }
        return skipInfo?.active(at: position, duration: engine.duration)
    }

    func loadSkip() {
        guard skipWork == nil, !markerBusy, !loading, !closing, let duration = engine?.duration, duration.isFinite, duration > 0 else { return }
        skipWork = Task {
            do {
                let info = try await api.request("player.chapterInfo", ["item_id": .string(item), "runtime_secs": .number(duration)]).decode(PlaybackSkip.self)
                guard !closing, !Task.isCancelled else { return }
                acceptSkip(info); updateSkip()
            } catch is CancellationError {} catch { if !closing { skipError = "片头片尾信息加载失败，可重试。" } }
        }
    }

    func retrySkip() { skipWork?.cancel(); skipWork = nil; loadSkip() }

    private func updateSkip() {
        if skipWork == nil { loadSkip() }
        guard !markerBusy, !suspended, !paused, skipInfo?.skip_auto == true,
              let engine, let range = activeSkip, !skippedEnds.contains(range.end) else { return }
        let hasFollowingContent = range.end < engine.duration || (!queueLoading && queueError == nil && nextEpisode != nil)
        guard hasFollowingContent else { return }
        skip(range)
    }

    func skip(_ range: PlaybackSkip.Range) {
        guard !markerBusy, canSeek, skipSeekWork == nil else { return }
        skippedEnds.insert(range.end)
        skipSeekWork = Task {
            await seek(to: range.end)
            skipSeekWork = nil
        }
    }

    func loadMarkers(scope: String? = nil) {
        guard !markerBusy, !closing, let duration = engine?.duration, duration.isFinite, duration > 0 else { return }
        let selected = scope ?? markers?.scope ?? "auto"
        markerBusy = true; markerError = nil; markers = nil
        markerWork = Task {
            defer { markerBusy = false; markerWork = nil }
            do {
                let value = try await api.request("player.getSkipRange", ["item_id": .string(item), "scope": .string(selected), "runtime_secs": .number(duration)]).decode(SkipMarkerSelection.self)
                if !closing { markers = value }
            } catch is CancellationError {} catch { if !closing { markerError = error.localizedDescription } }
        }
    }

    func mark(_ action: String) {
        guard !markerBusy, !closing, let markers, let engine, canSeek,
              engine.currentTime.isFinite, engine.duration.isFinite, engine.duration > 0 else { return }
        let position = engine.currentTime, duration = engine.duration
        markerBusy = true; markerError = nil
        markerWork = Task {
            defer { markerBusy = false; markerWork = nil }
            do {
                let value = try await api.request("player.setSkipRange", ["item_id": .string(item), "scope": .string(markers.scope),
                    "action": .string(action), "position": .number(position), "runtime_secs": .number(duration)]).decode(SkipMarkerSelection.self)
                guard !closing else { return }
                self.markers = value; suppressSavedSkip = true
                skipWork?.cancel(); await skipWork?.value; skipWork = nil
                skipInfo = nil; skippedEnds.removeAll()
                let info = try await api.request("player.chapterInfo", ["item_id": .string(item), "runtime_secs": .number(duration)]).decode(PlaybackSkip.self)
                guard !closing else { return }
                acceptSkip(info)
            } catch is CancellationError {} catch { if !closing { markerError = error.localizedDescription } }
        }
    }

    private func acceptSkip(_ info: PlaybackSkip) {
        skipInfo = info; skipError = nil
        // 刷新失败后也保留抑制，保存操作不能在重试时触发跳转。
        if suppressSavedSkip {
            if let engine, let range = info.active(at: engine.currentTime, duration: engine.duration) { skippedEnds.insert(range.end) }
            suppressSavedSkip = false
        }
    }

    func loadSkipPrefs() {
        Task {
            guard let prefs = try? await api.request("player.getPlaybackPrefs"), !closing else { return }
            skipIntroEnabled = prefs["skip_intro"].bool; skipOutroEnabled = prefs["skip_outro"].bool
        }
    }

    // key 为 skip_intro / skip_outro；关闭的一段核心不再返回区间，保存后刷新片头片尾信息。
    func setSkipPref(_ key: String, _ enabled: Bool) {
        guard !markerBusy, !closing else { return }
        markerBusy = true; markerError = nil
        markerWork = Task {
            defer { markerBusy = false; markerWork = nil }
            do {
                _ = try await api.request("player.setPlaybackPrefs", [key: .bool(enabled)])
                guard !closing else { return }
                if key == "skip_intro" { skipIntroEnabled = enabled } else { skipOutroEnabled = enabled }
                markerBusy = false; skipInfo = nil; retrySkip()
            } catch is CancellationError {} catch { if !closing { markerError = error.localizedDescription } }
        }
    }

    var canSetSpeed: Bool { !closing && !loading && !changingTrack && speedWork == nil && engine != nil }
    @Published private(set) var holdRate: Float?

    // 长按临时倍速：不写偏好，只在播放中生效；引擎按 maxSupportedRate 截断，界面显示实际值。
    func beginHoldSpeed(_ rate: Float) {
        guard holdRate == nil, canSetSpeed, !paused, let engine else { return }
        let value = min(rate, engine.maxSupportedRate)
        guard value > speed else { return }
        holdRate = value; engine.setRate(value)
    }
    func endHoldSpeed() {
        guard holdRate != nil else { return }
        holdRate = nil; engine?.setRate(speed)
    }

    func setSpeed(_ value: Float) -> Bool {
        guard canSetSpeed, let engine,
              value.isFinite, value >= 0.25, value <= min(4, engine.maxSupportedRate) else { return false }
        speedWork = Task {
            defer { speedWork = nil }
            do {
                _ = try await api.request("player.setPlaybackPrefs", [speedScope == "liuying" ? "liuying_speed" : "default_speed": .number(Double(value))])
                guard !closing else { return }
                speed = value; engine.setRate(value)
                if message == "倍率保存失败，请重试。" { message = nil }
            } catch is CancellationError {} catch {
                if !closing { message = "倍率保存失败，请重试。" }
            }
        }
        return true
    }

    func setPictureMode(_ mode: String) {
        guard let engine else { return }
        switch mode {
        case "fill": engine.videoGravity = .resizeAspectFill
        case "stretch": engine.videoGravity = .resize
        default: engine.videoGravity = .resizeAspect
        }
        pictureMode = mode
    }

    func setMuted(_ muted: Bool) { engine?.volume = muted ? 0 : 1 }
    func restart() async {
        guard canSeek else { return }
        await seek(to: 0)
        if !closing && !suspended { engine?.play() }
    }

    @discardableResult func toggle() -> Bool {
        guard !loading, !closing, !changingTrack, let engine else { return false }
        if engine.state == .playing { engine.pause() }
        else if engine.state == .paused { suspended = false; engine.play() }
        else { return false }
        report()
        return true
    }

    func seek(to value: Double) async {
        guard canSeek, let engine, value.isFinite else { return }
        ended = false; message = nil
        await engine.seek(to: min(max(0, value), engine.duration))
        if suspended || closing { engine.pause() }
        report()
    }

    func selectAudio(_ id: Int, manual: Bool = true) {
        guard audioSelectable, !changingTrack, let engine else { return }
        if manual {
            manualAudio = true
            if subtitleNote == "预选音轨暂不可用，请在音轨列表中选择。" { subtitleNote = nil }
        }
        changingTrack = true
        let wasPaused = paused
        trackWasPaused = wasPaused
        engine.selectAudioTrack(index: id)
        trackWork = Task { [weak self] in
            defer { self?.changingTrack = false }
            for _ in 0..<150 {
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                guard let self, !self.closing else { return }
                if case .error = engine.state { break }
                if engine.activeAudioTrackIndex == id && (engine.state == .playing || engine.state == .paused) {
                    if wasPaused || self.suspended { engine.pause() }
                    if manual, let track = engine.audioTracks.first(where: { $0.id == id }) { self.rememberTrack("audio", .track(Self.identity(track))) }
                    self.report(); return
                }
            }
            self?.message = "音轨切换未完成，请重试或返回。"
        }
    }

    var canChangeSubtitleStyle: Bool { !closing && subtitleStyle != .null && subtitleStyleWork == nil }
    func loadSubtitleStyle() {
        guard !closing, subtitleStyleWork == nil else { return }
        subtitleStyleWork = Task {
            defer { subtitleStyleWork = nil }
            do { let value = try await api.request("player.getSubStyle"); if !closing { subtitleStyle = value; subtitleStyleError = nil } }
            catch is CancellationError {} catch { if !closing { subtitleStyleError = error.localizedDescription } }
        }
    }
    func setSubtitleStyle(_ key: String, _ value: JSONValue) {
        guard canChangeSubtitleStyle else { return }
        subtitleStyleWork = Task {
            defer { subtitleStyleWork = nil }
            do {
                _ = try await api.request("player.setSubStyle", [key: value])
                let result = try await api.request("player.getSubStyle")
                if !closing { subtitleStyle = result; subtitleStyleError = nil }
            } catch is CancellationError {} catch { if !closing { subtitleStyleError = error.localizedDescription } }
        }
    }

    func selectSubtitle(_ id: Int?, manual: Bool = true) {
        guard !closing, let engine else { return }
        if manual {
            manualSubtitle = true
            if let id { if let track = textTracks.first(where: { $0.id == id }) { rememberTrack("sub", .track(Self.identity(track))) } }
            else { rememberTrack("sub", .off) }
        }
        subtitleText = ""
        if let id, textTracks.contains(where: { $0.id == id }) { engine.selectSubtitleTrack(index: id) }
        else { engine.clearSubtitle() }
    }

    private var trackScope: SessionTrackMemory.Scope? {
        guard let session = api.session, let episode = episodes.first(where: { $0.id == item }), episode.type_ == "Episode",
              let series = episode.series_id, !series.isEmpty else { return nil }
        return .init(server: session.server, user: session.user_id, series: series)
    }
    private static func identity(_ track: TrackInfo) -> SessionTrackMemory.Identity {
        .init(title: track.name, language: track.language ?? "", codec: track.codec, external: track.isExternal)
    }
    private func rememberTrack(_ kind: String, _ choice: SessionTrackMemory.Choice) {
        manualChoices[kind] = choice
        if let scope = trackScope { trackMemory.record(scope, kind: kind, choice) }
    }
    private func candidates(_ tracks: [TrackInfo], selected: Int?) -> [TrackCandidate] {
        tracks.map { TrackCandidate(id: $0.id, title: $0.name, language: $0.language ?? "", codec: $0.codec, external: $0.isExternal, selected: $0.id == selected) }
    }

    // 只沿用本次同剧手选；详情页显式预选和本集手选优先，匹配不唯一时交给核心偏好。
    private func restoreSessionTracks() {
        guard !loading, !closing, let engine else { return }
        let scope = trackScope
        if !manualAudio, !restoredAudio, !changingTrack, initialAudio == nil, audioSelectable,
           case .track(let identity)? = trackMemory.choice(scope, kind: "audio"),
           let id = SessionTrackMemory.match(identity, tracks: candidates(engine.audioTracks, selected: engine.activeAudioTrackIndex)) {
            restoredAudio = true; audioPreferenceWork?.cancel(); appliedAudio = engine.audioTracks
            if id != engine.activeAudioTrackIndex { selectAudio(id, manual: false) }
        }
        if !manualSubtitle, !restoredSubtitle, initialSubtitle == nil, let choice = trackMemory.choice(scope, kind: "sub") {
            switch choice {
            case .off:
                restoredSubtitle = true; subtitlePreferenceWork?.cancel(); appliedSubtitles = textTracks; selectSubtitle(nil, manual: false)
            case .track(let identity):
                guard let id = SessionTrackMemory.match(identity, tracks: candidates(textTracks, selected: engine.activeSubtitleTrackIndex)) else { break }
                restoredSubtitle = true; subtitlePreferenceWork?.cancel(); appliedSubtitles = textTracks; selectSubtitle(id, manual: false)
            }
        }
    }

    private func applyTrackPreferences() {
        guard !loading, !closing, let engine else { return }
        restoreSessionTracks()
        if !manualAudio && !restoredAudio && !changingTrack && !engine.audioTracks.isEmpty && engine.audioTracks != appliedAudio {
            let tracks = engine.audioTracks
            let rows = candidates(tracks, selected: engine.activeAudioTrackIndex)
            audioPreferenceWork?.cancel()
            if let initialAudio, initialAudio.version == actualVersion {
                if let id = TrackPreference.explicit(initialAudio.stream, tracks: rows) {
                    appliedAudio = tracks
                    if subtitleNote == "预选音轨暂不可用，请在音轨列表中选择。" { subtitleNote = nil }
                    if id != engine.activeAudioTrackIndex { selectAudio(id, manual: false) }
                } else {
                    // 保留明确预选，等待迟到轨道；不猜测同语言的其它音轨。
                    subtitleNote = "预选音轨暂不可用，请在音轨列表中选择。"
                }
            } else {
                appliedAudio = tracks
                audioPreferenceWork = Task {
                    do {
                        let chosen = try await api.trackPreference(rows, kind: "audio", prefs: trackPrefs)
                        guard !Task.isCancelled, !closing, !manualAudio, !restoredAudio, engine.audioTracks == tracks else { return }
                        if let id = Int(chosen), id != engine.activeAudioTrackIndex { selectAudio(id, manual: false) }
                    } catch is CancellationError {} catch { if !closing { subtitleNote = "音轨偏好读取失败，请手动选择。" } }
                }
            }
        }
        if !manualSubtitle && !restoredSubtitle && !textTracks.isEmpty && textTracks != appliedSubtitles {
            let tracks = textTracks
            if let initialSubtitle, initialSubtitle.version == actualVersion {
                if let stream = initialSubtitle.stream {
                    if let id = TrackPreference.explicit(stream, tracks: candidates(tracks, selected: engine.activeSubtitleTrackIndex)) {
                        appliedSubtitles = tracks; selectSubtitle(id, manual: false)
                    }
                } else { appliedSubtitles = tracks; selectSubtitle(nil, manual: false) }
            } else {
                appliedSubtitles = tracks
                let rows = candidates(tracks, selected: engine.activeSubtitleTrackIndex)
                subtitlePreferenceWork?.cancel()
                subtitlePreferenceWork = Task {
                    do {
                        let chosen = try await api.trackPreference(rows, kind: "sub", prefs: trackPrefs)
                        guard !Task.isCancelled, !closing, !manualSubtitle, !restoredSubtitle, textTracks == tracks else { return }
                        if chosen == "no" { selectSubtitle(nil, manual: false) }
                        else if let id = Int(chosen) { selectSubtitle(id, manual: false) }
                    } catch is CancellationError {} catch { if !closing { subtitleNote = "字幕偏好读取失败，请手动选择。" } }
                }
            }
        }
    }

    private func refreshSubtitles(at time: Double? = nil) {
        guard !closing, let engine else { subtitleText = ""; return }
        let now = time ?? engine.sourceTime
        let text = engine.subtitleCues.filter { $0.startTime <= now && now < $0.endTime }.compactMap(\.text).joined(separator: "\n")
        if subtitleText != text { subtitleText = text }
    }

    func suspend() {
        suspended = true
        engine?.pause()
        report()
    }

    private func report() {
        guard resolved, !closing, reportWork == nil else { return }
        let pos = engine?.currentTime ?? position, paused = paused
        guard pos.isFinite, pos >= 0 else { return }
        reportWork = Task { [weak self] in
            guard let self else { return }
            defer { self.reportWork = nil }
            do {
                let value = try await self.api.request("emby.reportProgress", ["item_id": .string(self.item), "pos": .number(pos), "paused": .bool(paused)])
                if value["reported"].bool != true { self.syncWarning = "播放进度暂未同步到服务器。" }
            } catch { if !(error is CancellationError) { self.syncWarning = "播放进度暂未同步到服务器。" } }
        }
    }

    func close() async {
        if let closeWork { await closeWork.value; return }
        closing = true
        audioPreferenceWork?.cancel(); subtitlePreferenceWork?.cancel()
        let lastPosition = engine?.currentTime ?? position
        engine?.pause(); ticker?.cancel(); trackWork?.cancel(); queueWork?.cancel(); versionWork?.cancel(); skipWork?.cancel(); skipSeekWork?.cancel()
        // 核心起播先收齐结果再停；引擎加载则有公开取消处理，可直接中断网络探测。
        if loadingEngine { startWork?.cancel() }
        reportWork?.cancel()
        closeWork = Task {
            await startWork?.value
            await speedWork?.value
            await markerWork?.value
            await subtitleStyleWork?.value
            await danmaku.close()
            let pos = max(lastPosition, engine?.currentTime ?? 0)
            engine?.stop(); subtitleText = ""
            await reportWork?.value
            if resolved {
                do {
                    let result = try await withThrowingTaskGroup(of: JSONValue.self) { group in
                        group.addTask { @MainActor in
                            try await self.api.request("player.stopPlayback", ["item_id": .string(self.item), "pos": .number(pos)])
                        }
                        group.addTask {
                            try await Task.sleep(for: .seconds(8))
                            throw CoreFailure(code: "E_NETWORK")
                        }
                        defer { group.cancelAll() }
                        return try await group.next()!
                    }
                    if result["reported"].bool != true { syncWarning = "已停止播放，但服务器未确认保存进度。" }
                } catch { syncWarning = "已停止播放，但进度同步失败。" }
            }
            subscriptions.removeAll()
            engine = nil
        }
        await closeWork?.value
    }
}
