import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class DesktopWindow {
  DesktopWindow._();

  static const MethodChannel _channel = MethodChannel('linplayer/window');

  static bool get _supported => !kIsWeb && Platform.isWindows;

  static Future<void> setBorderlessFullscreen(bool enabled) async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<void>('setBorderlessFullscreen', enabled);
    } catch (_) {
      // Best-effort: ignore if the channel isn't available.
    }
  }
}

