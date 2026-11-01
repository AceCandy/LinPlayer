import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

struct TVRemoteFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

extension AppModel {
    func refreshCompanionSession(privateSpace: Bool, backup: Bool = false) async throws {
        remoteEpoch = ""
        backupEnabled = false; backupExport = .null; backupPreview = .null; backupContent = nil
        let value = try await companion("companion.hostSession", ["private": .bool(privateSpace), "backup": .bool(backup)])
        backupEnabled = backup && !privateSpace
        remoteEpoch = value["epoch"].string ?? ""
        companionStatus = try await companion("companion.status")
    }
    func handleCompanion(_ event: JSONValue) async {
        guard let id = event["request_id"].string else { return }
        var refreshAccounts = false
        defer { if refreshAccounts { busy = false } }
        do {
            let epoch = remoteEpoch
            guard !epoch.isEmpty, event["epoch"].string == epoch, !privateSpace, !busy else { throw CoreFailure(code: "E_PERMISSION") }
            func checkSession() throws {
                guard !Task.isCancelled, !privateSpace, remoteEpoch == epoch else { throw CoreFailure(code: "E_PERMISSION") }
            }
            let valid = try await companion("companion.hostReply", ["request_id": .string(id), "validate": .bool(true)])
            guard valid["valid"].bool == true, valid["epoch"].string == epoch, remoteEpoch == epoch, !privateSpace else { throw CoreFailure(code: "E_PERMISSION") }
            let name = event["name"].string ?? ""
            guard case .object(let args) = event["args"] else { throw CoreFailure(code: "E_INVALID") }
            let output: JSONValue
            switch name {
            case "prefs.backupExport":
                guard backupEnabled, backupExport != .null, args["path"] == .null else { throw TVRemoteFailure(message: "请先在电视上准备导出") }
                output = backupExport; backupExport = .null
            case "prefs.backupPreview":
                guard backupEnabled, backupContent == nil, args["path"] == .null, let content = args["content"].string,
                      content.utf8.count <= 2 * 1024 * 1024 else { throw TVRemoteFailure(message: "请先处理电视上的备份预览，文件不得超过 2 MiB") }
                let preview = try await companion("prefs.backupPreview", ["content": .string(content)])
                try checkSession(); guard backupEnabled else { throw CoreFailure(code: "E_PERMISSION") }
                backupContent = content; backupPreview = preview; output = preview
            case "state":
                if let player = playback, let engine = player.engine {
                    output = .object(["title": .string(player.title), "player": .object(["item_id": .string(player.item),
                        "position": .number(engine.currentTime), "duration": .number(engine.duration), "paused": .bool(player.paused)]), "backup": .bool(backupEnabled)])
                } else { output = .object(["title": .string(""), "player": .null, "backup": .bool(backupEnabled)]) }
            case "player.setPause":
                guard let player = playback, let paused = args["paused"].bool, !player.loading, !player.closing, !player.changingTrack else { throw CoreFailure(code: "E_BUSY") }
                if player.paused != paused { player.toggle() }
                for _ in 0..<20 where player.paused != paused { try await Task.sleep(for: .milliseconds(50)) }
                guard player.paused == paused, playback === player else { throw TVRemoteFailure(message: "播放状态没有完成切换") }
                output = .bool(true)
            case "player.seek":
                guard let player = playback, player.canSeek, let position = args["pos"].number, position.isFinite,
                      let duration = player.engine?.duration, duration > 0 else { throw CoreFailure(code: "E_INVALID") }
                let target = min(max(0, position), duration)
                await player.seek(to: target)
                try checkSession()
                guard playback === player, !player.closing, abs((player.engine?.currentTime ?? -100) - target) < 3 else { throw TVRemoteFailure(message: "定位没有完成，请重试") }
                output = .bool(true)
            case "player.stopPlayback":
                let player = playback
                await closePlayer()
                try checkSession()
                if let warning = player?.syncWarning { throw TVRemoteFailure(message: warning) }
                output = .bool(true)
            case "open":
                guard let item = args["item_id"].string, !item.isEmpty, let api else { throw CoreFailure(code: "E_INVALID") }
                await closePlayer()
                try checkSession()
                if let server = args["server_id"].string, !server.isEmpty, server != account?.server {
                    try await switchServer(server, refreshCompanion: false); try checkSession()
                }
                let value = try await api.request("emby.itemDetail", ["item_id": .string(item), "with_children": .bool(false)])
                try checkSession()
                guard let title = value["name"].string, let type = value["type_"].string else { throw CoreFailure(code: "E_PROTOCOL") }
                pendingItem = .route(id: item, name: title, type: type)
                for _ in 0..<20 where pendingItem != nil { try await Task.sleep(for: .milliseconds(50)); try checkSession() }
                guard pendingItem == nil else { throw TVRemoteFailure(message: "详情页尚未打开，请重试") }
                output = .bool(true)
            case "key":
                guard let key = args["key"].string else { throw CoreFailure(code: "E_INVALID") }
                if key == "playpause", let player = playback {
                    guard !player.loading, !player.closing, !player.changingTrack else { throw CoreFailure(code: "E_BUSY") }
                    let before = player.paused; player.toggle()
                    for _ in 0..<20 where player.paused == before { try await Task.sleep(for: .milliseconds(50)) }
                    try checkSession()
                    guard player.paused != before else { throw TVRemoteFailure(message: "播放状态没有完成切换") }
                } else {
                    if key == "home" { await closePlayer() }
                    let dismissed = key == "back" && remoteFocus.exit()
                    let handled = dismissed ? true : await (playback == nil ? remoteNavigation : remotePlayback)?(key) ?? false
                    let focused = handled ? true : await remoteFocus.handle(key)
                    guard focused else { throw TVRemoteFailure(message: "当前焦点暂不支持此手机按键，请使用电视遥控器") }
                }
                output = .bool(true)
            default:
                refreshAccounts = ["emby.login", "account.removeAccount", "account.setActiveServer", "account.batchAddServers"].contains(name)
                if refreshAccounts { busy = true; await closePlayer() }
                if name == "emby.login" {
                    var login = args; login["device_id"] = .string(api?.deviceID ?? "")
                    output = try await companion(name, login)
                } else if name.hasPrefix("emby."), let api { output = try await api.request(name, args) }
                else { output = try await companion(name, args) }
                try checkSession()
            }
            if refreshAccounts { try await reloadAccounts(refreshCompanion: false); try checkSession() }
            _ = try await companion("companion.hostReply", ["request_id": .string(id), "data": output])
        } catch {
            _ = try? await companion("companion.hostReply", ["request_id": .string(id), "error": .string(error.localizedDescription)])
        }
    }
}

