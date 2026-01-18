import 'dart:io';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/services.dart' show rootBundle;
import 'package:media_kit/media_kit.dart';

import '../../state/anime4k_preferences.dart';

class Anime4k {
  Anime4k._();

  static const String _assetBase = 'assets/shaders/anime4k';

  static const String _clampHighlights =
      '$_assetBase/Anime4K_Clamp_Highlights.glsl';
  static const String _restoreM = '$_assetBase/Anime4K_Restore_CNN_M.glsl';
  static const String _restoreM2 = '$_assetBase/Anime4K_Restore_CNN_M_2.glsl';
  static const String _restoreSoftM =
      '$_assetBase/Anime4K_Restore_CNN_Soft_M.glsl';
  static const String _restoreSoftM2 =
      '$_assetBase/Anime4K_Restore_CNN_Soft_M_2.glsl';
  static const String _upscaleM = '$_assetBase/Anime4K_Upscale_CNN_x2_M.glsl';
  static const String _upscaleM2 = '$_assetBase/Anime4K_Upscale_CNN_x2_M_2.glsl';
  static const String _upscaleDenoiseM =
      '$_assetBase/Anime4K_Upscale_Denoise_CNN_x2_M.glsl';
  static const String _autoDownscalePreX2 =
      '$_assetBase/Anime4K_AutoDownscalePre_x2.glsl';
  static const String _autoDownscalePreX4 =
      '$_assetBase/Anime4K_AutoDownscalePre_x4.glsl';

  static const List<String> _allAssets = [
    _clampHighlights,
    _restoreM,
    _restoreM2,
    _restoreSoftM,
    _restoreSoftM2,
    _upscaleM,
    _upscaleM2,
    _upscaleDenoiseM,
    _autoDownscalePreX2,
    _autoDownscalePreX4,
  ];

  static String? _extractedDirPath;
  static Future<void>? _extractFuture;

  static Future<Directory> _ensureExtractDir() async {
    final existing = _extractedDirPath;
    if (existing != null && existing.trim().isNotEmpty) {
      return Directory(existing);
    }
    final sep = Platform.pathSeparator;
    final dir = Directory('${Directory.systemTemp.path}${sep}linplayer${sep}anime4k');
    await dir.create(recursive: true);
    _extractedDirPath = dir.path;
    return dir;
  }

  static Future<void> _ensureExtracted() async {
    if (_extractFuture != null) return _extractFuture!;
    _extractFuture = () async {
      final dir = await _ensureExtractDir();
      final sep = Platform.pathSeparator;
      for (final asset in _allAssets) {
        final name = asset.split('/').last;
        final out = File('${dir.path}$sep$name');
        if (await out.exists()) {
          try {
            if (await out.length() > 0) continue;
          } catch (_) {}
        }
        final data = await rootBundle.load(asset);
        final bytes = data.buffer.asUint8List(
          data.offsetInBytes,
          data.lengthInBytes,
        );
        await out.writeAsBytes(bytes, flush: true);
      }
    }();
    try {
      await _extractFuture;
    } catch (_) {
      _extractFuture = null;
      rethrow;
    }
  }

  static List<String> _assetChain(Anime4kPreset preset) {
    switch (preset) {
      case Anime4kPreset.off:
        return const [];
      case Anime4kPreset.a:
        return const [
          _clampHighlights,
          _restoreM,
          _upscaleM,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _upscaleM2,
        ];
      case Anime4kPreset.b:
        return const [
          _clampHighlights,
          _restoreSoftM,
          _upscaleM,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _upscaleM2,
        ];
      case Anime4kPreset.c:
        return const [
          _clampHighlights,
          _upscaleDenoiseM,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _upscaleM2,
        ];
      case Anime4kPreset.aa:
        return const [
          _clampHighlights,
          _restoreM,
          _upscaleM,
          _restoreM2,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _upscaleM2,
        ];
      case Anime4kPreset.bb:
        return const [
          _clampHighlights,
          _restoreSoftM,
          _upscaleM,
          _restoreSoftM2,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _upscaleM2,
        ];
      case Anime4kPreset.ca:
        return const [
          _clampHighlights,
          _upscaleDenoiseM,
          _autoDownscalePreX2,
          _autoDownscalePreX4,
          _restoreM,
          _upscaleM2,
        ];
    }
  }

  static Future<void> clear(Player player) async {
    if (kIsWeb) return;
    await (player.platform as dynamic).command(
      const ['change-list', 'glsl-shaders', 'clr'],
    );
  }

  static Future<void> apply(Player player, Anime4kPreset preset) async {
    if (kIsWeb) return;
    await clear(player);
    if (preset.isOff) return;

    await _ensureExtracted();
    final dir = await _ensureExtractDir();
    final sep = Platform.pathSeparator;
    final chain = _assetChain(preset);
    for (final asset in chain) {
      final name = asset.split('/').last;
      final path = '${dir.path}$sep$name';
      await (player.platform as dynamic).command(
        ['change-list', 'glsl-shaders', 'append', path],
      );
    }
  }
}
