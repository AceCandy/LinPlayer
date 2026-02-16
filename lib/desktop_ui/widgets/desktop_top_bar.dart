import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/desktop_theme_extension.dart';

class DesktopTopBar extends StatelessWidget {
  const DesktopTopBar({
    super.key,
    required this.title,
    required this.searchController,
    required this.onSearchSubmitted,
    required this.onSearchChanged,
    this.showBack = false,
    this.onBack,
    this.onToggleSidebar,
    this.onRefresh,
    this.onOpenSettings,
    this.searchHint = 'Search series or movies',
  });

  final String title;
  final TextEditingController searchController;
  final ValueChanged<String> onSearchSubmitted;
  final ValueChanged<String> onSearchChanged;
  final bool showBack;
  final VoidCallback? onBack;
  final VoidCallback? onToggleSidebar;
  final VoidCallback? onRefresh;
  final VoidCallback? onOpenSettings;
  final String searchHint;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final titleStyle = TextStyle(
      color: desktopTheme.textPrimary,
      fontSize: 20,
      fontWeight: FontWeight.w700,
      letterSpacing: 0.2,
    );

    return ClipRRect(
      borderRadius: BorderRadius.circular(18),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.26),
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: desktopTheme.border),
          ),
          child: SizedBox(
            height: 64,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  _TopIconButton(
                    icon: showBack
                        ? Icons.arrow_back_rounded
                        : Icons.menu_rounded,
                    tooltip: showBack ? 'Back' : 'Toggle sidebar',
                    onTap: showBack ? onBack : onToggleSidebar,
                  ),
                  const SizedBox(width: 10),
                  _LogoBadge(theme: desktopTheme),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Row(
                      children: [
                        Flexible(
                          flex: 3,
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: titleStyle,
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          flex: 5,
                          child: _SearchInput(
                            controller: searchController,
                            hintText: searchHint,
                            onSubmitted: onSearchSubmitted,
                            onChanged: onSearchChanged,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  _TopIconButton(
                    icon: Icons.refresh_rounded,
                    tooltip: 'Refresh',
                    onTap: onRefresh,
                  ),
                  const SizedBox(width: 6),
                  const _TopIconButton(
                    icon: Icons.notifications_none_rounded,
                    tooltip: 'Notifications',
                  ),
                  const SizedBox(width: 6),
                  const _AvatarBadge(),
                  const SizedBox(width: 6),
                  _TopIconButton(
                    icon: Icons.settings_outlined,
                    tooltip: 'Settings',
                    onTap: onOpenSettings,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SearchInput extends StatelessWidget {
  const _SearchInput({
    required this.controller,
    required this.hintText,
    required this.onSubmitted,
    required this.onChanged,
  });

  final TextEditingController controller;
  final String hintText;
  final ValueChanged<String> onSubmitted;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);

    return TextField(
      controller: controller,
      onChanged: onChanged,
      onSubmitted: onSubmitted,
      textInputAction: TextInputAction.search,
      decoration: InputDecoration(
        isDense: true,
        hintText: hintText,
        hintStyle: TextStyle(
          color: desktopTheme.textMuted.withValues(alpha: 0.78),
          fontSize: 13,
        ),
        prefixIcon: Icon(
          Icons.search_rounded,
          size: 20,
          color: desktopTheme.textMuted,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(999),
          borderSide:
              BorderSide(color: desktopTheme.border.withValues(alpha: 0.8)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(999),
          borderSide:
              BorderSide(color: desktopTheme.border.withValues(alpha: 0.8)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(999),
          borderSide: BorderSide(
            color: desktopTheme.focus,
            width: 1.6,
          ),
        ),
        fillColor: Colors.white.withValues(alpha: 0.10),
        filled: true,
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
      ),
    );
  }
}

class _LogoBadge extends StatelessWidget {
  const _LogoBadge({required this.theme});

  final DesktopThemeExtension theme;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(9),
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [Color(0xFF2D8CFF), Color(0xFF1598B8)],
            ),
          ),
          alignment: Alignment.center,
          child: const Text(
            'L',
            style: TextStyle(
              color: Colors.white,
              fontSize: 14,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        const SizedBox(width: 8),
        Text(
          'LinPlayer',
          style: TextStyle(
            color: theme.textPrimary,
            fontSize: 13,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.2,
          ),
        ),
      ],
    );
  }
}

class _TopIconButton extends StatelessWidget {
  const _TopIconButton({
    required this.icon,
    required this.tooltip,
    this.onTap,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    return Tooltip(
      message: tooltip,
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.11),
            borderRadius: BorderRadius.circular(999),
            border:
                Border.all(color: desktopTheme.border.withValues(alpha: 0.9)),
          ),
          child: Icon(icon, size: 18, color: desktopTheme.textPrimary),
        ),
      ),
    );
  }
}

class _AvatarBadge extends StatelessWidget {
  const _AvatarBadge();

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF365A9B), Color(0xFF1A2946)],
        ),
        border: Border.all(color: desktopTheme.border.withValues(alpha: 0.9)),
      ),
      child: const Icon(
        Icons.person_outline_rounded,
        size: 18,
        color: Colors.white,
      ),
    );
  }
}
