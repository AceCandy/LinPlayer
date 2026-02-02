import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:lin_player_core/app_config/app_config.dart';
import 'package:lin_player_core/state/media_server_type.dart';
import 'package:lin_player_server_api/services/server_share_text_parser.dart';
import 'package:lin_player_state/lin_player_state.dart';
import 'package:package_info_plus/package_info_plus.dart';

import 'tv_remote_command_dispatcher.dart';

class TvRemoteService extends ChangeNotifier {
  TvRemoteService._();

  static final TvRemoteService instance = TvRemoteService._();

  HttpServer? _server;
  final Set<WebSocket> _wsClients = <WebSocket>{};
  String? _token;
  List<InternetAddress> _ipv4 = const [];

  AppState? _appState;
  String _appVersion = '';

  bool get isRunning => _server != null;
  int? get port => _server?.port;
  String? get token => _token;
  List<String> get ipv4Addresses => _ipv4.map((a) => a.address).toList();

  Uri? get firstRemoteUrl {
    final p = port;
    final t = token;
    if (p == null || t == null || t.isEmpty) return null;
    final ip = ipv4Addresses.firstWhere((e) => e.isNotEmpty, orElse: () => '');
    if (ip.isEmpty) return null;
    return Uri.parse('http://$ip:$p/').replace(queryParameters: {'token': t});
  }

  Future<void> start({required AppState appState}) async {
    if (_server != null) return;
    _appState = appState;

    _token = _randomToken();
    _ipv4 = await _listIPv4();
    _appVersion = await _readAppVersion();

    final server = await HttpServer.bind(
      InternetAddress.anyIPv4,
      0,
      shared: true,
    );
    _server = server;
    unawaited(_serve(server));
    notifyListeners();
  }

  Future<void> stop() async {
    final server = _server;
    if (server == null) return;
    _server = null;
    _token = null;
    _ipv4 = const [];
    _appState = null;
    for (final ws in _wsClients.toList(growable: false)) {
      try {
        await ws.close();
      } catch (_) {}
    }
    _wsClients.clear();
    try {
      await server.close(force: true);
    } catch (_) {}
    notifyListeners();
  }

  Future<void> _serve(HttpServer server) async {
    await for (final request in server) {
      // Best-effort: keep handler isolated per request.
      // ignore: unawaited_futures
      _handle(request);
    }
  }