struct CompanionView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            TVSectionHeading("手机遥控")
            Text("手机与 Apple TV 连接同一网络后扫码。")
            Toggle("启用手机遥控", isOn: Binding(get: { model.companionStatus["enabled"].bool == true }, set: { enabled in
                busy = true
                Task {
                    defer { busy = false }
                    do {
                        model.companionStatus = try await model.companion("companion.setEnabled", ["enabled": .bool(enabled)])
                        try await model.refreshCompanionSession(privateSpace: model.privateSpace)
                        error = nil
                    } catch { self.error = error.localizedDescription }
                }
            })).toggleStyle(TVToggleStyle()).disabled(busy)
            if let url = model.companionStatus["url"].string, !url.isEmpty, let image = Self.qrImage(url) {
                Image(uiImage: image).interpolation(.none).resizable().frame(width: 400, height: 400).padding(18).background(.white)
                Text(model.companionStatus["connected"].bool == true ? "手机已连接" : "等待手机连接").foregroundStyle(.secondary)
            }
            ForEach(["error", "ip_error"], id: \.self) { key in
                if let value = model.companionStatus[key].string, !value.isEmpty { Text(value).foregroundStyle(.orange) }
            }
            if let error { RetryMessage(text: error) { Task { await load() } } }
            Button("返回") { dismiss() }.buttonStyle(TVActionStyle())
        }.padding(64).task { await load() }.tvExitCommand { dismiss() }
    }
    private func load() async {
        do { model.companionStatus = try await model.companion("companion.start"); error = nil }
        catch { self.error = error.localizedDescription }
    }
    static func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8)
        guard let output = filter.outputImage, let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

