import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../device/device_type.dart';

/// A TV-friendly slider that avoids focus being trapped on the native [Slider].
///
/// - On non-TV devices, renders a normal [Slider].
/// - On Android TV, renders a dpad-controlled slider that only consumes
///   left/right keys, so up/down can keep doing focus traversal.
class AppSlider extends StatelessWidget {
  const AppSlider({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    this.divisions,
    this.label,
    required this.onChanged,
    this.onChangeEnd,
  });

  final double value;
  final double min;
  final double max;
  final int? divisions;
  final String? label;
  final ValueChanged<double>? onChanged;
  final ValueChanged<double>? onChangeEnd;

  @override
  Widget build(BuildContext context) {
    if (!DeviceType.isTv) {
      return Slider(
        value: value,
        min: min,
        max: max,
        divisions: divisions,
        label: label,
        onChanged: onChanged,
        onChangeEnd: onChangeEnd,
      );
    }

    return _DpadSlider(
      value: value,
      min: min,
      max: max,
      divisions: divisions,
      onChanged: onChanged,
      onChangeEnd: onChangeEnd,
    );
  }
}

class _DpadSlider extends StatefulWidget {
  const _DpadSlider({
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.onChanged,
    required this.onChangeEnd,
  });

  final double value;
  final double min;
  final double max;
  final int? divisions;
  final ValueChanged<double>? onChanged;
  final ValueChanged<double>? onChangeEnd;

  @override
  State<_DpadSlider> createState() => _DpadSliderState();
}

class _DpadSliderState extends State<_DpadSlider> {
  final FocusNode _focusNode = FocusNode(debugLabel: 'AppSlider');
  Timer? _endTimer;
  bool _focused = false;
  double? _pendingEndValue;

  double get _fraction {
    final span = (widget.max - widget.min);
    if (span <= 0) return 0;
    return ((widget.value - widget.min) / span).clamp(0.0, 1.0).toDouble();
  }

  double get _step {
    final divisions = widget.divisions;
    final span = (widget.max - widget.min);
    if (span <= 0) return 0;
    if (divisions == null || divisions <= 0) return span / 100;
    return span / divisions;
  }

  void _scheduleOnChangeEnd(double value) {
    _pendingEndValue = value;
    _endTimer?.cancel();
    _endTimer = Timer(const Duration(milliseconds: 160), () {
      final v = _pendingEndValue;
      _pendingEndValue = null;
      if (v == null) return;
      widget.onChangeEnd?.call(v);
    });
  }

  void _nudge(int direction) {
    if (widget.onChanged == null) return;
    final step = _step;
    if (step <= 0) return;
    final next = (widget.value + direction * step)
        .clamp(widget.min, widget.max)
        .toDouble();
    if (next == widget.value) return;
    widget.onChanged?.call(next);
    _scheduleOnChangeEnd(next);
  }

  KeyEventResult _onKeyEvent(FocusNode node, KeyEvent event) {
    if (widget.onChanged == null) return KeyEventResult.ignored;
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.arrowLeft) {
      _nudge(-1);
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.arrowRight) {
      _nudge(1);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  void dispose() {
    _endTimer?.cancel();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final isDark = scheme.brightness == Brightness.dark;
    final enabled = widget.onChanged != null;

    final bg =
        scheme.surfaceContainerHigh.withValues(alpha: isDark ? 0.66 : 0.9);
    final borderColor = _focused ? scheme.primary : Colors.transparent;

    final trackBg = scheme.onSurface.withValues(alpha: isDark ? 0.22 : 0.16);
    final trackFg =
        enabled ? scheme.primary : scheme.onSurface.withValues(alpha: 0.28);

    return Focus(
      focusNode: _focusNode,
      onFocusChange: (v) => setState(() => _focused = v),
      onKeyEvent: _onKeyEvent,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: borderColor, width: 2),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
        child: Row(
          children: [
            Icon(Icons.remove, size: 18, color: enabled ? trackFg : trackFg),
            const SizedBox(width: 10),
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: ColoredBox(color: trackBg),
                    ),
                    FractionallySizedBox(
                      widthFactor: _fraction,
                      child: ColoredBox(color: trackFg),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 10),
            Icon(Icons.add, size: 18, color: enabled ? trackFg : trackFg),
          ],
        ),
      ),
    );
  }
}