  Future<void> _handle(HttpRequest request) async {
    final path = request.uri.path;
    if (path == '/ws') {
      await _handleWebSocket(request);
      return;
    }

    final response = request.response;
    try {
      if (request.method.toUpperCase() == 'GET') {
        final assetPath = _resolveAssetPath(path);
        if (assetPath != null) {
          final served = await _tryServeAsset(response, assetPath);
          if (served) return;
        }
      }

      // Fallback for older builds or if assets are missing.
      if (path == '/' || path == '/index.html') {
        response.statusCode = HttpStatus.ok;
        response.headers
            .set(HttpHeaders.contentTypeHeader, 'text/html; charset=utf-8');
        response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
        response.write(_indexHtml);
        return;
      }

      if (path == '/api/info') {
        final token = request.uri.queryParameters['token'] ?? '';
        if (!_checkToken(token)) {
          response.statusCode = HttpStatus.unauthorized;
          response.headers
              .set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
          response.write('unauthorized');
          return;
        }
        response.statusCode = HttpStatus.ok;
        response.headers
            .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
        response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
        response.write(
          jsonEncode({
            'ok': true,
            'app': {
              'name': AppConfig.current.displayName,
              'version': _appVersion,
            },
            'server': {
              'activeServerId': _appState?.activeServerId,
              'activeServerName': _appState?.activeServer?.name,
              'activeServerBaseUrl': _appState?.activeServer?.baseUrl,
            },
          }),
        );
        return;
      }

      if (path == '/api/addServer') {
        if (request.method.toUpperCase() != 'POST') {
          response.statusCode = HttpStatus.methodNotAllowed;
          response.headers.set(HttpHeaders.allowHeader, 'POST');
          return;
        }

        final raw = await utf8.decoder.bind(request).join();
        final decoded = jsonDecode(raw);
        final map = decoded is Map ? decoded.map((k, v) => MapEntry('$k', v)) : null;
        if (map == null) {
          response.statusCode = HttpStatus.badRequest;
          response.headers
              .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
          response.write(jsonEncode({'ok': false, 'error': 'invalid json'}));
          return;
        }

        final token = (map['token'] ?? '').toString().trim();
        if (!_checkToken(token)) {
          response.statusCode = HttpStatus.unauthorized;
          response.headers
              .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
          response.write(jsonEncode({'ok': false, 'error': 'unauthorized'}));
          return;
        }

        final result = await _handleAddServer(map);
        response.statusCode = HttpStatus.ok;
        response.headers
            .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
        response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
        response.write(jsonEncode(result));
        return;
      }

      if (path == '/api/parseShareText') {
        if (request.method.toUpperCase() != 'POST') {
          response.statusCode = HttpStatus.methodNotAllowed;
          response.headers.set(HttpHeaders.allowHeader, 'POST');
          return;
        }

        final raw = await utf8.decoder.bind(request).join();
        final decoded = jsonDecode(raw);
        final map =
            decoded is Map ? decoded.map((k, v) => MapEntry('$k', v)) : null;
        if (map == null) {
          response.statusCode = HttpStatus.badRequest;
          response.headers.set(
            HttpHeaders.contentTypeHeader,
            'application/json; charset=utf-8',
          );
          response.write(jsonEncode({'ok': false, 'error': 'invalid json'}));
          return;
        }

        final token = (map['token'] ?? '').toString().trim();
        if (!_checkToken(token)) {
          response.statusCode = HttpStatus.unauthorized;
          response.headers.set(
            HttpHeaders.contentTypeHeader,
            'application/json; charset=utf-8',
          );
          response.write(jsonEncode({'ok': false, 'error': 'unauthorized'}));
          return;
        }

        final text = (map['text'] ?? '').toString();
        final result = _handleParseShareText(text);
        response.statusCode = HttpStatus.ok;
        response.headers.set(
          HttpHeaders.contentTypeHeader,
          'application/json; charset=utf-8',
        );
        response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
        response.write(jsonEncode(result));
        return;
      }

      response.statusCode = HttpStatus.notFound;
      response.headers
          .set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
      response.write('not found');
    } catch (e) {
      response.statusCode = HttpStatus.internalServerError;
      response.headers
          .set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
      response.write('error: $e');
    } finally {
      try {
        await response.close();
      } catch (_) {}
    }
  }

  static String? _resolveAssetPath(String requestPath) {
    var path = requestPath.trim();
    if (path.isEmpty) return null;
    if (!path.startsWith('/')) path = '/$path';
    if (path.contains('..')) return null;
    if (path == '/ws') return null;
    if (path.startsWith('/api/')) return null;

    // Friendly aliases.
    if (path == '/' || path == '/index.html') return 'assets/tv_remote/index.html';
    if (path == '/setup' || path == '/setup.html') return 'assets/tv_remote/setup.html';
    if (path == '/remote' || path == '/remote.html') return 'assets/tv_remote/remote.html';

    // Direct mapping to bundled assets.
    final relative = path.substring(1);
    return 'assets/tv_remote/$relative';
  }

  static String _contentTypeForAsset(String assetPath) {
    final p = assetPath.toLowerCase();
    if (p.endsWith('.html')) return 'text/html; charset=utf-8';
    if (p.endsWith('.css')) return 'text/css; charset=utf-8';
    if (p.endsWith('.js')) return 'application/javascript; charset=utf-8';
    if (p.endsWith('.json')) return 'application/json; charset=utf-8';
    if (p.endsWith('.png')) return 'image/png';
    if (p.endsWith('.jpg') || p.endsWith('.jpeg')) return 'image/jpeg';
    if (p.endsWith('.svg')) return 'image/svg+xml; charset=utf-8';
    return 'application/octet-stream';
  }

  Future<bool> _tryServeAsset(HttpResponse response, String assetPath) async {
    ByteData data;
    try {
      data = await rootBundle.load(assetPath);
    } catch (_) {
      return false;
    }

    response.statusCode = HttpStatus.ok;
    response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
    response.headers.set(HttpHeaders.contentTypeHeader, _contentTypeForAsset(assetPath));
    response.add(data.buffer.asUint8List());
    return true;
  }

