import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import 'services/emby_api.dart';
import 'state/app_state.dart';

class ExoPlayNetworkPage extends StatefulWidget {
  const ExoPlayNetworkPage({
    super.key,
    required this.title,
    required this.itemId,
    required this.appState,
    this.isTv = false,
    this.mediaSourceId,
    this.audioStreamIndex,
    this.subtitleStreamIndex,
  });

  final String title;
  final String itemId;
  final AppState appState;
  final bool isTv;
  final String? mediaSourceId;
  final int? audioStreamIndex;
  final int? subtitleStreamIndex; // Emby MediaStream Index, -1 = off

  @override
  State<ExoPlayNetworkPage> createState() => _ExoPlayNetworkPageState();
}

class _ExoPlayNetworkPageState extends State<ExoPlayNetworkPage> {
  EmbyApi? _embyApi;
  VideoPlayerController? _controller;
  Timer? _uiTimer;

  bool _loading = true;
  String? _playError;
  bool _buffering = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  DateTime? _lastUiTickAt;

  String? _playSessionId;
  String? _mediaSourceId;
  DateTime? _lastProgressReportAt;
  bool _lastProgressReportPaused = false;
  bool _reportedStart = false;
  bool _reportedStop = false;
  bool _progressReportInFlight = false;

  bool get _isAndroid =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  bool get _isPlaying => _controller?.value.isPlaying ?? false;

  @override
  void initState() {
    super.initState();
    final baseUrl = widget.appState.baseUrl;
    if (baseUrl != null && baseUrl.trim().isNotEmpty) {
      _embyApi = EmbyApi(hostOrUrl: baseUrl, preferredScheme: 'https');
    }
    // ignore: unawaited_futures
    _enterImmersiveMode();
    _init();
  }

  @override
  void dispose() {
    _uiTimer?.cancel();
    _uiTimer = null;
    // ignore: unawaited_futures
    _reportPlaybackStoppedBestEffort();
    // ignore: unawaited_futures
    _exitImmersiveMode();
    // ignore: unawaited_futures
    _controller?.dispose();
    _controller = null;
    super.dispose();
  }

  String _fmt(Duration d) {
    String two(int v) => v.toString().padLeft(2, '0');
    final h = d.inHours;
    final m = d.inMinutes.remainder(60);
    final s = d.inSeconds.remainder(60);
    return h > 0 ? '${two(h)}:${two(m)}:${two(s)}' : '${two(m)}:${two(s)}';
  }

