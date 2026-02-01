import 'package:flutter/foundation.dart';
import 'package:flutter_displaymode/flutter_displaymode.dart';

/// Best-effort utilities for enabling high refresh rate on supported Android devices.
///
/// Some OEMs default to 60Hz for apps unless a preferred display mode is requested.
class HighRefreshRate {
  HighRefreshRate._();

  static bool _appliedOnce = false;

  static Future<void> apply({bool force = false}) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    if (_appliedOnce && !force) return;
    _appliedOnce = true;

    try {
      await FlutterDisplayMode.setHighRefreshRate();
      return;
    } catch (_) {
      // Fallback: pick the highest refresh rate mode manually.
    }

    try {
      final modes = await FlutterDisplayMode.supported;
      if (modes.isEmpty) return;

      modes.sort((a, b) {
        final refresh = b.refreshRate.compareTo(a.refreshRate);
        if (refresh != 0) return refresh;
        final pixelsA = a.width * a.height;
        final pixelsB = b.width * b.height;
        return pixelsB.compareTo(pixelsA);
      });

      await FlutterDisplayMode.setPreferredMode(modes.first);
    } catch (_) {
      // Ignore: high refresh is best-effort.
    }
  }
}