  Future<void> _handleWebSocket(HttpRequest request) async {
    final response = request.response;
    final token = request.uri.queryParameters['token'] ?? '';
    if (!_checkToken(token)) {
      response.statusCode = HttpStatus.unauthorized;
      response.headers
          .set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
      response.write('unauthorized');
      await response.close();
      return;
    }

    WebSocket ws;
    try {
      ws = await WebSocketTransformer.upgrade(request);
    } catch (e) {
      try {
        response.statusCode = HttpStatus.badRequest;
        response.headers
            .set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
        response.write('upgrade failed: $e');
        await response.close();
      } catch (_) {}
      return;
    }

    _wsClients.add(ws);
    ws.listen(
      (event) => _onWsEvent(event),
      onDone: () => _wsClients.remove(ws),
      onError: (_) => _wsClients.remove(ws),
      cancelOnError: true,
    );
  }

  void _onWsEvent(dynamic event) {
    if (event is! String) return;
    try {
      final decoded = jsonDecode(event);
      final map = decoded is Map ? decoded.map((k, v) => MapEntry('$k', v)) : null;
      if (map == null) return;

      final type = (map['type'] ?? '').toString().trim();
      if (type != 'command') return;
      final name = (map['name'] ?? '').toString().trim();
      if (name.isEmpty) return;
      TvRemoteCommandDispatcher.instance.dispatch(name, map);
    } catch (_) {
      // ignore invalid messages
    }
  }

  Future<Map<String, dynamic>> _handleAddServer(Map<String, dynamic> map) async {
    final appState = _appState;
    if (appState == null) {
      return {'ok': false, 'error': 'app not ready'};
    }

    bool readBool(dynamic v, {required bool fallback}) {
      if (v is bool) return v;
      if (v is num) return v != 0;
      if (v is String) {
        final s = v.trim().toLowerCase();
        if (s == 'true' || s == '1' || s == 'yes' || s == 'y') return true;
        if (s == 'false' || s == '0' || s == 'no' || s == 'n') return false;
      }
      return fallback;
    }

    List<CustomDomain> readCustomDomains(dynamic v) {
      if (v is! List) return const [];
      final out = <CustomDomain>[];
      for (final e in v) {
        if (e is! Map) continue;
        final name = (e['name'] ?? '').toString().trim();
        final url = (e['url'] ?? '').toString().trim();
        if (url.isEmpty) continue;
        out.add(CustomDomain(name: name.isEmpty ? url : name, url: url));
      }
      return out;
    }

    final typeRaw = (map['type'] ?? '').toString().trim().toLowerCase();
    final baseUrl = (map['baseUrl'] ?? '').toString().trim();
    final schemeRaw = (map['scheme'] ?? 'https').toString().trim().toLowerCase();
    final scheme = schemeRaw == 'http' ? 'http' : 'https';
    final port = (map['port'] ?? '').toString().trim();
    final username = (map['username'] ?? '').toString();
    final password = (map['password'] ?? '').toString();
    final displayName = (map['displayName'] ?? '').toString().trim();
    final remark = (map['remark'] ?? '').toString().trim();
    final iconUrl = (map['iconUrl'] ?? '').toString().trim();
    final activate = readBool(map['activate'], fallback: true);
    final customDomains = readCustomDomains(map['customDomains']);

    if (baseUrl.isEmpty) {
      return {'ok': false, 'error': 'missing baseUrl'};
    }

    try {
      final type = switch (typeRaw) {
        'jellyfin' => MediaServerType.jellyfin,
        'webdav' => MediaServerType.webdav,
        'plex' => MediaServerType.plex,
        _ => MediaServerType.emby,
      };

      if (type == MediaServerType.webdav) {
        if (username.trim().isEmpty) {
          return {'ok': false, 'error': 'missing username'};
        }
        final uri = _buildBaseUri(baseUrl, scheme: scheme, portText: port);
        if (uri == null) return {'ok': false, 'error': 'invalid url'};
        await appState.addWebDavServer(
          baseUrl: uri.toString(),
          username: username.trim(),
          password: password,
          displayName: displayName.isEmpty ? null : displayName,
          remark: remark.isEmpty ? null : remark,
          iconUrl: iconUrl.isEmpty ? null : iconUrl,
          activate: activate,
        );
      } else if (type == MediaServerType.plex) {
        final token = password.trim();
        if (token.isEmpty) {
          return {'ok': false, 'error': 'missing token'};
        }
        final uri = _buildBaseUri(baseUrl, scheme: scheme, portText: port);
        if (uri == null) return {'ok': false, 'error': 'invalid url'};
        await appState.addPlexServer(
          baseUrl: uri.toString(),
          token: token,
          displayName: displayName.isEmpty ? null : displayName,
          remark: remark.isEmpty ? null : remark,
          iconUrl: iconUrl.isEmpty ? null : iconUrl,
        );
      } else {
        if (username.trim().isEmpty) {
          return {'ok': false, 'error': 'missing username'};
        }

        // Auto-complete scheme if user only typed host/path.
        final hostOrUrl =
            baseUrl.contains('://') ? baseUrl : '$scheme://$baseUrl';

        await appState.addServer(
          hostOrUrl: hostOrUrl,
          scheme: scheme,
          port: port.isEmpty ? null : port,
          serverType: type,
          username: username.trim(),
          password: password,
          displayName: displayName.isEmpty ? null : displayName,
          remark: remark.isEmpty ? null : remark,
          iconUrl: iconUrl.isEmpty ? null : iconUrl,
          customDomains: customDomains.isEmpty ? null : customDomains,
          activate: activate,
        );
      }

      if (appState.error != null) {
        return {'ok': false, 'error': appState.error};
      }

      return {'ok': true};
    } catch (e) {
      return {'ok': false, 'error': e.toString()};
    }
  }

