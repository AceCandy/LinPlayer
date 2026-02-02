import 'package:flutter/material.dart';

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
            borderRadius: BorderRadius.circular(18),
            border: Border.all(
              width: _focused ? 2.0 : 1.0,
              color: enabled ? borderColor : borderColor.withValues(alpha: 0.4),
            ),
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(18),
            onTap: enabled ? widget.onPressed : null,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    widget.icon,
                    size: 28,
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
                    const SizedBox(height: 6),
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

