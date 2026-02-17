import 'package:flutter/material.dart';
import 'package:lin_player_core/state/media_server_type.dart';

import '../theme/desktop_theme_extension.dart';
import 'hover_effect_wrapper.dart';

class DesktopSidebarItem extends StatelessWidget {
  const DesktopSidebarItem({
    super.key,
    required this.serverName,
    required this.subtitle,
    required this.serverType,
    required this.selected,
    this.iconUrl,
    this.collapsed = false,
    required this.onTap,
  });

  final String serverName;
  final String subtitle;
  final MediaServerType serverType;
  final bool selected;
  final String? iconUrl;
  final bool collapsed;
  final VoidCallback onTap;

  IconData _fallbackIconForType(MediaServerType type) {
    switch (type) {
      case MediaServerType.jellyfin:
        return Icons.sports_esports_rounded;
      case MediaServerType.plex:
        return Icons.play_circle_outline_rounded;
      case MediaServerType.webdav:
        return Icons.folder_shared_outlined;
      case MediaServerType.emby:
        return Icons.video_library_outlined;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = DesktopThemeExtension.of(context);
    final fgPrimary = selected ? theme.textPrimary : theme.textMuted;
    final fgSecondary = selected
        ? theme.textPrimary.withValues(alpha: 0.72)
        : theme.textMuted.withValues(alpha: 0.74);
    final rawIconUrl = (iconUrl ?? '').trim();
    final fallbackIcon = _fallbackIconForType(serverType);
    final fallbackInitial =
        serverName.trim().isEmpty ? '?' : serverName.trim()[0];

    Widget fallbackAvatar() {
      return Center(
        child: fallbackInitial == '?'
            ? Icon(
                fallbackIcon,
                size: 18,
                color: selected ? theme.accent : theme.textMuted,
              )
            : Text(
                fallbackInitial.toUpperCase(),
                style: TextStyle(
                  color: selected ? theme.accent : theme.textMuted,
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                ),
              ),
      );
    }

    return HoverEffectWrapper(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      hoverScale: 1.01,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: selected
              ? theme.topTabActiveBackground
              : Colors.white.withValues(alpha: 0.02),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected
                ? theme.accent.withValues(alpha: 0.42)
                : theme.border.withValues(alpha: 0.24),
          ),
        ),
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: collapsed ? 10 : 12,
            vertical: 9,
          ),
          child: Row(
            mainAxisAlignment:
                collapsed ? MainAxisAlignment.center : MainAxisAlignment.start,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 34,
                height: 34,
                clipBehavior: Clip.antiAlias,
                decoration: BoxDecoration(
                  color: selected
                      ? theme.accent.withValues(alpha: 0.16)
                      : theme.surfaceElevated.withValues(alpha: 0.82),
                  borderRadius: BorderRadius.circular(9),
                  border: Border.all(
                    color: selected
                        ? theme.accent.withValues(alpha: 0.5)
                        : theme.border.withValues(alpha: 0.38),
                  ),
                ),
                child: rawIconUrl.isEmpty
                    ? fallbackAvatar()
                    : Image.network(
                        rawIconUrl,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => fallbackAvatar(),
                        loadingBuilder: (context, child, loadingProgress) {
                          if (loadingProgress == null) return child;
                          return fallbackAvatar();
                        },
                      ),
              ),
              if (!collapsed) ...[
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        serverName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: fgPrimary,
                          fontSize: 15,
                          height: 1.1,
                          fontWeight:
                              selected ? FontWeight.w700 : FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: fgSecondary,
                          fontSize: 11.5,
                          height: 1.15,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
