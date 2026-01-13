import 'package:media_kit/media_kit.dart';

Future<void> main() async {
  MediaKit.ensureInitialized();
  final player = Player();
  player.stream.error.listen((e) => print('player error: $e'));
  await player.open(Media('https://samplelib.com/lib/preview/mp4/sample-5s.mp4'));
  await Future.delayed(const Duration(seconds:5));
  await player.dispose();
}
