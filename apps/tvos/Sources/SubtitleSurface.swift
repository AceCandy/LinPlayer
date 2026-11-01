import AetherEngine
import Libass
import SwiftUI
import UIKit

struct SubtitleSurface: UIViewRepresentable {
    let engine: AetherEngine
    var bottomInset: CGFloat
    var style: JSONValue
    func makeUIView(context: Context) -> SubtitleCanvas { SubtitleCanvas(engine: engine) }
    func updateUIView(_ view: SubtitleCanvas, context: Context) { view.bottomInset = bottomInset; view.style = style }
    static func dismantleUIView(_ view: SubtitleCanvas, coordinator: ()) { view.stop() }
}

@MainActor final class SubtitleCanvas: UIView {
    let engine: AetherEngine
    var bottomInset: CGFloat = 60
    var style: JSONValue = .null
    private let library = ass_library_init()
    private var renderer: OpaquePointer?
    private var track: UnsafeMutablePointer<ASS_Track>?
    private var trackKey = ""
    private var events = Set<String>()
    private var fonts: [FontAttachment] = []
    private var displayLink: CADisplayLink?
    private var assFrame: UIImage?
    private var assSize = CGSize.zero
    private final class ClockTarget: NSObject {
        weak var view: SubtitleCanvas?
        @objc func tick() { view?.setNeedsDisplay() }
    }
    private let clockTarget = ClockTarget()

