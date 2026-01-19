import 'dart:io';

class StreamCache {
  static const _dirName = 'linplayer_stream_cache_v1';

  static Directory get directory =>
      Directory('${Directory.systemTemp.path}${Platform.pathSeparator}$_dirName');

  static Future<Directory> ensureDirectory() async {
    final dir = directory;
    if (await dir.exists()) return dir;
    return dir.create(recursive: true);
  }

  static Future<void> clear() async {
    final dir = directory;
    if (!await dir.exists()) return;
    try {
      await dir.delete(recursive: true);
    } catch (_) {}
    try {
      await dir.create(recursive: true);
    } catch (_) {}
  }
}

