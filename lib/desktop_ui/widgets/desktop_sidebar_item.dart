import 'package:flutter/material.dart';

import '../theme/desktop_theme_extension.dart';
import 'hover_effect_wrapper.dart';

class DesktopSidebarItem extends StatelessWidget {
  const DesktopSidebarItem({
    super.key,
    required this.icon,
    required this.label,
    required this.selected,
    this.collapsed = false,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final bool collapsed;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final fg = selected ? desktopTheme.textPrimary : desktopTheme.textMuted;

    return HoverEffectWrapper(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      hoverScale: 1.03,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: selected
              ? desktopTheme.accent.withValues(alpha: 0.18)
              : Colors.white.withValues(alpha: 0.02),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected
                ? desktopTheme.accent.withValues(alpha: 0.45)
                : desktopTheme.border.withValues(alpha: 0.2),
          ),
        ),
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: collapsed ? 10 : 14,
            vertical: 12,
          ),
          child: Row(
            mainAxisAlignment:
                collapsed ? MainAxisAlignment.center : MainAxisAlignment.start,
            children: [
              Icon(
                icon,
                size: 20,
                color: selected ? desktopTheme.accent : fg,
              ),
              if (!collapsed) ...[
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: fg,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    ),
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
