import 'dart:ui';

import 'package:flutter/material.dart';

import '../../state/preferences.dart';
import 'app_style.dart';

bool _usesGlassSurfaces(UiTemplate template) {
  switch (template) {
    case UiTemplate.candyGlass:
    case UiTemplate.stickerJournal:
    case UiTemplate.neonHud:
    case UiTemplate.washiWatercolor:
      return true;
    case UiTemplate.minimalCovers:
    case UiTemplate.pixelArcade:
    case UiTemplate.mangaStoryboard:
    case UiTemplate.proTool:
      return false;
  }
}

BorderRadius _borderRadiusOf(ShapeBorder? shape, TextDirection textDirection) {
  if (shape is RoundedRectangleBorder) {
    final resolved = shape.borderRadius.resolve(textDirection);
    if (resolved is BorderRadius) return resolved;
    return resolved as BorderRadius;
  }
  return BorderRadius.zero;
}

class GlassAppBar extends StatelessWidget implements PreferredSizeWidget {
  const GlassAppBar({
    super.key,
    required this.child,
    this.enableBlur = true,
    this.sigma = 18,
  });

  final PreferredSizeWidget child;
  final bool enableBlur;
  final double sigma;

  @override
  Size get preferredSize => child.preferredSize;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).extension<AppStyle>() ?? const AppStyle();
    final shouldBlur = enableBlur && _usesGlassSurfaces(style.template);
    if (!shouldBlur) return child;

    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
        child: child,
      ),
    );
  }
}

class GlassNavigationBar extends StatelessWidget {
  const GlassNavigationBar({
    super.key,
    required this.child,
    this.enableBlur = true,
    this.sigma = 18,
  });

  final Widget child;
  final bool enableBlur;
  final double sigma;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).extension<AppStyle>() ?? const AppStyle();
    final shouldBlur = enableBlur && _usesGlassSurfaces(style.template);
    if (!shouldBlur) return child;

    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
        child: child,
      ),
    );
  }
}

class GlassCard extends StatelessWidget {
  const GlassCard({
    super.key,
    required this.child,
    this.enableBlur = true,
    this.sigma = 18,
    this.margin,
    this.clipBehavior,
    this.shape,
    this.color,
    this.elevation,
    this.shadowColor,
    this.surfaceTintColor,
  });

  final Widget child;
  final bool enableBlur;
  final double sigma;
  final EdgeInsetsGeometry? margin;
  final Clip? clipBehavior;
  final ShapeBorder? shape;
  final Color? color;
  final double? elevation;
  final Color? shadowColor;
  final Color? surfaceTintColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.extension<AppStyle>() ?? const AppStyle();
    final shouldBlur = enableBlur && _usesGlassSurfaces(style.template);

    final card = Card(
      margin: margin,
      clipBehavior: clipBehavior,
      shape: shape,
      color: color,
      elevation: elevation,
      shadowColor: shadowColor,
      surfaceTintColor: surfaceTintColor,
      child: child,
    );

    if (!shouldBlur) return card;

    final borderRadius = _borderRadiusOf(
      shape ?? theme.cardTheme.shape,
      Directionality.of(context),
    );

    return Stack(
      fit: StackFit.passthrough,
      children: [
        Positioned.fill(
          child: ClipRRect(
            borderRadius: borderRadius,
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
              child: const SizedBox.expand(),
            ),
          ),
        ),
        card,
      ],
    );
  }
}