  Map<String, dynamic> _handleParseShareText(String raw) {
    try {
      final groups = ServerShareTextParser.parse(raw);
      return {
        'ok': true,
        'groups': groups
            .map(
              (g) => {
                'password': g.password,
                'lines': g.lines
                    .map(
                      (l) => {
                        'name': l.name,
                        'url': l.url,
                        'selectedByDefault': l.selectedByDefault,
                      },
                    )
                    .toList(growable: false),
              },
            )
            .toList(growable: false),
      };
    } catch (e) {
      return {'ok': false, 'error': e.toString()};
    }
  }

  static Uri? _buildBaseUri(
    String baseUrl, {
    required String scheme,
    required String portText,
  }) {
    final raw = baseUrl.trim();
    if (raw.isEmpty) return null;

    final withScheme = raw.contains('://') ? raw : '$scheme://$raw';
    final parsed = Uri.tryParse(withScheme);
    if (parsed == null || !parsed.hasScheme || parsed.host.isEmpty) return null;

    var uri = parsed;
    final p = int.tryParse(portText.trim());
    if (p != null && p > 0 && p <= 65535) {
      uri = uri.replace(port: p);
    }
    if (uri.path.isEmpty) {
      uri = uri.replace(path: '/');
    }
    return uri.replace(query: '', fragment: '');
  }

  bool _checkToken(String token) {
    final t = _token;
    if (t == null || t.isEmpty) return false;
    return token.trim() == t;
  }

  static String _randomToken() {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    final rnd = Random.secure();
    return List.generate(20, (_) => chars[rnd.nextInt(chars.length)]).join();
  }

  static Future<List<InternetAddress>> _listIPv4() async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLoopback: false,
        includeLinkLocal: false,
      );

      final out = <InternetAddress>[];
      for (final nic in interfaces) {
        for (final addr in nic.addresses) {
          final ip = addr.address;
          if (ip.isEmpty) continue;
          if (ip.startsWith('127.')) continue;
          if (ip.startsWith('169.254.')) continue;
          out.add(addr);
        }
      }
      return out;
    } catch (_) {
      return const [];
    }
  }

  static Future<String> _readAppVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      return '${info.version}+${info.buildNumber}';
    } catch (_) {
      return '';
    }
  }
}

