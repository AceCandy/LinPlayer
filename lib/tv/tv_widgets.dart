import 'package:flutter/material.dart';
import 'package:lin_player_ui/lin_player_ui.dart';

class TvActionCard extends StatefulWidget {
  const TvActionCard({
    super.key,
    required this.title,
    required this.icon,
    this.subtitle,
    this.onPressed,
    this.autofocus = false,
    this.enabled = true,
  });

  final String title;
  final String? subtitle;
  final IconData icon;
  final VoidCallback? onPressed;
  final bool autofocus;
  final bool enabled;

  @override
  State<TvActionCard> createState() => _TvActionCardState();
}

class _TvActionCardState extends State<TvActionCard> {
  bool _focused = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final uiScale = context.uiScale;

    final radius = (18 * uiScale).clamp(12.0, 22.0);
    final contentPadding = (16 * uiScale).clamp(12.0, 18.0);
    final iconSize = (28 * uiScale).clamp(18.0, 32.0);
    final subtitleSpacing = (6 * uiScale).clamp(4.0, 8.0);

    final enabled = widget.enabled && widget.onPressed != null;
    final borderColor = _focused
        ? colorScheme.primary
        : colorScheme.outlineVariant.withValues(alpha: 0.7);
    final bg = _focused
        ? colorScheme.primary.withValues(alpha: 0.14)
        : colorScheme.surfaceContainerHighest.withValues(alpha: 0.65);

    return FocusableActionDetector(
      autofocus: widget.autofocus,
      enabled: enabled,
      onFocusChange: (v) => setState(() => _focused = v),
      child: AnimatedScale(
        scale: _focused ? 1.04 : 1.0,
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          curve: Curves.easeOut,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(radius),
            border: Border.all(
              width: _focused ? 2.0 : 1.0,
              color: enabled ? borderColor : borderColor.withValues(alpha: 0.4),
            ),
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(radius),
            onTap: enabled ? widget.onPressed : null,
            child: Padding(
              padding: EdgeInsets.all(contentPadding),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    widget.icon,
                    size: iconSize,
                    color: enabled
                        ? colorScheme.onSurface
                        : colorScheme.onSurface.withValues(alpha: 0.55),
                  ),
                  const Spacer(),
                  Text(
                    widget.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: enabled
                          ? colorScheme.onSurface
                          : colorScheme.onSurface.withValues(alpha: 0.55),
                    ),
                  ),
                  if ((widget.subtitle ?? '').trim().isNotEmpty) ...[
                    SizedBox(height: subtitleSpacing),
                    Text(
                      widget.subtitle!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: enabled
                            ? colorScheme.onSurfaceVariant
                            : colorScheme.onSurfaceVariant
                                .withValues(alpha: 0.55),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
