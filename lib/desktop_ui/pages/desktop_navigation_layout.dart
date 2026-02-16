import 'package:flutter/material.dart';

import '../theme/desktop_theme_extension.dart';

class DesktopNavigationLayout extends StatelessWidget {
  const DesktopNavigationLayout({
    super.key,
    required this.sidebar,
    required this.topBar,
    required this.content,
    this.sidebarVisible = false,
    this.onDismissSidebar,
    this.sidebarWidth = 264,
  });

  final Widget sidebar;
  final Widget topBar;
  final Widget content;
  final bool sidebarVisible;
  final VoidCallback? onDismissSidebar;
  final double sidebarWidth;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final showSidebar = sidebarVisible && sidebarWidth > 0;

    return Stack(
      children: [
        DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                desktopTheme.backgroundGradientStart,
                desktopTheme.backgroundGradientEnd,
              ],
            ),
          ),
          child: const SizedBox.expand(),
        ),
        Positioned(
          left: -120,
          top: -140,
          child: _GlowSpot(
            size: 360,
            color: desktopTheme.accent.withValues(alpha: 0.12),
          ),
        ),
        Positioned(
          right: -130,
          top: 120,
          child: _GlowSpot(
            size: 300,
            color: desktopTheme.focus.withValues(alpha: 0.08),
          ),
        ),
        Column(
          children: [
            topBar,
            const SizedBox(height: 14),
            Expanded(child: content),
          ],
        ),
        if (showSidebar)
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onDismissSidebar,
              child: ColoredBox(
                color: Colors.black.withValues(alpha: 0.26),
              ),
            ),
          ),
        if (showSidebar)
          Align(
            alignment: Alignment.centerLeft,
            child: SizedBox(
              width: sidebarWidth,
              child: sidebar,
            ),
          ),
      ],
    );
  }
}

class _GlowSpot extends StatelessWidget {
  const _GlowSpot({
    required this.size,
    required this.color,
  });

  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [color, Colors.transparent],
          ),
        ),
      ),
    );
  }
}
