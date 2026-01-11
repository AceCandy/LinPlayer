import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:media_kit_video/media_kit_video.dart';

import 'player_service.dart';

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key});

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  final PlayerService _playerService = getPlayerService();
  final List<PlatformFile> _playlist = [];
  int _currentlyPlayingIndex = -1;
  StreamSubscription<Duration>? _posSub;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  String? _playError;

  @override
  void dispose() {
    _posSub?.cancel();
    _playerService.dispose();
    super.dispose();
  }

  Future<void> _pickFile() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.video,
      allowMultiple: true,
      withData: kIsWeb,
    );
    if (result != null) {
      setState(() => _playlist.addAll(result.files));
      if (_currentlyPlayingIndex == -1 && _playlist.isNotEmpty) {
        _playFile(_playlist.first, 0);
      }
    }
  }

  Future<void> _playFile(PlatformFile file, int index) async {
    setState(() {
      _currentlyPlayingIndex = index;
      _playError = null;
    });
    try {
      await _playerService.dispose();
    } catch (_) {}

    try {
      if (kIsWeb) {
        await _playerService.initialize(null, networkUrl: file.path ?? '');
      } else {
        await _playerService.initialize(file.path);
      }
      _duration = _playerService.duration;
      _posSub?.cancel();
      _posSub = _playerService.player.stream.position.listen((d) {
        setState(() => _position = d);
      });
    } catch (e) {
      setState(() => _playError = e.toString());
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final currentFileName =
        _currentlyPlayingIndex != -1 ? _playlist[_currentlyPlayingIndex].name : 'LinPlayer';

    return Scaffold(
      appBar: AppBar(
        title: Text(currentFileName),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.folder_open),
            onPressed: _pickFile,
          ),
        ],
      ),
      body: Column(
        children: [
          AspectRatio(
            aspectRatio: 16 / 9,
            child: Container(
              color: Colors.black,
              child: _playerService.isInitialized
                  ? Video(controller: _playerService.controller)
                  : _playError != null
                      ? Center(
                          child: Text(
                            '播放失败：$_playError',
                            style: const TextStyle(color: Colors.redAccent),
                          ),
                        )
                      : const Center(child: Text('选择一个视频播放')),
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              IconButton(
                icon: const Icon(Icons.replay_10),
                onPressed: !_playerService.isInitialized
                    ? null
                    : () {
                        final newPos = _position - const Duration(seconds: 10);
                        _playerService.seek(newPos);
                      },
              ),
              IconButton(
                icon: Icon(_playerService.isPlaying ? Icons.pause : Icons.play_arrow),
                onPressed: !_playerService.isInitialized
                    ? null
                    : () {
                        setState(() {
                          _playerService.isPlaying ? _playerService.pause() : _playerService.play();
                        });
                      },
              ),
              IconButton(
                icon: const Icon(Icons.forward_10),
                onPressed: !_playerService.isInitialized
                    ? null
                    : () {
                        final newPos = _position + const Duration(seconds: 10);
                        _playerService.seek(newPos);
                      },
              ),
            ],
          ),
          if (_playerService.isInitialized)
            Slider(
              value: _position.inMilliseconds.toDouble().clamp(0, _duration.inMilliseconds + 1),
              max: (_playerService.duration.inMilliseconds + 1).toDouble(),
              onChanged: (v) => _playerService.seek(Duration(milliseconds: v.toInt())),
            ),
          const Padding(
            padding: EdgeInsets.all(8.0),
            child: Text(
              '播放列表',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: _playlist.length,
              itemBuilder: (context, index) {
                final file = _playlist[index];
                final isPlaying = index == _currentlyPlayingIndex;
                return ListTile(
                  leading: Icon(isPlaying ? Icons.play_circle_filled : Icons.movie),
                  title: Text(
                    file.name,
                    style: TextStyle(
                      color: isPlaying ? Colors.blue : null,
                    ),
                  ),
                  onTap: () => _playFile(file, index),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
