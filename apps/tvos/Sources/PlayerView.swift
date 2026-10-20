import SwiftUI
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
    @State private var panel: String?
    @State private var preview: Double?
    @State private var interaction = 0
    @State private var countdown: Int?
    @AppStorage("autoNextEpisode") private var autoNext = true
    @FocusState private var focus: String?

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            if let engine = controller.engine { VideoSurface(engine: engine).ignoresSafeArea() }
            if !controller.subtitleText.isEmpty {
                Text(controller.subtitleText).font(.title3.bold()).multilineTextAlignment(.center)
                    .padding(12).background(.black.opacity(0.65)).padding(.horizontal, 100)
                    .padding(.bottom, controls ? 270 : 60).allowsHitTesting(false).accessibilityHidden(true)
            }
            if !controls {
                Color.clear.contentShape(Rectangle()).focusable().focused($focus, equals: "surface")
                    .onTapGesture { showControls() }
                    .onMoveCommand { _ in showControls() }
            }
            if controller.loading || controller.engine?.isBuffering == true { ProgressView("正在缓冲").frame(maxHeight: .infinity) }
            if controls {
                VStack(alignment: .leading, spacing: 20) {
                    Text(controller.title).font(.system(size: 30, weight: .semibold)).lineLimit(1)
                    if let message = controller.message { Text(message).foregroundStyle(.orange) }
                    if let note = controller.subtitleNote { Text(note).font(.caption).foregroundStyle(.secondary) }
                    if let warning = controller.syncWarning { Text(warning).font(.caption).foregroundStyle(.orange) }
                    if controller.canSeek, let duration = controller.engine?.duration, duration > 0 {
                        Button {
                            if let value = preview { preview = nil; Task { await controller.seek(to: value) } }
                        } label: {
                            VStack(alignment: .leading) {
                                ProgressView(value: min(max(0, (preview ?? controller.position) / duration), 1))
                                Text("\(time(preview ?? controller.position)) / \(time(duration))" + (preview == nil ? "" : " · 确认跳转，返回取消"))
                            }
                        }
                        .buttonStyle(TVRowStyle()).focusEffectDisabled()
                        .focused($focus, equals: "progress")
                        .onMoveCommand { direction in
                            guard focus == "progress" else { return }
                            if direction == .left || direction == .right {
                                preview = min(max(0, (preview ?? controller.position) + (direction == .left ? -10 : 10)), duration)
                                touch()
                            }
                        }
                    } else if !controller.loading { Text("当前内容暂不支持定位").font(.caption).foregroundStyle(.secondary) }
                    if controller.ended {
                        if controller.queueLoading { ProgressView("正在准备下一集") }
                        else if let error = controller.queueError { RetryMessage(text: error) { controller.loadQueue() } }
                        else if let next = controller.nextEpisode {
                            HStack {
                                Button("下一集：" + next.title + (countdown.map { " · \($0) 秒" } ?? "")) { switchEpisode(next) }
                                Toggle("自动下一集", isOn: $autoNext)
                            }.disabled(model.switchingPlayback)
                        } else { Text("本季已播放完毕").foregroundStyle(.secondary) }
                    }
                    ScrollView(.horizontal) {
                    HStack(spacing: 20) {
                        Button { controller.toggle(); touch() } label: { Label(controller.paused ? "播放" : "暂停", systemImage: controller.paused ? "play.fill" : "pause.fill") }
                            .disabled(controller.loading || controller.changingTrack).focused($focus, equals: "pause")
                        if controller.audioSelectable {
                            Button(controller.changingTrack ? "正在切换音轨" : "音轨") { lastPanel = "audio"; panel = "audio"; touch() }
                                .disabled(controller.changingTrack).focused($focus, equals: "audio")
                        }
                        Button("字幕") { lastPanel = "subtitles"; panel = "subtitles"; touch() }.focused($focus, equals: "subtitles")
                        Button("倍速") { lastPanel = "speed"; panel = "speed"; touch() }.focused($focus, equals: "speed")
                        Button("画面") { lastPanel = "picture"; panel = "picture"; touch() }.focused($focus, equals: "picture")
                        Button("版本") { lastPanel = "versions"; panel = "versions"; touch() }.focused($focus, equals: "versions")
                        if controller.engine?.mediaChapters.isEmpty == false {
                            Button("章节") { lastPanel = "chapters"; panel = "chapters"; touch() }.focused($focus, equals: "chapters")
                        }
                        if controller.queueLoading || controller.queueError != nil || !controller.episodes.isEmpty {
                            Button("选集") { lastPanel = "episodes"; panel = "episodes"; touch() }.focused($focus, equals: "episodes")
                        }
                        if let previous = controller.previousEpisode { Button("上一集") { switchEpisode(previous) } }
                        if let next = controller.nextEpisode { Button("下一集") { switchEpisode(next) } }
                        if controller.message != nil && !controller.ended {
                            Button("重试") {
                                Task {
                                    await model.switchPlayer(item: controller.item, title: controller.title, version: controller.version, fromStart: controller.fromStart)
                                }
                            }
                        }
                        Button(controller.closing ? "正在保存…" : "退出播放") { Task { await model.closePlayer() } }
                            .disabled(controller.closing)
                    }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled().padding(.vertical, 12)
                    }.disabled(model.switchingPlayback || controller.closing)
                }.padding(.horizontal, 64).padding(.vertical, 32)
                    .background { LinearGradient(colors: [.clear, .black.opacity(0.92)], startPoint: .top, endPoint: .bottom).padding(.top, -60) }
            }
        }
        .ignoresSafeArea(.container)
        .preferredColorScheme(.dark)
        .foregroundStyle(.white)
        .tint(TVPalette.accent(false))
        .interactiveDismissDisabled()
        .onPlayPauseCommand { controller.toggle(); touch() }
        .onExitCommand {
            if preview != nil { preview = nil }
            else if controls { controls = false; focus = "surface" }
            else { Task { await model.closePlayer() } }
        }
        .onMoveCommand { _ in touch() }
        .onChange(of: focus) { _, _ in touch() }
        .onChange(of: controller.message) { _, next in if next != nil { showControls() } }
        .task { await controller.start(); focus = "pause" }
        .task(id: "\(controller.ended):\(controller.queueLoading):\(controller.nextEpisode?.id ?? ""):\(autoNext):\(model.sceneActive):\(panel ?? "")") {
            countdown = nil
            guard controller.ended, !controller.queueLoading, controller.queueError == nil,
                  let next = controller.nextEpisode, autoNext, model.sceneActive, panel == nil else { return }
            for seconds in stride(from: 5, through: 1, by: -1) {
                countdown = seconds
                do { try await Task.sleep(for: .seconds(1)) } catch { countdown = nil; return }
            }
            guard !Task.isCancelled, !controller.closing, model.sceneActive, autoNext, panel == nil,
                  controller.ended, controller.nextEpisode?.id == next.id else { countdown = nil; return }
            countdown = nil; switchEpisode(next)
        }
        .task(id: interaction) {
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            if panel == nil && preview == nil && !controller.loading && controller.engine?.isBuffering != true && !controller.changingTrack && controller.message == nil && !controller.closing {
                controls = false; focus = "surface"
            }
        }
        .sheet(isPresented: Binding(get: { panel != nil }, set: { if !$0 { panel = nil } }), onDismiss: {
            focus = lastPanel; touch()
        }) { trackPanel }
    }

    @State private var lastPanel = "subtitles"
    private func showControls() { controls = true; focus = "pause"; touch() }
    private func touch() { interaction += 1 }

    private var trackPanel: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text(panelTitle).font(.title)
                if panel == "speed", let engine = controller.engine {
                    ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 1.75, 2].filter { $0 <= engine.maxSupportedRate }, id: \.self) { rate in
                        Button((controller.speed == rate ? "✓ " : "") + "\(rate)×") { controller.setSpeed(rate); panel = nil }
                    }
                } else if panel == "picture" {
                    Button((controller.pictureMode == "fit" ? "✓ " : "") + "适应画面") { controller.setPictureMode("fit"); panel = nil }
                    Button((controller.pictureMode == "fill" ? "✓ " : "") + "填满画面") { controller.setPictureMode("fill"); panel = nil }
                    Button((controller.pictureMode == "stretch" ? "✓ " : "") + "拉伸") { controller.setPictureMode("stretch"); panel = nil }
                } else if panel == "chapters", let engine = controller.engine {
                    ForEach(engine.mediaChapters) { chapter in
                        Button(chapter.name + " · " + time(chapter.startSeconds)) {
                            Task { await controller.seek(to: chapter.startSeconds) }; panel = nil
                        }
                    }
                } else if panel == "episodes" {
                    if controller.queueLoading { ProgressView("正在加载当前季") }
                    if let error = controller.queueError { RetryMessage(text: error) { controller.loadQueue() } }
                    ForEach(controller.episodes) { episode in
                        Button((episode.id == controller.item ? "✓ " : "") + episode.title) { switchEpisode(episode) }
                            .disabled(episode.id == controller.item || model.switchingPlayback)
                    }
                    Toggle("自动下一集", isOn: $autoNext)
                } else if panel == "versions" {
                    if let error = controller.versionError { RetryMessage(text: error) { controller.loadVersions() } }
                    ForEach(controller.versions) { version in
                        Button((version.id == controller.actualVersion ? "✓ " : "") + version.name) {
                            panel = nil
                            Task { await model.switchPlayer(item: controller.item, title: controller.title, version: version.id) }
                        }.disabled(model.switchingPlayback)
                    }
                } else if panel == "audio", let engine = controller.engine {
                    ForEach(engine.audioTracks) { track in
                        Button((engine.activeAudioTrackIndex == track.id ? "✓ " : "") + track.name) {
                            lastPanel = "audio"; controller.selectAudio(track.id); panel = nil
                        }
                    }
                } else {
                    Button((controller.engine?.activeSubtitleTrackIndex == nil ? "✓ " : "") + "关闭字幕") {
                        lastPanel = "subtitles"; controller.selectSubtitle(nil); panel = nil
                    }
                    ForEach(controller.textTracks) { track in
                        Button((controller.engine?.activeSubtitleTrackIndex == track.id ? "✓ " : "") + track.name) {
                            lastPanel = "subtitles"; controller.selectSubtitle(track.id); panel = nil
                        }
                    }
                    if controller.textTracks.isEmpty { Text("无可用文本字幕").foregroundStyle(.secondary) }
                    if controller.unsupportedSubtitleCount > 0 { Text("样式、位图或由系统渲染的字幕暂不提供切换。").foregroundStyle(.secondary) }
                }
                Button("返回") { panel = nil }
            }.padding(64).buttonStyle(TVRowStyle()).toggleStyle(TVToggleStyle()).focusEffectDisabled()
        }
        .onExitCommand { panel = nil }
    }

    private var panelTitle: String {
        switch panel {
        case "audio": return "音轨"
        case "speed": return "倍速"
        case "picture": return "画面比例"
        case "chapters": return "章节"
        case "episodes": return "选集"
        case "versions": return "播放版本"
        default: return "字幕"
        }
    }

    private func switchEpisode(_ episode: MediaItem) {
        panel = nil
        Task { await model.switchPlayer(item: episode.id, title: episode.title) }
    }

    private func time(_ value: Double) -> String {
        guard value.isFinite, value >= 0 else { return "--:--" }
        let secs = Int(value)
        return String(format: "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
    }
}