struct TVAboutView: View {
    let api: MediaAPI
    @State private var update: JSONValue = .null
    @State private var error: String?
    @State private var checking = false
    @State private var channel = "stable"
    @State private var channelTouched = false
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 28) {
            TVSectionHeading("关于 LinPlayer")
            Text("版本 " + (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
            Text("播放内核：AetherEngine")
            TVChoice(title: "更新渠道", selection: Binding(get: { channel }, set: { value in
                channelTouched = true
                checking = true
                Task {
                    defer { checking = false }
                    do { _ = try await api.request("prefs.setUpdateSettings", ["channel": .string(value)]); channel = value; update = .null; error = nil }
                    catch { self.error = error.localizedDescription }
                }
            }), options: [("stable", "稳定版"), ("prerelease", "预发布版")]).disabled(checking)
            Button(checking ? "正在检查…" : "检查更新") {
                checking = true
                Task {
                    defer { checking = false }
                    do { update = try await api.request("system.checkUpdate"); error = nil }
                    catch { self.error = error.localizedDescription }
                }
            }.disabled(checking)
            if update["has_update"].bool == true {
                Text("发现新版本 " + (update["update"]["version"].string ?? ""))
                Text(update["update"]["notes"].string ?? "")
            } else if update["has_update"].bool == false { Text("当前已是最新版本") }
            Text("Apple TV 请通过原安装渠道更新应用。").foregroundStyle(.secondary)
            if let error { Text(error).foregroundStyle(.orange) }
        }.padding(64) }.buttonStyle(TVActionStyle())
            .task {
                do {
                    let value = try await api.request("prefs.getUpdateSettings")["channel"].string ?? "stable"
                    if !channelTouched { channel = value }
                } catch { self.error = error.localizedDescription }
            }
    }
}

struct TVBackupView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var accounts = true
    @State private var settings = true
    @State private var error: String?
    @State private var working = false
    @State private var backupRequested = false
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 26) {
            TVSectionHeading("备份与还原")
            Text("含账号的备份包含登录凭据，请勿公开分享。手机与电视之间使用局域网 HTTP 传输。")
            Toggle("包含账号", isOn: $accounts).toggleStyle(TVToggleStyle())
            Toggle("包含设置", isOn: $settings).toggleStyle(TVToggleStyle())
            if model.backupEnabled {
                if let url = model.companionStatus["url"].string, let image = CompanionView.qrImage(url) {
                    Image(uiImage: image).interpolation(.none).resizable().frame(width: 320, height: 320).padding(16).background(.white)
                }
                Text("扫码打开手机页面的备份页。导入文件将在此处预览，确认后才会还原。")
                Button("准备导出到手机") { perform {
                    let value = try await model.companion("prefs.backupExport", ["accounts": .bool(accounts), "settings": .bool(settings)])
                    guard (value["content"].string?.utf8.count ?? 0) <= 2 * 1024 * 1024 else { throw TVRemoteFailure(message: "备份超过 2 MiB，暂不能通过手机传输") }
                    model.backupExport = value
                } }.disabled(!accounts && !settings)
                if model.backupExport != .null { Text("已准备好，请在手机上下载。领取后自动清除本次导出内容。").foregroundStyle(.secondary) }
                if model.backupPreview != .null {
                    Text("待还原：\(Int(model.backupPreview["accounts"].number ?? 0)) 个账号" + (model.backupPreview["has_settings"].bool == true ? "，包含设置" : ""))
                    Text("账号将合并到现有账号；勾选的设置将替换当前设置。").foregroundStyle(.secondary)
                    Button("确认还原") { perform {
                        guard let content = model.backupContent else { throw CoreFailure(code: "E_INVALID") }
                        model.busy = true; defer { model.busy = false }
                        let result = try await model.companion("prefs.backupImport", ["content": .string(content), "accounts": .bool(accounts), "settings": .bool(settings)])
                        model.backupContent = nil; model.backupPreview = .null
                        try await model.reloadAccounts()
                        model.notice = "还原完成，导入 \(Int(result["imported"].number ?? 0)) 个账号。请重新扫码连接。"
                    } }.disabled(!accounts && !settings)
                    Button("取消本次还原") { model.backupContent = nil; model.backupPreview = .null }
                }
            } else {
                Button("开启本次手机传输") { backupRequested = true; perform { try await model.refreshCompanionSession(privateSpace: false, backup: true) } }
            }
            if let error { Text(error).foregroundStyle(.orange) }
            Button("返回") { dismiss() }
        }.padding(64).disabled(working) }.buttonStyle(TVActionStyle())
            .onDisappear { if backupRequested || model.backupEnabled { Task { try? await model.refreshCompanionSession(privateSpace: model.privateSpace) } } }
            .tvExitCommand { dismiss() }
    }
    private func perform(_ action: @escaping () async throws -> Void) {
        guard !working else { return }; working = true
        Task { defer { working = false }; do { try await action(); error = nil } catch { self.error = error.localizedDescription } }
    }
}
