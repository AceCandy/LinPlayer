import 'dart:ui';

import 'package:flutter/material.dart';

import 'desktop_ui_episode_sections.dart';
import 'desktop_ui_home_sections.dart';
import 'desktop_ui_series_sections.dart';
import 'desktop_ui_shared.dart';

class DesktopUiPreviewPage extends StatelessWidget {
  const DesktopUiPreviewPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const DesktopUiAppLayout(
      child: _DesktopUiPreviewContent(),
    );
  }
}

class DesktopUiAppLayout extends StatelessWidget {
  const DesktopUiAppLayout({
    super.key,
    required this.child,
    this.collapsedNavigation = false,
  });

  final Widget child;
  final bool collapsedNavigation;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: DesktopUiTheme.background,
      body: Stack(
        children: [
          const Positioned.fill(child: _BackgroundDecor()),
          const Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: DesktopUiTopBar(),
          ),
          Positioned.fill(
            top: 64,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DesktopUiSideNavigation(collapsed: collapsedNavigation),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
                    child: child,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class DesktopUiTopBar extends StatelessWidget {
  const DesktopUiTopBar({super.key});

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          Positioned.fill(
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
              child: const SizedBox.expand(),
            ),
          ),
          Container(
            height: 64,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.28),
              border: Border(
                bottom: BorderSide(
                  color: Colors.white.withValues(alpha: 0.08),
                ),
              ),
            ),
            child: Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(10),
                    gradient: const LinearGradient(
                      colors: [Color(0xFF2D8CFF), Color(0xFF0EA5B7)],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                  ),
                  alignment: Alignment.center,
                  child: const Text(
                    'L',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                const Text(
                  'Placeholder Logo',
                  style: TextStyle(
                    color: DesktopUiTheme.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(width: 14),
                _iconButton(Icons.menu_rounded),
                const Spacer(),
                const Text(
                  'Placeholder Center Title',
                  style: TextStyle(
                    color: DesktopUiTheme.textSecondary,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const Spacer(),
                _iconButton(Icons.search_rounded),
                const SizedBox(width: 8),
                _iconButton(Icons.notifications_none_rounded),
                const SizedBox(width: 8),
                Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.16),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: const Icon(
                    Icons.person_outline_rounded,
                    color: DesktopUiTheme.textPrimary,
                    size: 18,
                  ),
                ),
                const SizedBox(width: 8),
                _iconButton(Icons.settings_outlined),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static Widget _iconButton(IconData icon) {
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Icon(icon, color: DesktopUiTheme.textPrimary, size: 18),
    );
  }
}

class DesktopUiSideNavigation extends StatelessWidget {
  const DesktopUiSideNavigation({
    super.key,
    required this.collapsed,
  });

  final bool collapsed;

  @override
  Widget build(BuildContext context) {
    final width = collapsed ? 92.0 : 250.0;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      curve: Curves.easeOut,
      width: width,
      padding: const EdgeInsets.fromLTRB(12, 14, 12, 18),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.24),
        border: Border(
          right: BorderSide(
            color: Colors.white.withValues(alpha: 0.08),
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment:
            collapsed ? CrossAxisAlignment.center : CrossAxisAlignment.start,
        children: [
          _NavItem(
            icon: Icons.home_rounded,
            label: 'Placeholder Nav 01',
            selected: true,
            collapsed: collapsed,
          ),
          const SizedBox(height: 8),
          _NavItem(
            icon: Icons.grid_view_rounded,
            label: 'Placeholder Nav 02',
            collapsed: collapsed,
          ),
          const SizedBox(height: 8),
          _NavItem(
            icon: Icons.search_rounded,
            label: 'Placeholder Nav 03',
            collapsed: collapsed,
          ),
          const SizedBox(height: 8),
          _NavItem(
            icon: Icons.movie_outlined,
            label: 'Placeholder Nav 04',
            collapsed: collapsed,
          ),
          const SizedBox(height: 8),
          _NavItem(
            icon: Icons.settings_outlined,
            label: 'Placeholder Nav 05',
            collapsed: collapsed,
          ),
          const Spacer(),
          if (!collapsed)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 10),
              child: Text(
                'Placeholder Collapse Support\n(UI Only)',
                style: TextStyle(
                  color: DesktopUiTheme.textSecondary,
                  fontSize: 11,
                  height: 1.4,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.label,
    required this.collapsed,
    this.selected = false,
  });

  final IconData icon;
  final String label;
  final bool collapsed;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final bg =
        selected ? Colors.white.withValues(alpha: 0.15) : Colors.transparent;
    final color =
        selected ? DesktopUiTheme.textPrimary : DesktopUiTheme.textSecondary;

    return UiHoverScale(
      scale: 1.02,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
        height: 42,
        padding: EdgeInsets.symmetric(horizontal: collapsed ? 0 : 12),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected ? DesktopUiTheme.border : Colors.transparent,
          ),
        ),
        child: Row(
          mainAxisAlignment:
              collapsed ? MainAxisAlignment.center : MainAxisAlignment.start,
          children: [
            Icon(icon, color: color, size: 18),
            if (!collapsed) ...[
              const SizedBox(width: 10),
              Text(
                label,
                style: TextStyle(
                  color: color,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _DesktopUiPreviewContent extends StatelessWidget {
  const _DesktopUiPreviewContent();

  @override
  Widget build(BuildContext context) {
    return ListView(
      children: const [
        UiPageShell(
          title: 'HomePage Placeholder',
          child: DesktopHomePageUi(),
        ),
        SizedBox(height: 32),
        UiPageShell(
          title: 'SeriesDetailPage Placeholder',
          child: DesktopSeriesDetailPageUi(),
        ),
        SizedBox(height: 32),
        UiPageShell(
          title: 'EpisodeDetailPage Placeholder',
          child: DesktopEpisodeDetailPageUi(),
        ),
        SizedBox(height: 16),
      ],
    );
  }
}

class _BackgroundDecor extends StatelessWidget {
  const _BackgroundDecor();

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        const Positioned.fill(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFF070B12),
                  Color(0xFF0E1622),
                  Color(0xFF090E16),
                ],
              ),
            ),
          ),
        ),
        Positioned(
          left: -120,
          top: -160,
          child: _glow(
            size: 420,
            color: const Color(0xFF0F9FC4).withValues(alpha: 0.20),
          ),
        ),
        Positioned(
          right: -140,
          top: 120,
          child: _glow(
            size: 380,
            color: const Color(0xFF2D8CFF).withValues(alpha: 0.20),
          ),
        ),
      ],
    );
  }

  static Widget _glow({required double size, required Color color}) {
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
