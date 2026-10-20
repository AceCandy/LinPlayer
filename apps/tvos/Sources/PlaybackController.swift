import AetherEngine
import Combine
import SwiftUI

@MainActor final class PlaybackController: ObservableObject {
    let api: MediaAPI
    let item: String
    let title: String
    let version: String?
    let fromStart: Bool
    @Published private(set) var engine: AetherEngine?
    @Published var message: String?
    @Published var syncWarning: String?
    @Published var loading = true
    @Published var closing = false
    @Published var changingTrack = false
    @Published var position: Double = 0
    @Published var subtitleText = ""
    @Published var subtitleNote: String?
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
    private var skipWork: Task<Void, Never>?
    private var skipSeekWork: Task<Void, Never>?
    private var skippedEnds = Set<Double>()
    private var queueWork: Task<Void, Never>?
    private var versionWork: Task<Void, Never>?
    private let initialSubtitle: InitialSubtitle?
    @Published private(set) var actualVersion = ""
    private var trackPrefs: JSONValue = .null
    private var manualAudio = false
    private var manualSubtitle = false
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
    private var suspended = false
    private var resolved = false
    private var loadingEngine = false
    private var trackWasPaused = false

    init(api: MediaAPI, item: String, title: String, version: String?, fromStart: Bool, subtitle: InitialSubtitle? = nil) {
        initialSubtitle = subtitle
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
                    if case .error = state { self.message = "播放失败，请重试或返回。" }
                    if state == .ended {
                        let duration = self.engine?.duration ?? 0
                        let position = max(self.position, self.engine?.currentTime ?? 0)
                        if duration.isFinite && duration > 0 && position > 0 && position >= duration - 5 {
                            self.ended = true; self.message = "播放结束"
                        } else { self.message = "播放提前中断，请重试。" }
                        self.report()
                    }
                }
            }.store(in: &subscriptions)
        } catch { message = "播放器初始化失败，请返回后重试。"; loading = false }
    }

    var paused: Bool { engine?.state != .playing }
    var canSeek: Bool { engine?.canSeek == true && !changingTrack && !closing }
    var audioSelectable: Bool {
        guard !loading, !closing, let engine else { return false }
        return engine.audioTracks.count > 1 && engine.videoRoute != .remoteBypass && engine.canSeek
    }
    var textTracks: [TrackInfo] {
        (engine?.subtitleTracks ?? []).filter { Self.plainCodec($0.codec) && !$0.isNativelyRenderedSubtitle }
    }
    var unsupportedSubtitleCount: Int { (engine?.subtitleTracks.count ?? 0) - textTracks.count }
    private static func plainCodec(_ codec: String) -> Bool { ["subrip", "srt", "webvtt", "mov_text", "text"].contains(codec.lowercased()) }

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
            let result = try await api.play(item: item, version: version, fromStart: fromStart)
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
                default: hint = ["srt", "vtt"].contains(url.pathExtension.lowercased()) ? url.pathExtension.lowercased() : nil
                }
                guard let hint else { unsupported += 1; return nil }
                // 核心地址自带资源鉴权；禁止把主服 token 额外加到跨源字幕请求。
                return ExternalSubtitleTrack(url: url, name: sub.title, language: sub.lang, isDefault: false, httpHeaders: [:], formatHint: hint)
            }
            if unsupported > 0 { subtitleNote = "部分样式或位图字幕暂不支持。" }
            let options = LoadOptions(httpHeaders: [:], externalSubtitles: external, autoplay: false)
            loadingEngine = true
            defer { loadingEngine = false }
            _ = try await engine.load(url: url, startPosition: result.resume_secs, options: options)
            try Task.checkCancellation()
            engine.clearSubtitle()
            let preferredSpeed = Float(prefs["default_speed"].number ?? 1)
            speed = min(max(preferredSpeed, 0.25), engine.maxSupportedRate)
            engine.setRate(speed)
            loading = false
            applyTrackPreferences()
            loadSkip()
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
            loading = false
            message = (error as? CoreFailure)?.errorDescription ?? "无法开始播放，请检查网络后重试。"
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
        guard skipWork == nil, !loading, !closing, let duration = engine?.duration, duration.isFinite, duration > 0 else { return }
        skipWork = Task {
            do {
                let info = try await api.request("player.chapterInfo", ["item_id": .string(item), "runtime_secs": .number(duration)]).decode(PlaybackSkip.self)
                guard !closing, !Task.isCancelled else { return }
                skipInfo = info; skipError = nil; updateSkip()
            } catch is CancellationError {} catch { if !closing { skipError = "片头片尾信息加载失败，可重试。" } }
        }
    }

    func retrySkip() { skipWork?.cancel(); skipWork = nil; loadSkip() }

    private func updateSkip() {
        if skipWork == nil { loadSkip() }
        guard !suspended, !paused, skipInfo?.skip_auto == true,
              let range = activeSkip, !skippedEnds.contains(range.end) else { return }
        skip(range)
    }

    func skip(_ range: PlaybackSkip.Range) {
        guard canSeek, skipSeekWork == nil else { return }
        skippedEnds.insert(range.end)
        skipSeekWork = Task {
            await seek(to: range.end)
            skipSeekWork = nil
        }
    }

    func setSpeed(_ value: Float) {
        guard !closing, !loading, let engine, value > 0, value <= engine.maxSupportedRate else { return }
        speed = value; engine.setRate(value)
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

    func toggle() {
        guard !loading, !closing, !changingTrack, let engine else { return }
        if engine.state == .playing { engine.pause() }
        else if engine.state == .paused { suspended = false; engine.play() }
        report()
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
        if manual { manualAudio = true }
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
                    self.report(); return
                }
            }
            self?.message = "音轨切换未完成，请重试或返回。"
        }
    }

    func selectSubtitle(_ id: Int?, manual: Bool = true) {
        guard !closing, let engine else { return }
        if manual { manualSubtitle = true }
        subtitleText = ""
        if let id, textTracks.contains(where: { $0.id == id }) { engine.selectSubtitleTrack(index: id) }
        else { engine.clearSubtitle() }
    }

    private func applyTrackPreferences() {
        guard !loading, !closing, let engine else { return }
        func candidates(_ tracks: [TrackInfo], selected: Int?) -> [TrackCandidate] {
            tracks.map { TrackCandidate(id: $0.id, title: $0.name, language: $0.language ?? "", codec: $0.codec, external: $0.isExternal, selected: $0.id == selected) }
        }
        if !manualAudio && !changingTrack && !engine.audioTracks.isEmpty && engine.audioTracks != appliedAudio {
            let tracks = engine.audioTracks
            appliedAudio = tracks
            let rows = candidates(tracks, selected: engine.activeAudioTrackIndex)
            audioPreferenceWork?.cancel()
            audioPreferenceWork = Task {
                do {
                    let chosen = try await api.trackPreference(rows, kind: "audio", prefs: trackPrefs)
                    guard !Task.isCancelled, !closing, !manualAudio, engine.audioTracks == tracks else { return }
                    if let id = Int(chosen), id != engine.activeAudioTrackIndex { selectAudio(id, manual: false) }
                } catch is CancellationError {} catch { if !closing { subtitleNote = "音轨偏好读取失败，请手动选择。" } }
            }
        }
        if !manualSubtitle && !textTracks.isEmpty && textTracks != appliedSubtitles {
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
                        guard !Task.isCancelled, !closing, !manualSubtitle, textTracks == tracks else { return }
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
        let cues = engine.subtitleCues.filter { $0.startTime <= now && now < $0.endTime }
        var lines: [String] = []
        for cue in cues {
            guard case .text(let text) = cue.body else {
                engine.clearSubtitle(); subtitleText = ""; subtitleNote = "这条字幕包含样式或位图，暂不支持。"; return
            }
            lines.append(text)
        }
        let text = lines.joined(separator: "\n")
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
