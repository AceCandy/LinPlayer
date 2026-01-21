import 'package:flutter/widgets.dart';

class UiScaleScope extends InheritedWidget {
  const UiScaleScope({
    super.key,
    required this.scale,
    required super.child,
  });

  final double scale;

  static UiScaleScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<UiScaleScope>();

  /// UI scale factor based on the current logical screen width.
  ///
  /// - Landscape tablets typically end up at `1.0`.
  /// - Phones are slightly compact by default to avoid oversized UI.
  static double autoScaleFor(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    if (width <= 0) return 1.0;

    // Phones: keep things a bit smaller so more content fits.
    if (width < 420) return 0.92;
    if (width < 520) return 0.95;
    if (width < 600) return 0.98;

    // Tablets / desktop: use the designed scale.
    return 1.0;
  }

  @override
  bool updateShouldNotify(UiScaleScope oldWidget) => scale != oldWidget.scale;
}

extension UiScaleContext on BuildContext {
  double get uiScale {
    final scoped = UiScaleScope.maybeOf(this);
    if (scoped != null) return scoped.scale;
    return UiScaleScope.autoScaleFor(this);
  }
}
