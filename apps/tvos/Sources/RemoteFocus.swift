import SwiftUI

@MainActor final class TVRemoteFocus {
    struct Target {
        let rect: CGRect
        let probe: UIView
        let focus: () -> Void
        let activate: () -> Void
        let back: () -> Bool
    }
    var targets: [UUID: Target] = [:]
    var exits: [UUID: (UIView, () -> Void)] = [:]
    private func isActive(_ probe: UIView) -> Bool {
        guard let window = probe.window else { return false }
        var responder: UIResponder? = probe
        while let current = responder, !(current is UIViewController) { responder = current.next }
        guard var owner = responder as? UIViewController else { return false }
        while let parent = owner.parent { owner = parent }
        var top = window.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        guard owner === top else { return false }
        var visible = window.bounds
        var current: UIView? = probe
        while let view = current {
            visible = visible.intersection(view.convert(view.bounds, to: window))
            current = view.superview
        }
        return !visible.isNull && !visible.isEmpty
    }
    func exit() -> Bool {
        func depth(_ view: UIView) -> Int { var depth = 0; var view: UIView? = view; while let parent = view?.superview { depth += 1; view = parent }; return depth }
        if let action = exits.values.filter({ isActive($0.0) }).max(by: { depth($0.0) < depth($1.0) })?.1 { action(); return true }
        if let focused, let target = targets[focused], isActive(target.probe) { return target.back() }
        return false
    }
    var focused: UUID?
    private var moving = false
    func remove(_ id: UUID) { targets[id] = nil; if focused == id { focused = nil } }
    func handle(_ key: String) async -> Bool {
        guard !moving else { return false }
        let visible = targets.filter { _, target in
            guard let window = target.probe.window, !target.rect.isEmpty, window.bounds.intersects(target.rect) else { return false }
            return isActive(target.probe)
        }
        guard let focused, let current = visible[focused] else { return false }
        if key == "ok" { current.activate(); return true }
        guard ["up", "down", "left", "right"].contains(key) else { return false }
        let frames = visible.filter { $0.key != focused }.map { id, target in
            TVRemoteFrame(id: id, x: target.rect.midX, y: target.rect.midY)
        }
        guard let id = TVRemoteFrame.next(key, x: current.rect.midX, y: current.rect.midY, candidates: frames), let target = visible[id] else { return false }
        moving = true; defer { moving = false }
        target.focus()
        for _ in 0..<20 {
            if self.focused == id { return true }
            do { try await Task.sleep(for: .milliseconds(25)) } catch { return false }
        }
        return false
    }
}

private struct TVRemoteProbe: UIViewRepresentable {
    let update: (UIView) -> Void
    func makeUIView(context: Context) -> UIView { let view = UIView(); view.isUserInteractionEnabled = false; return view }
    func updateUIView(_ view: UIView, context: Context) { DispatchQueue.main.async { update(view) } }
}

struct TVRemoteButton<Content: View>: View {
    let action: () -> Void
    @ViewBuilder let content: () -> Content
    @EnvironmentObject private var model: AppModel
    @Environment(\.isEnabled) private var enabled
    @Environment(\.dismiss) private var dismiss
    @Environment(\.isPresented) private var presented
    @Environment(\.tvPageActive) private var active
    @FocusState private var focused: Bool
    @State private var id = UUID()
    @State private var visible = false
    @State private var rect = CGRect.zero
    @State private var probe: UIView?
    var body: some View {
        Button(action: action, label: content).buttonStyle(TVRemoteNativeStyle()).focusEffectDisabled().focused($focused)
            .background(TVRemoteProbe { value in if probe !== value { probe = value } })
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { rect = $0; register() }
            .onChange(of: probe) { _, _ in register() }
            .onChange(of: enabled) { _, _ in register() }
            .onChange(of: active) { _, _ in register() }
            .onChange(of: focused) { _, value in
                if value { model.remoteFocus.focused = id }
                else if model.remoteFocus.focused == id { model.remoteFocus.focused = nil }
            }
            .onAppear { visible = true; register() }.onDisappear { visible = false; model.remoteFocus.remove(id) }
    }
    private func register() {
        guard visible, enabled, active, let probe else { model.remoteFocus.remove(id); return }
        model.remoteFocus.targets[id] = .init(rect: rect, probe: probe, focus: { focused = true }, activate: action, back: { if presented { dismiss(); return true }; return false })
    }
}

private struct TVPressedKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var tvPressed: Bool { get { self[TVPressedKey.self] } set { self[TVPressedKey.self] = newValue } }
}
private struct TVRemoteNativeStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.environment(\.tvPressed, configuration.isPressed).opacity(configuration.isPressed ? 0.8 : 1)
    }
}

private struct TVRemoteExit: ViewModifier {
    // nil 表示不拦截返回键，交给 tvOS 回到主屏幕。
    let action: (() -> Void)?
    @EnvironmentObject private var model: AppModel
    @State private var id = UUID()
    @State private var visible = false
    @State private var probe: UIView?
    @Environment(\.tvPageActive) private var active
    func body(content: Content) -> some View {
        content.onExitCommand(perform: action)
            .background(TVRemoteProbe { value in if probe !== value { probe = value } })
            .onChange(of: probe) { _, _ in register() }
            .onChange(of: active) { _, _ in register() }
            .onChange(of: action == nil) { _, _ in register() }
            .onAppear { visible = true; register() }
            .onDisappear { visible = false; model.remoteFocus.exits[id] = nil }
    }
    private func register() {
        if visible && active, let probe, let action { model.remoteFocus.exits[id] = (probe, action) }
        else { model.remoteFocus.exits[id] = nil }
    }
}
extension View {
    func tvExitCommand(perform action: (() -> Void)?) -> some View { modifier(TVRemoteExit(action: action)) }
}
