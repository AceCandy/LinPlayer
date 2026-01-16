import 'package:flutter/material.dart';

extension UiScaleContext on BuildContext {
  /// UI scale factor based on the current logical screen width.
  ///
  /// - Landscape tablets typically end up around `1.0`.
  /// - Large desktop windows scale down to fit more content.
  double get uiScale {
    final width = MediaQuery.sizeOf(this).width;
    if (width <= 0) return 1.0;

    const referenceWidth = 1000.0;
    // Allow UI to scale down on large screens while avoiding overly tiny UI.
    const minScale = 0.5;
    const maxScale = 1.0;
    return (referenceWidth / width).clamp(minScale, maxScale);
  }
}
