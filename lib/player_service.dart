import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

class PlayerService {
  late final Player _player;
  late final VideoController _controller;
  bool _initialized = false;

  Player get player => _player;
  VideoController get controller => _controller;
  bool get isInitialized => _initialized;

  Duration get position => _player.state.position;
  Duration get duration => _player.state.duration;
  bool get isPlaying => _player.state.playing;

  Future<void> initialize(String? path, {String? networkUrl}) async {
    _player = Player();
    _controller = VideoController(_player);
    if (networkUrl != null) {
      await _player.open(Media(networkUrl));
    } else if (path != null) {
      await _player.open(Media(path));
    } else {
      throw Exception('No media source provided');
    }
    _initialized = true;
  }

  Future<void> play() => _player.play();
  Future<void> pause() => _player.pause();
  Future<void> seek(Duration pos) => _player.seek(pos);
  Future<void> dispose() async {
    await _player.dispose();
  }
}

PlayerService getPlayerService() => PlayerService();