  Future<void> _init() async {
    _uiTimer?.cancel();
    _uiTimer = null;
    _playError = null;
    _loading = true;

    _reportedStart = false;
    _reportedStop = false;
    _progressReportInFlight = false;
    _lastProgressReportAt = null;
    _lastProgressReportPaused = false;

    _playSessionId = null;
    _mediaSourceId = null;

    final prev = _controller;
    _controller = null;
    if (prev != null) {
      await prev.dispose();
    }

    if (!mounted) return;
    setState(() {});

    try {
      if (!_isAndroid) {
        throw Exception('Exo 内核仅支持 Android');
      }
      final streamUrl = await _buildStreamUrl();
      final controller = VideoPlayerController.networkUrl(
        Uri.parse(streamUrl),
        httpHeaders: {
          'X-Emby-Token': widget.appState.token!,
          'X-Emby-Authorization':
              'MediaBrowser Client="LinPlayer", Device="Flutter", DeviceId="${widget.appState.deviceId}", Version="1.0.0"',
        },
      );
      _controller = controller;
      await controller.initialize();
      await controller.play();

      _uiTimer = Timer.periodic(const Duration(milliseconds: 250), (_) {
        final c = _controller;
        if (!mounted || c == null) return;
        final v = c.value;
        _buffering = v.isBuffering;
        _position = v.position;
        _duration = v.duration;

        _maybeReportPlaybackProgress(_position);

        if (!_reportedStop &&
            _duration > Duration.zero &&
            !_buffering &&
            !v.isPlaying &&
            _position >= _duration - const Duration(milliseconds: 200)) {
          // ignore: unawaited_futures
          _reportPlaybackStoppedBestEffort(completed: true);
        }

        final now = DateTime.now();
        final shouldRebuild = _lastUiTickAt == null ||
            now.difference(_lastUiTickAt!) >= const Duration(milliseconds: 250);
        if (shouldRebuild) {
          _lastUiTickAt = now;
          setState(() {});
        }
      });

      // ignore: unawaited_futures
      _reportPlaybackStartBestEffort();
    } catch (e) {
      _playError = e.toString();
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  Future<String> _buildStreamUrl() async {
    final base = widget.appState.baseUrl!;
    final token = widget.appState.token!;
    final userId = widget.appState.userId!;
    _playSessionId = null;
    _mediaSourceId = null;

    String applyQueryPrefs(String url) {
      final uri = Uri.parse(url);
      final params = Map<String, String>.from(uri.queryParameters);
      if (!params.containsKey('api_key')) params['api_key'] = token;
      if (widget.audioStreamIndex != null) {
        params['AudioStreamIndex'] = widget.audioStreamIndex.toString();
      }
      if (widget.subtitleStreamIndex != null &&
          widget.subtitleStreamIndex! >= 0) {
        params['SubtitleStreamIndex'] = widget.subtitleStreamIndex.toString();
      }
      return uri.replace(queryParameters: params).toString();
    }

    String resolve(String candidate) {
      final resolved = Uri.parse(base).resolve(candidate).toString();
      return applyQueryPrefs(resolved);
    }

    try {
      final api = _embyApi ??
          EmbyApi(
              hostOrUrl: widget.appState.baseUrl!, preferredScheme: 'https');
      final info = await api.fetchPlaybackInfo(
        token: token,
        baseUrl: base,
        userId: userId,
        deviceId: widget.appState.deviceId,
        itemId: widget.itemId,
      );
      final sources = info.mediaSources.cast<Map<String, dynamic>>();
      Map<String, dynamic>? ms;
      if (sources.isNotEmpty) {
        final selectedId = widget.mediaSourceId;
        if (selectedId != null && selectedId.isNotEmpty) {
          ms = sources.firstWhere(
            (s) => (s['Id'] as String? ?? '') == selectedId,
            orElse: () => sources.first,
          );
        } else {
          ms = sources.first;
        }
      }
      _playSessionId = info.playSessionId;
      _mediaSourceId = (ms?['Id'] as String?) ?? info.mediaSourceId;
      final directStreamUrl = ms?['DirectStreamUrl'] as String?;
      if (directStreamUrl != null && directStreamUrl.isNotEmpty) {
        return resolve(directStreamUrl);
      }
      final mediaSourceId = (ms?['Id'] as String?) ?? info.mediaSourceId;
      return applyQueryPrefs(
        '$base/emby/Videos/${widget.itemId}/stream?static=true&MediaSourceId=$mediaSourceId'
        '&PlaySessionId=${info.playSessionId}&UserId=$userId&DeviceId=${widget.appState.deviceId}'
        '&api_key=$token',
      );
    } catch (_) {
      return applyQueryPrefs(
        '$base/emby/Videos/${widget.itemId}/stream?static=true&UserId=$userId'
        '&DeviceId=${widget.appState.deviceId}&api_key=$token',
      );
    }
  }

  int _toTicks(Duration d) => d.inMicroseconds * 10;

  Future<void> _reportPlaybackStartBestEffort() async {
    if (_reportedStart || _reportedStop) return;
    final api = _embyApi;
    if (api == null) return;
    final baseUrl = widget.appState.baseUrl;
    final token = widget.appState.token;
    final userId = widget.appState.userId;
    if (baseUrl == null || baseUrl.isEmpty || token == null || token.isEmpty) {
      return;
    }

    _reportedStart = true;
    final posTicks = _toTicks(_position);
    final paused = !_isPlaying;
    try {
      final ps = _playSessionId;
      final ms = _mediaSourceId;
      if (ps != null && ps.isNotEmpty && ms != null && ms.isNotEmpty) {
        await api.reportPlaybackStart(
          token: token,
          baseUrl: baseUrl,
          deviceId: widget.appState.deviceId,
          itemId: widget.itemId,
          mediaSourceId: ms,
          playSessionId: ps,
          positionTicks: posTicks,
          isPaused: paused,
          userId: userId,
        );
      }
    } catch (_) {}
  }

  void _maybeReportPlaybackProgress(Duration position, {bool force = false}) {
    if (_reportedStop) return;
    if (_progressReportInFlight) return;
    final api = _embyApi;
    if (api == null) return;
    final baseUrl = widget.appState.baseUrl;
    final token = widget.appState.token;
    final userId = widget.appState.userId;
    if (baseUrl == null || baseUrl.isEmpty || token == null || token.isEmpty) {
      return;
    }

    final now = DateTime.now();
    final paused = !_isPlaying;

    final due = _lastProgressReportAt == null ||
        now.difference(_lastProgressReportAt!) >= const Duration(seconds: 15);
    final pausedChanged = paused != _lastProgressReportPaused &&
        (_lastProgressReportAt == null ||
            now.difference(_lastProgressReportAt!) >=
                const Duration(seconds: 1));
    final shouldReport = force || due || pausedChanged;
    if (!shouldReport) return;

    _lastProgressReportAt = now;
    _lastProgressReportPaused = paused;
    _progressReportInFlight = true;

    final ticks = _toTicks(position);

    // ignore: unawaited_futures
    () async {
      try {
        final ps = _playSessionId;
        final ms = _mediaSourceId;
        if (ps != null && ps.isNotEmpty && ms != null && ms.isNotEmpty) {
          await api.reportPlaybackProgress(
            token: token,
            baseUrl: baseUrl,
            deviceId: widget.appState.deviceId,
            itemId: widget.itemId,
            mediaSourceId: ms,
            playSessionId: ps,
            positionTicks: ticks,
            isPaused: paused,
            userId: userId,
          );
        } else if (userId != null && userId.isNotEmpty) {
          await api.updatePlaybackPosition(
            token: token,
            baseUrl: baseUrl,
            userId: userId,
            itemId: widget.itemId,
            positionTicks: ticks,
          );
        }
      } finally {
        _progressReportInFlight = false;
      }
    }();
  }

  Future<void> _reportPlaybackStoppedBestEffort(
      {bool completed = false}) async {
    if (_reportedStop) return;
    _reportedStop = true;

    final api = _embyApi;
    if (api == null) return;
    final baseUrl = widget.appState.baseUrl;
    final token = widget.appState.token;
    final userId = widget.appState.userId;
    if (baseUrl == null || baseUrl.isEmpty || token == null || token.isEmpty) {
      return;
    }

    final pos = _position;
    final dur = _duration;
    final played = completed ||
        (dur > Duration.zero && pos >= dur - const Duration(seconds: 20));
    final ticks = _toTicks(pos);

    try {
      final ps = _playSessionId;
      final ms = _mediaSourceId;
      if (ps != null && ps.isNotEmpty && ms != null && ms.isNotEmpty) {
        await api.reportPlaybackStopped(
          token: token,
          baseUrl: baseUrl,
          deviceId: widget.appState.deviceId,
          itemId: widget.itemId,
          mediaSourceId: ms,
          playSessionId: ps,
          positionTicks: ticks,
          userId: userId,
        );
      }
    } catch (_) {}

    try {
      if (userId != null && userId.isNotEmpty) {
        await api.updatePlaybackPosition(
          token: token,
          baseUrl: baseUrl,
          userId: userId,
          itemId: widget.itemId,
          positionTicks: ticks,
          played: played,
        );
      }
    } catch (_) {}
  }

  bool get _shouldControlSystemUi {
    if (kIsWeb) return false;
    if (widget.isTv) return false;
    return Platform.isAndroid || Platform.isIOS;
  }

  Future<void> _enterImmersiveMode() async {
    if (!_shouldControlSystemUi) return;
    try {
      await SystemChrome.setEnabledSystemUIMode(
        SystemUiMode.immersiveSticky,
        overlays: const [],
      );
    } catch (_) {}
  }

  Future<void> _exitImmersiveMode() async {
    if (!_shouldControlSystemUi) return;
    try {
      await SystemChrome.setEnabledSystemUIMode(
        SystemUiMode.manual,
        overlays: SystemUiOverlay.values,
      );
    } catch (_) {}
    try {
      await SystemChrome.setPreferredOrientations(const []);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    final isReady = controller != null && controller.value.isInitialized;

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
        centerTitle: true,
        actions: [
          IconButton(
            tooltip: '重新加载',
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _init,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _playError != null
              ? Center(child: Text(_playError!))
              : Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    children: [
                      Expanded(
                        child: Stack(
                          children: [
                            Center(
                              child: isReady
                                  ? AspectRatio(
                                      aspectRatio:
                                          controller.value.aspectRatio == 0
                                              ? 16 / 9
                                              : controller.value.aspectRatio,
                                      child: VideoPlayer(controller),
                                    )
                                  : const SizedBox.shrink(),
                            ),
                            if (_buffering)
                              const Positioned.fill(
                                child: ColoredBox(
                                  color: Colors.black26,
                                  child: Center(
                                      child: CircularProgressIndicator()),
                                ),
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Text(_fmt(_position)),
                          Expanded(
                            child: Slider(
                              value: _duration.inMilliseconds == 0
                                  ? 0
                                  : _position.inMilliseconds
                                      .clamp(0, _duration.inMilliseconds)
                                      .toDouble(),
                              min: 0,
                              max: _duration.inMilliseconds
                                  .toDouble()
                                  .clamp(1, double.infinity),
                              onChanged: isReady
                                  ? (v) => setState(() => _position =
                                      Duration(milliseconds: v.round()))
                                  : null,
                              onChangeEnd: isReady
                                  ? (v) async {
                                      await controller.seekTo(
                                        Duration(milliseconds: v.round()),
                                      );
                                      _maybeReportPlaybackProgress(
                                        Duration(milliseconds: v.round()),
                                        force: true,
                                      );
                                    }
                                  : null,
                            ),
                          ),
                          Text(_fmt(_duration)),
                        ],
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          IconButton(
                            tooltip: '后退 10 秒',
                            icon: const Icon(Icons.replay_10),
                            onPressed: isReady
                                ? () async {
                                    final target =
                                        _position - const Duration(seconds: 10);
                                    await controller.seekTo(
                                      target < Duration.zero
                                          ? Duration.zero
                                          : target,
                                    );
                                    _maybeReportPlaybackProgress(
                                      controller.value.position,
                                      force: true,
                                    );
                                  }
                                : null,
                          ),
                          IconButton(
                            tooltip: _isPlaying ? '暂停' : '播放',
                            icon: Icon(_isPlaying
                                ? Icons.pause_circle
                                : Icons.play_circle),
                            iconSize: 44,
                            onPressed: isReady
                                ? () async {
                                    if (_isPlaying) {
                                      await controller.pause();
                                    } else {
                                      await controller.play();
                                    }
                                    _maybeReportPlaybackProgress(
                                      controller.value.position,
                                      force: true,
                                    );
                                    if (mounted) setState(() {});
                                  }
                                : null,
                          ),
                          IconButton(
                            tooltip: '前进 10 秒',
                            icon: const Icon(Icons.forward_10),
                            onPressed: isReady
                                ? () async {
                                    final target =
                                        _position + const Duration(seconds: 10);
                                    await controller.seekTo(
                                      target > _duration ? _duration : target,
                                    );
                                    _maybeReportPlaybackProgress(
                                      controller.value.position,
                                      force: true,
                                    );
                                  }
                                : null,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Exo 内核为 Android 兼容性模式：部分高级功能（音轨/字幕切换、弹幕等）暂不可用。',
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                ),
    );
  }
}
