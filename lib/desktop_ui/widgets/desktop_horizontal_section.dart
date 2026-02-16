import 'package:flutter/material.dart';

import '../theme/desktop_theme_extension.dart';

class DesktopHorizontalSection extends StatelessWidget {
  const DesktopHorizontalSection({
    super.key,
    required this.title,
    required this.children,
    this.subtitle,
    this.trailing,
    this.emptyLabel = 'No items yet',
    this.spacing = 16,
    this.viewportHeight = 380,
  });

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final List<Widget> children;
  final String emptyLabel;
  final double spacing;
  final double viewportHeight;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      color: desktopTheme.textPrimary,
                      fontSize: 24,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if ((subtitle ?? '').trim().isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      subtitle!,
                      style: TextStyle(
                        color: desktopTheme.textMuted,
                        fontSize: 13.5,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null)
              trailing!
            else
              Text(
                'View More',
                style: TextStyle(
                  color: desktopTheme.textMuted,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
          ],
        ),
        const SizedBox(height: 16),
        SizedBox(
          height: viewportHeight,
          child: children.isEmpty
              ? Center(
                  child: Text(
                    emptyLabel,
                    style: TextStyle(color: desktopTheme.textMuted),
                  ),
                )
              : ListView.separated(
                  clipBehavior: Clip.none,
                  scrollDirection: Axis.horizontal,
                  itemCount: children.length,
                  itemBuilder: (context, index) => children[index],
                  separatorBuilder: (_, __) => SizedBox(width: spacing),
                ),
        ),
      ],
    );
  }
}
