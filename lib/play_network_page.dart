import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import 'player_service.dart';

class PlayNetworkPage extends StatefulWidget {
  const PlayNetworkPage({super.key, required this.title, required this.streamUrl});

  final String title;
  final String streamUrl;

  @override
  State<PlayNetworkPage> createState() => _PlayNetworkPageState();
}

class _PlayNetworkPageState extends State<PlayNetworkPage> {
  final PlayerService _playerService = getPlayerService();
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      await _playerService.initialize(null, networkUrl: widget.streamUrl);
      _playerService.controller?.addListener(() {
        if (mounted) setState(() {});
      });
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  void dispose() {
    _playerService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final initialized = _playerService.isInitialized;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
      ),
      body: Column(
        children: [
          AspectRatio(
            aspectRatio: 16 / 9,
            child: Container(
              color: Colors.black,
              child: initialized
                  ? VideoPlayer(_playerService.controller!)
                  : const Center(child: CircularProgressIndicator()),
            ),
          ),
          if (_loading) const LinearProgressIndicator(),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              IconButton(
                icon: Icon(_playerService.isPlaying ? Icons.pause : Icons.play_arrow),
                onPressed: !initialized
                    ? null
                    : () {
                        setState(() {
                          _playerService.isPlaying ? _playerService.pause() : _playerService.play();
                        });
                      },
              ),
            ],
          ),
          if (initialized)
            VideoProgressIndicator(
              _playerService.controller!,
              allowScrubbing: true,
            ),
        ],
      ),
    );
  }
}
