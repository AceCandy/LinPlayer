import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'player_screen.dart';
import 'player_screen_exo.dart';
import 'services/webdav_api.dart';
import 'services/webdav_proxy.dart';
import 'src/device/device_type.dart';
import 'src/ui/glass_blur.dart';
import 'state/app_state.dart';
import 'state/local_playback_handoff.dart';
import 'state/preferences.dart';
import 'state/server_profile.dart';

class WebDavBrowserPage extends StatefulWidget {
  const WebDavBrowserPage({
    super.key,
    required this.appState,
    required this.server,
    this.dirUri,
  });

  final AppState appState;
  final ServerProfile server;
  final Uri? dirUri;

  @override
  State<WebDavBrowserPage> createState() => _WebDavBrowserPageState();
}

class _WebDavBrowserPageState extends State<WebDavBrowserPage> {
  late final Uri _baseUri = WebDavApi.normalizeBaseUri(widget.server.baseUrl);
  late final WebDavApi _api = WebDavApi(
    baseUri: _baseUri,
    username: widget.server.username,
    password: widget.server.token,
  );

  late final Uri _dirUri = widget.dirUri ?? _baseUri;

  bool _loading = true;
  String? _error;
  List<WebDavEntry> _entries = const [];

  bool _isTv(BuildContext context) => DeviceType.isTv;

  String _title() {
    final root = _baseUri.path;
    final current = _dirUri.path;
    if (current == root || current == '$root/') {
      final name = widget.server.name.trim();
      return name.isEmpty ? 'WebDAV' : name;
    }
    final segs = _dirUri.pathSegments;
    if (segs.isNotEmpty) return segs.last;
    return 'WebDAV';
  }

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await _api.listDirectory(_dirUri);
      if (!mounted) return;
      setState(() {
        _entries = list;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  String _fmtSize(int? bytes) {
    final b = bytes ?? 0;
    if (b <= 0) return '';
    const kb = 1024;
    const mb = 1024 * 1024;
    const gb = 1024 * 1024 * 1024;
    if (b >= gb) return '${(b / gb).toStringAsFixed(2)} GB';
    if (b >= mb) return '${(b / mb).toStringAsFixed(2)} MB';
    if (b >= kb) return '${(b / kb).toStringAsFixed(1)} KB';
    return '$b B';
  }

  Future<void> _openEntry(WebDavEntry entry) async {
    if (entry.isDirectory) {
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => WebDavBrowserPage(
            appState: widget.appState,
            server: widget.server,
            dirUri: entry.uri,
          ),
        ),
      );
      return;
    }

    final local = await WebDavProxyServer.instance.registerFile(
      remoteUri: entry.uri,
      username: widget.server.username,
      password: widget.server.token,
      fileName: entry.name,
    );

    widget.appState.setLocalPlaybackHandoff(
      LocalPlaybackHandoff(
        playlist: [
          LocalPlaybackItem(name: entry.name, path: local.toString()),
        ],
        index: 0,
        position: Duration.zero,
        wasPlaying: true,
      ),
    );

    if (!mounted) return;

    final useExoCore = !kIsWeb &&
        defaultTargetPlatform == TargetPlatform.android &&
        widget.appState.playerCore == PlayerCore.exo;

    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => useExoCore
            ? ExoPlayerScreen(appState: widget.appState)
            : PlayerScreen(appState: widget.appState),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isTv = _isTv(context);
    final enableBlur = !isTv && widget.appState.enableBlurEffects;

    return Scaffold(
      appBar: GlassAppBar(
        enableBlur: enableBlur,
        child: AppBar(
          title: Text(_title()),
          actions: [
            IconButton(
              tooltip: '切换服务器',
              onPressed: () => widget.appState.leaveServer(),
              icon: const Icon(Icons.logout),
            ),
            IconButton(
              tooltip: '刷新',
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : (_error != null)
              ? Center(child: Text(_error!, textAlign: TextAlign.center))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.separated(
                    padding: const EdgeInsets.all(12),
                    itemCount: _entries.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 6),
                    itemBuilder: (context, index) {
                      final e = _entries[index];
                      final subtitle = e.isDirectory
                          ? '文件夹'
                          : [
                              _fmtSize(e.contentLength),
                              if (e.lastModified != null)
                                e.lastModified!.toString().split('.').first,
                            ].where((s) => s.trim().isNotEmpty).join(' · ');
                      return Card(
                        clipBehavior: Clip.antiAlias,
                        child: ListTile(
                          leading:
                              Icon(e.isDirectory ? Icons.folder : Icons.movie),
                          title: Text(e.name),
                          subtitle: subtitle.isEmpty ? null : Text(subtitle),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => _openEntry(e),
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}
