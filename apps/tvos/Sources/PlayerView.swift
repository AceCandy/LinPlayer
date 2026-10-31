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
    @State private var controlHeight: CGFloat = 270
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
            if !controller.subtitleText.isEmpty && !controller.ended {
                Text(controller.subtitleText).font(.title3.bold()).multilineTextAlignment(.center)
                    .padding(12).background(.black.opacity(0.65)).padding(.horizontal, 100)
                    .padding(.trailing, panel == nil ? 0 : 620)
                    .padding(.bottom, controls ? controlHeight + 24 : 60).allowsHitTesting(false).accessibilityHidden(true)
            }
            if !controls {
                Color.clear.contentShape(Rectangle()).focusable().focused($focus, equals: "surface")
                    .onTapGesture { showControls() }
                    .onMoveCommand { _ in showControls() }
            }
            if controller.loading || controller.engine?.isBuffering == true { TVLoading("正在缓冲").frame(maxHeight: .infinity) }
            if controls {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(controller.title).font(.system(size: 34, weight: .semibold)).lineLimit(1)
                        if let source = controller.versions.first(where: { $0.id == controller.actualVersion }), let video = source.streams?.first(where: { $0.type_ == "Video" }) {
                            Text(video.summary).font(.system(size: 22)).foregroundStyle(.white.opacity(0.7))
                        }
                        if let subtitle = controller.textTracks.first(where: { $0.id == controller.engine?.activeSubtitleTrackIndex }) {
                            Text("字幕 · " + subtitle.name).font(.system(size: 22)).foregroundStyle(.white.opacity(0.7))
                        }
                        if let message = controller.message { Text(message).foregroundStyle(.orange) }
                        if let note = controller.subtitleNote { Text(note).font(.caption).foregroundStyle(.secondary) }
                        if let warning = controller.syncWarning { Text(warning).font(.caption).foregroundStyle(.orange) }
                    }.padding(.horizontal, 64).padding(.top, 48).padding(.bottom, 50)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(LinearGradient(colors: [.black.opacity(0.85), .clear], startPoint: .top, endPoint: .bottom))
                    Spacer()
                    nextEpisodeCard
                    VStack(alignment: .leading, spacing: 22) {
                        manualSkipButton
                        if let error = controller.skipError { RetryMessage(text: error) { controller.retrySkip(); touch() } }
                        if !controller.ended, let error = controller.queueError {
                            RetryMessage(text: error) { controller.loadQueue(); touch() }
                        }
                        progressControl
                        transportControls
                    }.padding(.horizontal, 64).padding(.bottom, 40).padding(.top, 28)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.92)], startPoint: .top, endPoint: .bottom))
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlHeight = $0 }
                }.disabled(panel != nil || model.switchingPlayback || controller.closing)
            } else if panel == nil {
                manualSkipButton.padding(.trailing, 64).padding(.bottom, 110).frame(maxWidth: .infinity, alignment: .trailing)
            }

        }
        .ignoresSafeArea(.container)
        .preferredColorScheme(.dark)
        .foregroundStyle(.white)
        .tint(TVPalette.accent(false, palette: model.colorScheme))
        .interactiveDismissDisabled()
        .onPlayPauseCommand { controller.toggle(); touch() }
        .onExitCommand {
            if panel != nil { closePanel() }
            else if preview != nil { preview = nil }
            else if controls { controls = false; focus = "surface" }
            else { Task { await model.closePlayer() } }
        }
        .onMoveCommand { _ in touch() }
        .onChange(of: focus) { _, _ in touch() }
        .onChange(of: controller.message) { _, next in if next != nil { showControls() } else { touch() } }
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
        .overlay(alignment: .trailing) {
            if panel != nil {
                trackPanel.frame(width: 620).frame(maxHeight: .infinity).background(.black.opacity(0.96))
                    .task(id: panel) { await Task.yield(); panelFocus = selectedPanelKey }
            }
        }

    }

    @State private var lastPanel = "subtitles"
    private func showControls() { controls = true; focus = "pause"; touch() }
    private func touch() { interaction += 1 }

    @FocusState private var panelFocus: String?
    private func closePanel() { panel = nil; focus = lastPanel; touch() }
    private func openPanel(_ key: String) { lastPanel = key; panel = key; touch() }
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
        if controller.ended {
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
                                    Text("下一集" + (countdown.map { " · \($0) 秒后播放" } ?? "")).foregroundStyle(.secondary)
                                    Text(next.title).lineLimit(2)
                                }
                            }
                        }.buttonStyle(TVRowStyle()).focusEffectDisabled()
                        Toggle("自动下一集", isOn: $autoNext).toggleStyle(TVToggleStyle())
                    } else { Text("本季已播放完毕").foregroundStyle(.secondary) }
                }.frame(width: 650).padding(24).background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 18))
            }.padding(.horizontal, 64).padding(.bottom, 18)
        }
    }
    @ViewBuilder private var progressControl: some View {
        if controller.canSeek, let duration = controller.engine?.duration, duration > 0 {
            Button {
                if let value = preview { preview = nil; Task { await controller.seek(to: value) } }
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
                    if direction == .left || direction == .right {
                        preview = min(max(0, (preview ?? controller.position) + (direction == .left ? -10 : 10)), duration); touch()
                    }
                }
        } else if !controller.loading { Text("当前内容暂不支持定位").font(.caption).foregroundStyle(.secondary) }
    }
    private var transportControls: some View {
        HStack(spacing: 20) {
            if let previous = controller.previousEpisode { Button { switchEpisode(previous) } label: { Image(systemName: "backward.end.fill") }.accessibilityLabel("上一集") }
            Button { controller.toggle(); touch() } label: { Label(controller.paused ? "播放" : "暂停", systemImage: controller.paused ? "play.fill" : "pause.fill") }
                .disabled(controller.loading || controller.changingTrack).focused($focus, equals: "pause")
            if let next = controller.nextEpisode { Button { switchEpisode(next) } label: { Image(systemName: "forward.end.fill") }.accessibilityLabel("下一集") }
            Spacer(minLength: 20)
            if controller.audioSelectable { Button("音轨") { openPanel("audio") }.disabled(controller.changingTrack).focused($focus, equals: "audio") }
            Button("字幕") { openPanel("subtitles") }.focused($focus, equals: "subtitles")
            Button("倍速") { openPanel("speed") }.disabled(!controller.canSetSpeed).focused($focus, equals: "speed")
            Button("画面") { openPanel("picture") }.focused($focus, equals: "picture")
            Button("版本") { openPanel("versions") }.focused($focus, equals: "versions")
            if controller.engine?.mediaChapters.isEmpty == false { Button("章节") { openPanel("chapters") }.focused($focus, equals: "chapters") }
            if controller.message != nil && !controller.ended {
                Button("重试") { Task { await model.switchPlayer(item: controller.item, title: controller.title, version: controller.version, fromStart: controller.fromStart) } }
            }
            Button(controller.closing ? "正在保存…" : "退出") { Task { await model.closePlayer() } }.disabled(controller.closing)
        }.buttonStyle(TVActionStyle(compact: true)).focusEffectDisabled()
    }

    private var trackPanel: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text(panelTitle).font(.title)
                if panel == "speed", let engine = controller.engine {
                    ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 1.75, 2].filter { $0 <= engine.maxSupportedRate }, id: \.self) { rate in
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
                        Button((version.id == controller.actualVersion ? "✓ " : "") + version.name) {
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
                } else {
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
                    if controller.textTracks.isEmpty { Text("无可用文本字幕").foregroundStyle(.secondary) }
                    if controller.unsupportedSubtitleCount > 0 { Text("样式、位图或由系统渲染的字幕暂不提供切换。").foregroundStyle(.secondary) }
                }
                Button("返回") { closePanel() }
            }.padding(40).buttonStyle(TVRowStyle()).toggleStyle(TVToggleStyle()).focusEffectDisabled()
        }
        .onExitCommand { closePanel() }
    }

    private var panelTitle: String {
        switch panel {
        case "audio": return "音轨"
        case "speed": return "倍速"
        case "picture": return "画面比例"
        case "chapters": return "章节"
        case "versions": return "播放版本"
        default: return "字幕"
        }
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