    init(engine: AetherEngine) {
        self.engine = engine
        super.init(frame: .zero)
        isOpaque = false; isUserInteractionEnabled = false; backgroundColor = .clear
        contentMode = .redraw
        clockTarget.view = self
        if let library {
            // 字幕内容和附件名不能写入系统日志。
            ass_set_message_cb(library, { _, _, _, _ in }, nil)
            renderer = ass_renderer_init(library)
            if let renderer { ass_set_fonts(renderer, nil, "Helvetica", 1, nil, 1) }
        }
    }
    required init?(coder: NSCoder) { nil }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        stop()
        if window != nil {
            let link = CADisplayLink(target: clockTarget, selector: #selector(ClockTarget.tick))
            link.preferredFramesPerSecond = 30
            link.add(to: .main, forMode: .common); displayLink = link
        }
    }
    func stop() { displayLink?.invalidate(); displayLink = nil }
    deinit {
        displayLink?.invalidate()
        if let track { ass_free_track(track) }
        if let renderer { ass_renderer_done(renderer) }
        if let library { ass_library_done(library) }
    }
    private var videoRect: CGRect {
        if let rect = engine.nativePlayerLayer?.videoRect, !rect.isEmpty { return rect }
        guard let size = engine.softwareDisplaySize, size.width > 0, size.height > 0 else { return bounds }
        if engine.videoGravity == .resize { return bounds }
        let scale = engine.videoGravity == .resizeAspectFill
            ? max(bounds.width / size.width, bounds.height / size.height)
            : min(bounds.width / size.width, bounds.height / size.height)
        let shown = CGSize(width: size.width * scale, height: size.height * scale)
        return CGRect(x: bounds.midX - shown.width / 2, y: bounds.midY - shown.height / 2, width: shown.width, height: shown.height)
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(), engine.sourceTime.isFinite else { return }
        let selected = engine.subtitleTracks.first { $0.id == engine.activeSubtitleTrackIndex }
        let header = selected?.assHeader ?? engine.sidecarASSHeader ?? ""
        let styled = ["ass", "ssa"].contains(selected?.codec.lowercased() ?? "")
        let key = "\(selected?.id ?? -1):\(header)"
        if key != trackKey {
            if let track { ass_free_track(track) }
            track = nil; assFrame = nil; events.removeAll(); trackKey = key
            if styled, !header.isEmpty, let library {
                track = ass_new_track(library)
                if let track {
                    header.replacingOccurrences(of: "\0", with: "").withCString { pointer in
                        ass_process_codec_private(track, pointer, Int32(strlen(pointer)))
                    }
                    // 部分源重复使用 ReadOrder=0，以内容和时间去重。
                    ass_set_check_readorder(track, 0)
                }
            }
        }
        if let library {
            var added = false
            for font in engine.fontAttachments where !fonts.contains(font) {
                font.data.withUnsafeBytes { data in
                    guard let pointer = data.baseAddress?.assumingMemoryBound(to: CChar.self), data.count <= Int(Int32.max) else { return }
                    (String(fonts.count) + "-" + font.filename).withCString { ass_add_font(library, $0, pointer, Int32(data.count)) }
                }
                fonts.append(font); added = true
            }
            if added, let next = ass_renderer_init(library) {
                if let renderer { ass_renderer_done(renderer) }
                renderer = next; ass_set_fonts(next, nil, "Helvetica", 1, nil, 1)
                assFrame = nil; assSize = .zero
            }
        }
        let video = videoRect
        let time = engine.sourceTime
        if styled {
            guard let track, let renderer else { return }
            for cue in engine.subtitleCues {
                guard case .text(let raw) = cue.body, cue.startTime.isFinite, cue.endTime.isFinite,
                      cue.startTime >= 0, cue.endTime > cue.startTime, cue.endTime < 4e9 else { continue }
                for event in raw.split(separator: "\n", omittingEmptySubsequences: true) {
                    let id = "\(cue.startTime):\(cue.endTime):\(event)"
                    if events.insert(id).inserted {
                        String(event).withCString { pointer in
                            ass_process_chunk(track, pointer, Int32(strlen(pointer)), Int64(cue.startTime * 1000), Int64((cue.endTime - cue.startTime) * 1000))
                        }
                    }
                }
            }
            let size = CGSize(width: max(1, video.width.rounded()), height: max(1, video.height.rounded()))
            ass_set_font_scale(renderer, max(0.2, style["scale"].number.flatMap { $0 > 0 ? $0 : nil } ?? 1))
            // 自绘层只覆盖视频有效区域；位置超过画面下沿不能直接传负值给 libass。
            let position = min(100, max(0, style["position"].number.flatMap { $0 >= 0 ? $0 : nil } ?? 100))
            ass_set_line_position(renderer, 100 - position)
            ass_set_frame_size(renderer, Int32(size.width), Int32(size.height))
            let source = engine.softwareDisplaySize ?? size
            ass_set_storage_size(renderer, Int32(source.width), Int32(source.height))
            var changed: Int32 = 0
            let images = ass_render_frame(renderer, track, Int64(max(0, min(time, 4e9)) * 1000), &changed)
            if changed != 0 || size != assSize {
                assSize = size
                let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = false
                assFrame = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                    var image = images
                    while let current = image {
                        let value = current.pointee
                        if let bitmap = value.bitmap, value.w > 0, value.h > 0, value.w <= 16384, value.h <= 16384, value.stride >= value.w {
                            let width = Int(value.w), height = Int(value.h)
                            var pixels = [UInt8](repeating: 0, count: width * height * 4)
                            let opacity = 255 - Int(value.color & 255)
                            for y in 0..<height { for x in 0..<width {
                                let alpha = Int(bitmap[y * Int(value.stride) + x]) * opacity / 255
                                let offset = (y * width + x) * 4
                                pixels[offset] = UInt8(Int((value.color >> 24) & 255) * alpha / 255)
                                pixels[offset + 1] = UInt8(Int((value.color >> 16) & 255) * alpha / 255)
                                pixels[offset + 2] = UInt8(Int((value.color >> 8) & 255) * alpha / 255)
                                pixels[offset + 3] = UInt8(alpha)
                            } }
                            if let provider = CGDataProvider(data: Data(pixels) as CFData),
                               let cgImage = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                                    provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) {
                                UIImage(cgImage: cgImage).draw(in: CGRect(x: Int(value.dst_x), y: Int(value.dst_y), width: width, height: height))
                            }
                        }
                        image = value.next
                    }
                }
            }
            assFrame?.draw(in: video)
            return
        }
        for cue in engine.subtitleCues where cue.startTime <= time && time < cue.endTime {
            switch cue.body {
            case .image(let image):
                var canvas = video
                if image.canvasSize.width > 0 && image.canvasSize.height > 0 {
                    let height = video.width * image.canvasSize.height / image.canvasSize.width
                    canvas = CGRect(x: video.minX, y: video.midY - height / 2, width: video.width, height: height)
                }
                let position = image.position
                UIImage(cgImage: image.cgImage).draw(in: CGRect(x: canvas.minX + position.minX * canvas.width,
                    y: canvas.minY + position.minY * canvas.height, width: position.width * canvas.width, height: position.height * canvas.height))
            case .text(let text): drawText([SubtitleTextRun(text: text, color: nil)], placement: cue.placement, video: video, context: context)
            case .richText(let runs): drawText(runs, placement: cue.placement, video: video, context: context)
            }
        }
    }
    private func drawText(_ runs: [SubtitleTextRun], placement: SubtitleTextPlacement?, video: CGRect, context: CGContext) {
        let text = NSMutableAttributedString(string: "")
        let alignment = placement?.alignment ?? 2
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment % 3 == 1 ? .left : alignment % 3 == 0 ? .right : .center
        for run in runs {
            let scale = style["scale"].number.flatMap { $0 > 0 ? $0 : nil } ?? 1
            let size = CGFloat(run.fontSize ?? 36) * bounds.height / 1080 * scale
            var font = run.fontName.flatMap { UIFont(name: $0, size: size) } ?? UIFont.systemFont(ofSize: size, weight: run.isBold || style["bold"].bool == true ? .bold : .regular)
            var traits = font.fontDescriptor.symbolicTraits
            if run.isBold || style["bold"].bool == true { traits.insert(.traitBold) }; if run.isItalic { traits.insert(.traitItalic) }
            if let descriptor = font.fontDescriptor.withSymbolicTraits(traits) { font = UIFont(descriptor: descriptor, size: size) }
            let color = run.color.map { UIColor(red: CGFloat($0.r) / 255, green: CGFloat($0.g) / 255, blue: CGFloat($0.b) / 255, alpha: 1) } ?? .white
            text.append(NSAttributedString(string: run.text, attributes: [.font: font, .foregroundColor: color,
                .paragraphStyle: paragraph, .strokeColor: UIColor.black, .strokeWidth: -max(0, style["border_size"].number.flatMap { $0 >= 0 ? $0 : nil } ?? 3),
                .underlineStyle: run.isUnderlined ? NSUnderlineStyle.single.rawValue : 0,
                .strikethroughStyle: run.isStruckThrough ? NSUnderlineStyle.single.rawValue : 0]))
        }
        let maxWidth = max(1, min(bounds.width, video.width) - 128)
        let size = text.boundingRect(with: CGSize(width: maxWidth, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).size
        let column = (alignment - 1) % 3, row = (alignment - 1) / 3
        let point = placement?.position.map { CGPoint(x: video.minX + $0.x * video.width, y: video.minY + $0.y * video.height) }
            ?? CGPoint(x: column == 0 ? video.minX + 64 : column == 2 ? video.maxX - 64 : video.midX,
                y: row == 2 ? video.minY + 48 : row == 1 ? video.midY : min(video.minY + video.height * (style["position"].number.flatMap { $0 >= 0 ? $0 : nil } ?? 100) / 100 - 48, bounds.maxY - bottomInset))
        let box = CGRect(x: point.x - (column == 0 ? 0 : column == 2 ? size.width : size.width / 2),
            y: point.y - (row == 2 ? 0 : row == 1 ? size.height / 2 : size.height), width: ceil(size.width), height: ceil(size.height))
        context.saveGState(); context.setShadow(offset: CGSize(width: 0, height: 2), blur: 3, color: UIColor.black.cgColor)
        text.draw(with: box, options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        context.restoreGState()
    }
}
