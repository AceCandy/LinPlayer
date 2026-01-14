import 'dart:ui';

import 'package:flutter/material.dart';

/// A lightweight "glass" surface: gradient + optional backdrop blur.
///
/// Use [enableBlur] to disable blur on low-performance targets (e.g. Android TV).
class FrostedCard extends StatelessWidget {
  const FrostedCard({
    super.key,
    required this.child,
    this.padding,
    this.borderRadius = 18,
    this.enableBlur = true,
  });

  final Widget child;
  final EdgeInsetsGeometry? padding;
  final double borderRadius;
  final bool enableBlur;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final isDark = scheme.brightness == Brightness.dark;
    final radius = BorderRadius.circular(borderRadius);

    final a = isDark ? 0.62 : 0.78;
    final b = isDark ? 0.48 : 0.68;
    final gradient = LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [
        scheme.surfaceContainerHigh.withValues(alpha: a),
        scheme.surfaceContainerHigh.withValues(alpha: b),
      ],
    );
    final border = Border.all(
      color: scheme.outlineVariant.withValues(alpha: isDark ? 0.42 : 0.7),
    );

    Widget content = Container(
      padding: padding,
      decoration: BoxDecoration(
        gradient: gradient,
        border: border,
        borderRadius: radius,
      ),
      child: child,
    );

    if (!enableBlur) return ClipRRect(borderRadius: radius, child: content);

    return ClipRRect(
      borderRadius: radius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14),
        child: content,
      ),
    );
  }
}
