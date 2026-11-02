import SwiftUI
import Foundation
#if !UI_RENDER_CHECK
import AetherEngine

struct VideoSurface: UIViewRepresentable {
    let engine: AetherEngine
    func makeUIView(context: Context) -> AetherPlayerView {
        let view = AetherPlayerView(frame: .zero)
        engine.bind(view: view)
        return view
    }
    func updateUIView(_ uiView: AetherPlayerView, context: Context) {}
}

#endif

struct PlayerView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var controller: PlaybackController
    @State private var controls = true
    @State private var controlHeight: CGFloat = 270
    @State private var panel: String?
    @State private var panelParent: String?
    @State private var panelReturnFocus: String?
    @State private var advancedMarkers = false
    @State private var preview: Double?
    @State private var interaction = 0
    @State private var flash: Bool?
    @AppStorage("tvSeekStep") private var seekStep = 10
    @State private var seekCommit = 0
    @State private var seekRepeat = 0
    @State private var lastSeekMove = Date.distantPast
    @AppStorage("tvHoldSpeed") private var holdSpeed = 3
    @State private var holdTask: Task<Void, Never>?
    @State private var holdUsed = false
    @FocusState private var focus: String?

    private var episodeLabel: String? {
        guard let episode = controller.episodes.first(where: { $0.id == controller.item }),
              let season = episode.season_no, let number = episode.episode_no else { return nil }
        return String(format: "S%02dE%02d · %@", season, number, episode.name)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            if let engine = controller.engine { VideoSurface(engine: engine).id(ObjectIdentifier(engine)).ignoresSafeArea() }
#if UI_RENDER_CHECK
            if !controller.subtitleText.isEmpty { Text(controller.subtitleText).padding(.bottom, controls ? controlHeight + 24 : 60) }
#else
            if let engine = controller.engine, !controller.ended {
                SubtitleSurface(engine: engine, bottomInset: controls ? controlHeight + 24 : 60, style: controller.subtitleStyle).id(ObjectIdentifier(engine)).ignoresSafeArea().allowsHitTesting(false)
            }
#endif
            TVDanmakuOverlay(session: controller.danmaku, position: { controller.engine?.currentTime ?? controller.position }, duration: controller.engine?.duration ?? 0, paused: controller.paused)
                .ignoresSafeArea()
            if !controls && controller.failure == nil {
                // 确认键按住 600ms 为临时倍速，松开恢复；短按在松手时才切换暂停，与 Android TV 一致。
                Button { if holdUsed { holdUsed = false } else { togglePlayback() } } label: { Color.clear.contentShape(Rectangle()) }
                    .buttonStyle(TVPressStyle { holdPressing($0) }).focusEffectDisabled().focused($focus, equals: "surface")
                    .onMoveCommand { _ in showControls() }
                    .onDisappear { holdPressing(false) }
            }
            if controller.failure == nil && (controller.loading || controller.engine?.isBuffering == true) { TVLoading("正在缓冲").frame(maxHeight: .infinity) }
            if let failure = controller.failure, !controller.ended {
                failurePage(failure)
            } else if controls {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer()
                    nextEpisodeCard
                    VStack(alignment: .leading, spacing: 22) {
                        manualSkipButton
                        if let error = controller.skipError { RetryMessage(text: error) { controller.retrySkip(); touch() } }
                        if !controller.ended, let error = controller.queueError {
                            RetryMessage(text: error) { controller.loadQueue(); touch() }
                        }
                        if let message = controller.message { Text(message).foregroundStyle(.orange) }
                        if let note = controller.subtitleNote { Text(note).font(.caption).foregroundStyle(.secondary) }
                        if let warning = controller.syncWarning { Text(warning).font(.caption).foregroundStyle(.orange) }
                        transportControls
                        progressControl
                    }.padding(.horizontal, 64).padding(.bottom, 40).padding(.top, 28)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.92)], startPoint: .top, endPoint: .bottom))
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlHeight = $0 }
                }.disabled(panel != nil || model.switchingPlayback || controller.closing)
            } else if panel == nil {
                VStack {
                    nextEpisodeCard
                    manualSkipButton.padding(.trailing, 64).padding(.bottom, 110).frame(maxWidth: .infinity, alignment: .trailing)
                    GeometryReader { geometry in
                        Rectangle().fill(.white.opacity(0.6)).frame(width: geometry.size.width * min(1, max(0, controller.position / max(1, controller.engine?.duration ?? 0))))
                    }.frame(height: 2).allowsHitTesting(false)
                }
            }
            if let flash {
                Image(systemName: flash ? "play.fill" : "pause.fill").font(.system(size: 44))
                    .frame(width: 120, height: 120).background(.black.opacity(0.6), in: Circle())
                    .allowsHitTesting(false).task(id: flash) {
                        do { try await Task.sleep(for: .milliseconds(600)) } catch { return }
                        self.flash = nil
                    }
            }
            if let rate = controller.holdRate {
                Label(String(format: "%.1f×", rate), systemImage: "forward.fill").font(.system(size: 30, weight: .bold))
                    .padding(.horizontal, 28).padding(.vertical, 12).background(.black.opacity(0.6), in: Capsule())
                    .frame(maxHeight: .infinity, alignment: .top).padding(.top, 48).allowsHitTesting(false)
            }
            VStack { HStack { Spacer(); TimelineView(.periodic(from: .now, by: 30)) { context in
                Text(context.date, style: .time).font(.system(size: 24)).monospacedDigit().shadow(color: .black, radius: 3)
            }.padding(.trailing, 64).padding(.top, 32) }; Spacer() }.allowsHitTesting(false)

        }
        .ignoresSafeArea(.container)
        .preferredColorScheme(.dark)
        .foregroundStyle(.white)
        .tint(TVPalette.accent(false, palette: model.colorScheme))
        .interactiveDismissDisabled()
        .onPlayPauseCommand { togglePlayback() }
        .tvExitCommand {
            if controller.failure != nil && !controller.ended { Task { await model.closePlayer() } }
            else if panel != nil { closePanel() }
            else if preview != nil { preview = nil }
            else if controls { controls = false; focus = "surface" }
            else { Task { await model.closePlayer() } }
        }
        .onMoveCommand { _ in touch() }
        .onChange(of: focus) { _, next in if next != "progress" { preview = nil }; touch() }
        .onChange(of: controller.message) { _, next in if next != nil { showControls() } else { touch() } }
        .onChange(of: controller.failure) { _, next in
            if next != nil { panel = nil; panelParent = nil; preview = nil; focus = "retry" }
        }
        .onAppear {
            model.remotePlayback = { key in
                if controller.failure != nil && !controller.ended { if key == "ok" { retryPlayback() }; return true }
                if ["up", "down", "left", "right"].contains(key), !controls { showControls(); return true }
                if key == "ok", !controls { togglePlayback(); return true }
                if key == "menu" { openPanel("more"); return true }
                if panel == nil, focus == "progress", ["left", "right"].contains(key), controller.canSeek {
                    movePreview(forward: key == "right"); return true
                }
                return false
            }
        }
        .onDisappear { if model.playback === controller { model.remotePlayback = nil } }
        .task { await controller.start(); focus = "progress" }
        .task(id: "\(controller.ended):\(controller.queueLoading):\(controller.nextEpisode?.id ?? ""):\(model.sceneActive):\(panel ?? "")") {
            guard controller.ended, !controller.queueLoading, controller.queueError == nil,
                  !controller.closing, model.playback === controller, model.sceneActive, panel == nil else { return }
            if let next = controller.nextEpisode { switchEpisode(next) }
            else { await model.closePlayer() }
        }
        // 与 Android TV 一致：最后一次方向键后 700ms 自动提交预览位置。
        .task(id: seekCommit) {
            guard seekCommit > 0 else { return }
            do { try await Task.sleep(for: .milliseconds(700)) } catch { return }
            guard let value = preview else { return }
            preview = nil
            await controller.seek(to: value)
        }
        // 暂停时保持控制栏，恢复播放后重新计时。
        .task(id: "\(interaction):\(controller.paused)") {
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            if panel == nil && preview == nil && !controller.paused && !controller.loading && controller.engine?.isBuffering != true && !controller.changingTrack && controller.message == nil && !controller.closing {
                controls = false; focus = "surface"
            }
        }
        .overlay(alignment: .trailing) {
            if panel != nil {
                trackPanel.frame(width: 620).frame(maxHeight: .infinity).background(.black.opacity(0.96))
                    .task(id: panel) { await Task.yield(); panelFocus = panelReturnFocus ?? selectedPanelKey; panelReturnFocus = nil }
            }
        }

    }

    @State private var lastPanel = "subtitles"
    private func showControls() { controls = true; focus = "progress"; touch() }
    private func touch() { interaction += 1 }
    private func movePreview(forward: Bool) {
        guard let duration = controller.engine?.duration, duration > 0 else { return }
        let now = Date()
        // shortcut: tvOS 方向事件不带重复计数，间隔小于 0.2 秒视为按住；真机重复间隔不同需调整阈值。
        seekRepeat = now.timeIntervalSince(lastSeekMove) < 0.2 ? seekRepeat + 1 : 0
        lastSeekMove = now
        let step = seekRepeat < 3 ? Double(seekStep) : seekRepeat < 10 ? 30 : seekRepeat < 20 ? 60 : 300
        preview = min(max(0, (preview ?? controller.position) + (forward ? step : -step)), duration)
        seekCommit += 1; touch()
    }
    private func holdPressing(_ pressing: Bool) {
        holdTask?.cancel(); holdTask = nil
        guard pressing else { controller.endHoldSpeed(); return }
        holdUsed = false
        holdTask = Task {
            do { try await Task.sleep(for: .milliseconds(600)) } catch { return }
            holdUsed = true
            controller.beginHoldSpeed(Float(holdSpeed))
        }
    }
    private func togglePlayback() {
        let wasPaused = controller.paused
        if controller.toggle() { flash = wasPaused }
        touch()
    }
    private func retryPlayback() {
        Task { await model.switchPlayer(item: controller.item, title: controller.title, version: controller.version, fromStart: controller.fromStart) }
    }
    private func failurePage(_ reason: String) -> some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 72)).foregroundStyle(.orange)
            Text("这一片没能播起来").font(.title2)
            if controller.autoRetried { Text("已自动重试 1 次仍然失败").foregroundStyle(.secondary) }
            Text(reason).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("重试") { retryPlayback() }.buttonStyle(TVActionStyle()).focusEffectDisabled()
                .disabled(model.switchingPlayback || controller.closing).focused($focus, equals: "retry")
        }.padding(64).frame(maxWidth: .infinity, maxHeight: .infinity).background(.black.opacity(0.92))
        .onAppear { focus = "retry" }
    }

    @FocusState private var panelFocus: String?
    private func closePanel() {
        if let parent = panelParent { panelReturnFocus = panel; panel = parent; panelParent = nil }
        else { panel = nil; focus = lastPanel }
        touch()
    }
    private func openPanel(_ key: String) {
        if panel == "more" { panelParent = "more" } else { lastPanel = key; panelParent = nil }
        panel = key
        if key == "markers" { controller.loadMarkers() }
        if key == "more" { controller.loadSkipPrefs() }
        touch()
    }
    private var nextUp: Bool {
        controller.ended || PlaybackSkip.nextUp(at: controller.position, duration: controller.engine?.duration ?? 0, outro: controller.skipInfo?.outro)
    }
    private var selectedPanelKey: String? {
        switch panel {
        case "audio": return controller.engine?.activeAudioTrackIndex.map { "audio:\($0)" }
        case "subtitles": return "sub:\(controller.engine?.activeSubtitleTrackIndex ?? -1)"
        case "speed": return "speed:\(controller.speed)"
        case "picture": return "picture:\(controller.pictureMode)"
        case "versions": return "version:\(controller.actualVersion)"
        case "chapters": return controller.engine?.mediaChapters.last(where: { $0.startSeconds <= controller.position }).map { "chapter:\($0.id)" }
        default: return nil
        }
    }
    @ViewBuilder private var manualSkipButton: some View {
        if controller.skipInfo?.skip_auto == false, let range = controller.activeSkip {
            Button(controller.skipInfo?.intro == range ? "跳过片头" : "跳过片尾") {
                controller.skip(range); touch()
            }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
                .disabled(!controller.canSeek || model.switchingPlayback).focused($focus, equals: "skip")
        }
    }
    @ViewBuilder private var nextEpisodeCard: some View {
        if nextUp, controller.nextEpisode != nil || controller.ended {
            HStack {
                Spacer()
                VStack(alignment: .leading, spacing: 16) {
                    if controller.queueLoading { TVLoading("正在准备下一集") }
                    else if let error = controller.queueError { RetryMessage(text: error) { controller.loadQueue() } }
                    else if let next = controller.nextEpisode {
                        Button { switchEpisode(next) } label: {
                            HStack(spacing: 20) {
                                MediaArtwork(item: next, loader: model.images, width: 240, height: 135)
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("下一集").foregroundStyle(.secondary)
                                    Text(next.title).lineLimit(2)
                                }
                            }
                        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                    } else { Text("本季已播放完毕").foregroundStyle(.secondary) }
                }.frame(width: 650).padding(24).background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 18))
            }.padding(.horizontal, 64).padding(.bottom, 18)
        }
    }
    @ViewBuilder private var progressControl: some View {
        if controller.canSeek, let duration = controller.engine?.duration, duration > 0 {
            Button {
                if let value = preview { preview = nil; Task { await controller.seek(to: value) } }
                else { togglePlayback() }
            } label: {
                VStack(spacing: 16) {
                    if let preview, controller.skipInfo?.thumbs == true,
                       let chapter = controller.skipInfo?.chapters?.filter({ $0.start_secs <= preview }).max(by: { $0.start_secs < $1.start_secs }) {
                        HStack(spacing: 20) {
                            if chapter.image_url != nil { RemoteArtwork(url: chapter.image_url, loader: model.images, width: 240, height: 135) }
                            VStack(alignment: .leading, spacing: 10) {
                                Text(time(preview)).monospacedDigit()
                                Text(chapter.name).font(.system(size: 22)).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                    }
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            TVDanmakuHeatmap(session: controller.danmaku).frame(height: 28).offset(y: -20)
                            Capsule().fill(.white.opacity(0.25))
                            Capsule().fill(.white.opacity(0.45)).frame(width: geometry.size.width * min(max(0, controller.bufferedPosition / duration), 1))
                            if let info = controller.skipInfo {
                                ForEach([info.intro, info.outro].compactMap { $0 }.filter { $0.start >= 0 && $0.end <= duration && $0.end > $0.start }, id: \.start) { range in
                                    Rectangle().fill(.yellow.opacity(0.4)).frame(width: geometry.size.width * (range.end - range.start) / duration)
                                        .offset(x: geometry.size.width * range.start / duration)
                                }
                            }
                            Capsule().fill(TVPalette.accent(false, palette: model.colorScheme)).frame(width: geometry.size.width * min(max(0, (preview ?? controller.position) / duration), 1))
                            ForEach(controller.engine?.mediaChapters ?? []) { chapter in
                                if chapter.startSeconds > 0 && chapter.startSeconds < duration {
                                    Rectangle().fill(.white.opacity(0.8)).frame(width: 2).offset(x: geometry.size.width * chapter.startSeconds / duration)
                                }
                            }
                        }
                    }.frame(height: focus == "progress" ? 10 : 6)
                    HStack {
                        Text(time(preview ?? controller.position))
                        if preview != nil { Text("确认跳转，返回取消").foregroundStyle(.secondary) }
                        Spacer()
                        Text("剩余 " + time(max(0, duration - (preview ?? controller.position)))).foregroundStyle(.secondary)
                        Text(time(duration))
                    }.font(.system(size: 22)).monospacedDigit()
                }.padding(.vertical, 12)
            }.buttonStyle(TVCardStyle()).focusEffectDisabled().focused($focus, equals: "progress")
                .onMoveCommand { direction in
                    guard focus == "progress" else { return }
                    if direction == .left || direction == .right { movePreview(forward: direction == .right) }
                }
        } else if !controller.loading { Text("当前内容暂不支持定位").font(.caption).foregroundStyle(.secondary) }
    }
    private var transportControls: some View {
        HStack(alignment: .firstTextBaseline, spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                if let episodeLabel { Text(episodeLabel).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1) }
                Text(controller.title).font(.system(size: 30, weight: .semibold)).lineLimit(1)
            }
            Spacer(minLength: 24)
            if let next = controller.nextEpisode, !nextUp {
                Button { switchEpisode(next) } label: { Image(systemName: "forward.end.fill") }.accessibilityLabel("下一集")
            }
            Button { openPanel("speed") } label: { Image(systemName: "speedometer") }
                .accessibilityLabel("倍速").disabled(!controller.canSetSpeed).focused($focus, equals: "speed")
            Button { openPanel("danmaku") } label: { Image(systemName: "text.bubble") }
                .accessibilityLabel("弹幕").focused($focus, equals: "danmaku")
            if controller.audioSelectable {
                Button { openPanel("audio") } label: { Image(systemName: "waveform") }
                    .accessibilityLabel("音轨").disabled(controller.changingTrack).focused($focus, equals: "audio")
            }
            Button { openPanel("subtitles") } label: { Image(systemName: "captions.bubble") }
                .accessibilityLabel("字幕").focused($focus, equals: "subtitles")
            Button { openPanel("more") } label: { Image(systemName: "ellipsis") }
                .accessibilityLabel("更多").focused($focus, equals: "more")
        }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
    }

    private var trackPanel: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text(panelTitle).font(.title)
                if panel == "danmaku" {
                    TVDanmakuPanel(session: controller.danmaku)
                } else if panel == "more" {
                    Button("画面比例") { openPanel("picture") }.focused($panelFocus, equals: "picture")
                    if controller.versions.count > 1 || controller.versionError != nil {
                        Button("播放版本") { openPanel("versions") }.focused($panelFocus, equals: "versions")
                    }
                    if let previous = controller.previousEpisode { Button("上一集") { switchEpisode(previous) } }
                    Button("片头片尾标记") { openPanel("markers") }.focused($panelFocus, equals: "markers")
                    if let enabled = controller.skipIntroEnabled {
                        Toggle("自动跳过片头", isOn: Binding(get: { enabled }, set: { controller.setSkipPref("skip_intro", $0) })).disabled(controller.markerBusy)
                    }
                    if let enabled = controller.skipOutroEnabled {
                        Toggle("自动跳过片尾", isOn: Binding(get: { enabled }, set: { controller.setSkipPref("skip_outro", $0) })).disabled(controller.markerBusy)
                    }
                    if let error = controller.markerError { Text(error).foregroundStyle(.orange) }
                    if controller.engine?.mediaChapters.isEmpty == false {
                        Button("章节") { openPanel("chapters") }.focused($panelFocus, equals: "chapters")
                    }
                    Button("媒体信息") { openPanel("info") }.focused($panelFocus, equals: "info")
                    if controller.message != nil && !controller.ended {
                        Button("重试播放") { retryPlayback() }
                    }
                    Button(controller.closing ? "正在保存…" : "退出播放") { Task { await model.closePlayer() } }.disabled(controller.closing)
                } else if panel == "markers" {
                    markerControls
                } else if panel == "info" {
                    Text("播放内核：AetherEngine")
                    if let source = controller.versions.first(where: { $0.id == controller.actualVersion }) {
                        Text(source.displayName)
                        if let path = source.displayPath { Text(path).font(.system(size: 20)).foregroundStyle(.secondary) }
                        if let size = source.size_bytes { Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) }
                        ForEach(source.streams ?? []) { stream in
                            VStack(alignment: .leading, spacing: 8) { Text(stream.label); Text(stream.summary).foregroundStyle(.secondary) }
                        }
                    }
                } else if panel == "speed", let engine = controller.engine {
                    ForEach([Float(0.25), 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 3.5, 4].filter { $0 <= engine.maxSupportedRate }, id: \.self) { rate in
                        Button((controller.speed == rate ? "✓ " : "") + "\(rate)×") {
                            if controller.setSpeed(rate) { closePanel() }
                        }.disabled(!controller.canSetSpeed).focused($panelFocus, equals: "speed:\(rate)")
                    }
                } else if panel == "picture" {
                    Button((controller.pictureMode == "fit" ? "✓ " : "") + "适应画面") { controller.setPictureMode("fit"); closePanel() }.focused($panelFocus, equals: "picture:fit")
                    Button((controller.pictureMode == "fill" ? "✓ " : "") + "填满画面") { controller.setPictureMode("fill"); closePanel() }.focused($panelFocus, equals: "picture:fill")
                    Button((controller.pictureMode == "stretch" ? "✓ " : "") + "拉伸") { controller.setPictureMode("stretch"); closePanel() }.focused($panelFocus, equals: "picture:stretch")
                } else if panel == "chapters", let engine = controller.engine {
                    ForEach(engine.mediaChapters) { chapter in
                        Button(chapter.name + " · " + time(chapter.startSeconds)) {
                            Task { await controller.seek(to: chapter.startSeconds) }; closePanel()
                        }.focused($panelFocus, equals: "chapter:\(chapter.id)")
                    }
                } else if panel == "versions" {
                    if let error = controller.versionError { RetryMessage(text: error) { controller.loadVersions() } }
                    ForEach(controller.versions) { version in
                        Button((version.id == controller.actualVersion ? "✓ " : "") + version.displayName) {
                            closePanel()
                            Task { await model.switchPlayer(item: controller.item, title: controller.title, version: version.id) }
                        }.focused($panelFocus, equals: "version:\(version.id)").disabled(model.switchingPlayback)
                    }
                } else if panel == "audio", let engine = controller.engine {
                    ForEach(engine.audioTracks) { track in
                        Button { lastPanel = "audio"; controller.selectAudio(track.id); closePanel() } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                Text((engine.activeAudioTrackIndex == track.id ? "✓ " : "") + track.name)
                                Text([track.language, Optional(track.codec.uppercased()), track.isExternal ? "外挂" : nil].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.system(size: 20)).foregroundStyle(.secondary)
                            }
                        }.focused($panelFocus, equals: "audio:\(track.id)")
                    }
                } else if panel == "subtitles" {
                    if let error = controller.subtitleStyleError { RetryMessage(text: error) { controller.loadSubtitleStyle() } }
                    VStack(alignment: .leading, spacing: 20) {
                        TVChoice(title: "字号", selection: subtitleNumber("scale", fallback: 1), options: [0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4].map { ($0, "\($0)×") })
                        TVChoice(title: "位置", selection: subtitleNumber("position", fallback: 100), options: [0.0, 25, 50, 75, 100].map { ($0, String(Int($0))) })
                        TVChoice(title: "描边", selection: subtitleNumber("border_size", fallback: 3), options: [0.0, 1, 2, 3, 4, 6, 8, 10].map { ($0, String(Int($0))) })
                        Toggle("粗体", isOn: Binding(get: { controller.subtitleStyle["bold"].bool == true }, set: { controller.setSubtitleStyle("bold", .bool($0)) }))
                        Text("ASS 保留自身描边和字体样式；位图字幕不改变字号。").font(.system(size: 20)).foregroundStyle(.secondary)
                    }.disabled(!controller.canChangeSubtitleStyle)
                    Button((controller.engine?.activeSubtitleTrackIndex == nil ? "✓ " : "") + "关闭字幕") {
                        lastPanel = "subtitles"; controller.selectSubtitle(nil); closePanel()
                    }.focused($panelFocus, equals: "sub:-1")
                    ForEach(controller.textTracks) { track in
                        Button { lastPanel = "subtitles"; controller.selectSubtitle(track.id); closePanel() } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                Text((controller.engine?.activeSubtitleTrackIndex == track.id ? "✓ " : "") + track.name)
                                Text([track.language, Optional(track.codec.uppercased()), track.isExternal ? "外挂" : nil].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.system(size: 20)).foregroundStyle(.secondary)
                            }
                        }.focused($panelFocus, equals: "sub:\(track.id)")
                    }
                    if controller.textTracks.isEmpty { Text("无可用字幕").foregroundStyle(.secondary) }
                    if controller.unsupportedSubtitleCount > 0 { Text("部分外挂字幕无法识别。").foregroundStyle(.secondary) }
                }
                Button("返回") { closePanel() }
            }.padding(40).buttonStyle(TVRowStyle()).toggleStyle(TVToggleStyle()).focusEffectDisabled()
        }
        .tvExitCommand { closePanel() }
    }

    private var panelTitle: String {
        switch panel {
        case "audio": return "音轨"
        case "speed": return "倍速"
        case "picture": return "画面比例"
        case "chapters": return "章节"
        case "versions": return "播放版本"
        case "more": return "更多"
        case "danmaku": return "弹幕"
        case "markers": return "片头片尾标记"
        case "info": return "媒体信息"
        default: return "字幕"
        }
    }

    @ViewBuilder private var markerControls: some View {
        if let markers = controller.markers {
            Text("当前位置 · " + time(controller.position)).monospacedDigit()
            if markers.can_season {
                TVChoice(title: "应用范围", selection: Binding(get: { markers.scope }, set: { controller.loadMarkers(scope: $0) }), options: [("episode", "本集"), ("season", "本季")])
                    .disabled(controller.markerBusy)
            }
            Text("片头：" + markerLabel(markers.intro)).foregroundStyle(.secondary)
            Text("片尾：" + markerLabel(markers.outro)).foregroundStyle(.secondary)
            Group {
                Button("标记片头结束") { controller.mark("intro_end") }
                Button("标记片尾开始") { controller.mark("outro_start") }
                Toggle("自定义区间", isOn: $advancedMarkers)
                if advancedMarkers {
                    Button("标记片头开始") { controller.mark("intro_start") }.disabled(markers.intro == nil)
                    Button("标记片尾结束") { controller.mark("outro_end") }.disabled(markers.outro == nil)
                }
                Button("清除当前范围的标记") { controller.mark("clear") }
            }.disabled(controller.markerBusy || !controller.canSeek)
        }
        if controller.markerBusy { TVLoading("正在处理标记") }
        if let error = controller.markerError { RetryMessage(text: error) { controller.loadMarkers() } }
    }
    private func subtitleNumber(_ key: String, fallback: Double) -> Binding<Double> {
        Binding(get: {
            let value = controller.subtitleStyle[key].number ?? -1
            return value < 0 || key == "scale" && value == 0 ? fallback : value
        }, set: { controller.setSubtitleStyle(key, .number($0)) })
    }

    private func markerLabel(_ range: PlaybackSkip.Range?) -> String {
        range.map { time($0.start) + " — " + time($0.end) } ?? "未设置"
    }

    private func switchEpisode(_ episode: MediaItem) {
        closePanel()
        Task { await model.switchPlayer(item: episode.id, title: episode.title) }
    }

    private func time(_ value: Double) -> String {
        guard value.isFinite, value >= 0 else { return "--:--" }
        let secs = Int(value)
        return String(format: "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
    }
}

private struct TVPressStyle: ButtonStyle {
    let onPressing: (Bool) -> Void
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.onChange(of: configuration.isPressed) { _, pressing in onPressing(pressing) }
    }
}