const String _indexHtml = r'''<!doctype html>
<html lang="zh">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>LinPlayer TV Remote</title>
  <style>
    :root { color-scheme: light dark; }
    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 16px; }
    .card { max-width: 720px; margin: 0 auto; padding: 16px; border: 1px solid rgba(127,127,127,0.35); border-radius: 12px; }
    h1 { font-size: 18px; margin: 0 0 8px 0; }
    .muted { opacity: 0.75; font-size: 12px; }
    label { display: block; margin: 10px 0 6px; font-size: 13px; }
    input, select, textarea, button { width: 100%; padding: 10px; font-size: 16px; border-radius: 10px; border: 1px solid rgba(127,127,127,0.35); box-sizing: border-box; }
    button { margin-top: 12px; cursor: pointer; }
    pre { white-space: pre-wrap; word-break: break-word; font-size: 12px; opacity: 0.85; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    @media (max-width: 640px) { .row { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <div class="card">
    <h1>LinPlayer · TV 扫码输入</h1>
    <div class="muted" id="info">正在连接…</div>

    <hr style="opacity:0.25; margin: 14px 0;" />

    <form id="addServerForm">
      <label>类型</label>
      <select id="type">
        <option value="emby">Emby</option>
        <option value="jellyfin">Jellyfin</option>
        <option value="webdav">WebDAV</option>
        <option value="plex">Plex（Token）</option>
      </select>

      <label>地址</label>
      <input id="baseUrl" placeholder="例如：https://example.com 或 192.168.1.2" autocomplete="off" />

      <div class="row">
        <div>
          <label>Scheme（仅 Emby/Jellyfin 默认值）</label>
          <select id="scheme">
            <option value="https" selected>https</option>
            <option value="http">http</option>
          </select>
        </div>
        <div>
          <label>端口（可选）</label>
          <input id="port" placeholder="例如：8096" inputmode="numeric" autocomplete="off" />
        </div>
      </div>

      <div class="row">
        <div>
          <label>账号（Emby/Jellyfin/WebDAV）</label>
          <input id="username" autocomplete="username" />
        </div>
        <div>
          <label>密码 / Token（Plex）</label>
          <input id="password" type="password" autocomplete="current-password" />
        </div>
      </div>

      <div class="row">
        <div>
          <label>显示名（可选）</label>
          <input id="displayName" autocomplete="off" />
        </div>
        <div>
          <label>备注（可选）</label>
          <input id="remark" autocomplete="off" />
        </div>
      </div>

      <label style="display:flex; align-items:center; gap:10px; margin-top: 12px;">
        <input id="activate" type="checkbox" checked style="width: 20px; height: 20px; margin: 0;" />
        添加后设为当前服务器
      </label>

      <button type="submit">添加到 TV</button>
    </form>

    <pre id="log"></pre>
  </div>

  <script>
    const params = new URLSearchParams(location.search);
    const token = params.get('token') || '';
    const logEl = document.getElementById('log');
    const infoEl = document.getElementById('info');
    const log = (s) => { logEl.textContent = (new Date().toLocaleTimeString()) + ' ' + s + '\n' + logEl.textContent; };

    const api = async (path, body) => {
      const res = await fetch(path, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });
      return await res.json();
    };

    const loadInfo = async () => {
      if (!token) {
        infoEl.textContent = '缺少 token：请重新扫码打开。';
        return;
      }
      try {
        const res = await fetch('/api/info?token=' + encodeURIComponent(token), { cache: 'no-store' });
        const data = await res.json();
        if (!data.ok) throw new Error(data.error || 'unknown');
        const app = data.app || {};
        const server = data.server || {};
        infoEl.textContent = `${app.name || 'LinPlayer'} ${app.version || ''}` +
          (server.activeServerName ? ` · 当前：${server.activeServerName}` : '');
      } catch (e) {
        infoEl.textContent = '连接失败：' + e;
      }
    };
    loadInfo();

    document.getElementById('addServerForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!token) { log('缺少 token'); return; }

      const payload = {
        token,
        type: document.getElementById('type').value,
        baseUrl: document.getElementById('baseUrl').value,
        scheme: document.getElementById('scheme').value,
        port: document.getElementById('port').value,
        username: document.getElementById('username').value,
        password: document.getElementById('password').value,
        displayName: document.getElementById('displayName').value,
        remark: document.getElementById('remark').value,
        activate: document.getElementById('activate').checked,
      };

      log('提交中…');
      try {
        const data = await api('/api/addServer', payload);
        if (!data.ok) throw new Error(data.error || 'unknown');
        log('成功：已添加');
      } catch (e) {
        log('失败：' + e);
      }
    });
  </script>
</body>
</html>''';
