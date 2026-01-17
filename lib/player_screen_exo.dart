import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import 'state/app_state.dart';

class ExoPlayerScreen extends StatefulWidget {
  const ExoPlayerScreen({super.key, required this.appState});

  final AppState appState;

  @override
  State<ExoPlayerScreen> createState() => _ExoPlayerScreenState();
}

class _ExoPlayerScreenState extends State<ExoPlayerScreen> {
  final List<PlatformFile> _playlist = [];
  int _currentIndex = -1;

  VideoPlayerController? _controller;
  Timer? _uiTimer;

  bool _buffering = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  String? _playError;
  DateTime? _lastUiTickAt;

  bool get _isAndroid =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  @override
  void dispose() {
    _uiTimer?.cancel();
    _uiTimer = null;
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

  Future<void> _pickFiles() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.video,
      allowMultiple: true,
      withData: false,
    );
    if (!mounted) return;
    if (result == null) return;

    setState(() => _playlist.addAll(result.files));
    if (_currentIndex == -1 && _playlist.isNotEmpty) {
      // ignore: unawaited_futures
      _playFile(_playlist.first, 0);
    }
  }

  Future<void> _playFile(PlatformFile file, int index) async {
    final path = (file.path ?? '').trim();
    if (path.isEmpty) {
      setState(() => _playError = '无法读取文件路径');
      return;
    }

    setState(() {
      _currentIndex = index;
      _playError = null;
      _buffering = false;
      _position = Duration.zero;
      _duration = Duration.zero;
    });

    _uiTimer?.cancel();
    _uiTimer = null;

    final prev = _controller;
    _controller = null;
    if (prev != null) {
      await prev.dispose();
    }

    try {
      final uri = Uri.tryParse(path);
      final isUri = uri != null && uri.scheme.isNotEmpty;
      final controller = isUri
          ? VideoPlayerController.networkUrl(uri)
          : VideoPlayerController.file(File(path));
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
        final now = DateTime.now();
        final shouldRebuild = _lastUiTickAt == null ||
            now.difference(_lastUiTickAt!) >= const Duration(milliseconds: 250);
        if (shouldRebuild) {
          _lastUiTickAt = now;
          setState(() {});
        }
      });

      if (mounted) setState(() {});
    } catch (e) {
      setState(() => _playError = e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final fileName = _currentIndex >= 0 && _currentIndex < _playlist.length
        ? _playlist[_currentIndex].name
        : '本地播放（Exo）';

    if (!_isAndroid) {
      return Scaffold(
        appBar: AppBar(title: const Text('本地播放（Exo）'), centerTitle: true),
        body: const Center(child: Text('Exo 内核仅支持 Android')),
      );
    }

    final controller = _controller;
    final isReady = controller != null && controller.value.isInitialized;

    return Scaffold(
      appBar: AppBar(
        title: Text(fileName),
        centerTitle: true,
        actions: [
          IconButton(
            tooltip: '选择文件',
            icon: const Icon(Icons.folder_open),
            onPressed: _pickFiles,
          ),
          IconButton(
            tooltip: '选集',
            icon: const Icon(Icons.playlist_play),
            onPressed: _playlist.isEmpty
                ? null
                : () {
                    showModalBottomSheet(
                      context: context,
                      builder: (ctx) => ListView.builder(
                        itemCount: _playlist.length,
                        itemBuilder: (_, i) {
                          final f = _playlist[i];
                          return ListTile(
                            title: Text(f.name),
                            trailing: i == _currentIndex
                                ? const Icon(Icons.play_arrow)
                                : null,
                            onTap: () {
                              Navigator.of(ctx).pop();
                              // ignore: unawaited_futures
                              _playFile(f, i);
                            },
                          );
                        },
                      ),
                    );
                  },
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Expanded(
              child: Stack(
                children: [
                  Center(
                    child: isReady
                        ? AspectRatio(
                            aspectRatio: controller.value.aspectRatio == 0
                                ? 16 / 9
                                : controller.value.aspectRatio,
                            child: VideoPlayer(controller),
                          )
                        : const Text('请选择要播放的视频'),
                  ),
                  if (_buffering)
                    const Positioned.fill(
                      child: ColoredBox(
                        color: Colors.black26,
                        child: Center(child: CircularProgressIndicator()),
                      ),
                    ),
                  if (_playError != null)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: Padding(
                        padding: const EdgeInsets.all(8),
                        child: Text(
                          _playError!,
                          style: const TextStyle(color: Colors.redAccent),
                        ),
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
                        ? (v) => setState(
                              () =>
                                  _position = Duration(milliseconds: v.round()),
                            )
                        : null,
                    onChangeEnd: isReady
                        ? (v) async {
                            await controller.seekTo(
                              Duration(milliseconds: v.round()),
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
                            target < Duration.zero ? Duration.zero : target,
                          );
                        }
                      : null,
                ),
                IconButton(
                  tooltip: controller?.value.isPlaying == true ? '暂停' : '播放',
                  icon: Icon(controller?.value.isPlaying == true
                      ? Icons.pause_circle
                      : Icons.play_circle),
                  iconSize: 44,
                  onPressed: isReady
                      ? () async {
                          if (controller.value.isPlaying) {
                            await controller.pause();
                          } else {
                            await controller.play();
                          }
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
