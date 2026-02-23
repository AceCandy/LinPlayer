import 'package:flutter/material.dart';

class DesktopUiTheme {
  static const Color background = Color(0xFF060A11);
  static const Color surface = Color(0x0DFFFFFF);
  static const Color border = Color(0x22FFFFFF);
  static const Color surfaceStrong = Color(0x19FFFFFF);
  static const Color textPrimary = Color(0xFFF4F7FC);
  static const Color textSecondary = Color(0xFFA0AEC4);
  static const Color accent = Color(0xFF2D8CFF);
}

class UiSectionHeader extends StatelessWidget {
  const UiSectionHeader({
    super.key,
    required this.title,
    this.trailing = 'Placeholder More',
  });

  final String title;
  final String trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: const TextStyle(
              color: DesktopUiTheme.textPrimary,
              fontSize: 22,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        Text(
          trailing,
          style: const TextStyle(
            color: DesktopUiTheme.textSecondary,
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }
}

class UiHorizontalScrollArea extends StatelessWidget {
  const UiHorizontalScrollArea({
    super.key,
    required this.children,
  });

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: children),
    );
  }
}

class UiHoverScale extends StatefulWidget {
  const UiHoverScale({
    super.key,
    required this.child,
    this.scale = 1.05,
    this.duration = const Duration(milliseconds: 200),
  });

  final Widget child;
  final double scale;
  final Duration duration;

  @override
  State<UiHoverScale> createState() => _UiHoverScaleState();
}

class _UiHoverScaleState extends State<UiHoverScale> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: AnimatedScale(
        scale: _hovered ? widget.scale : 1.0,
        duration: widget.duration,
        curve: Curves.easeOut,
        child: widget.child,
      ),
    );
  }
}

class UiPlaceholderImage extends StatelessWidget {
  const UiPlaceholderImage({
    super.key,
    required this.url,
    this.fit = BoxFit.cover,
  });

  final String url;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    return Image.network(
      url,
      fit: fit,
      errorBuilder: (context, _, __) {
        return const ColoredBox(
          color: Color(0xFF131B29),
          child: Center(
            child: Icon(
              Icons.image_not_supported_outlined,
              color: Color(0xFF6B778D),
              size: 26,
            ),
          ),
        );
      },
      loadingBuilder: (context, child, progress) {
        if (progress == null) return child;
        return const ColoredBox(color: Color(0xFF0E1521));
      },
    );
  }
}

class UiPageShell extends StatelessWidget {
  const UiPageShell({
    super.key,
    required this.title,
    required this.child,
  });

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: DesktopUiTheme.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: DesktopUiTheme.border),
        boxShadow: const [
          BoxShadow(
            color: Color(0x66000000),
            blurRadius: 30,
            offset: Offset(0, 16),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(
                color: DesktopUiTheme.textPrimary,
                fontSize: 28,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.2,
              ),
            ),
            const SizedBox(height: 24),
            child,
          ],
        ),
      ),
    );
  }
}

class UiGlassButton extends StatelessWidget {
  const UiGlassButton({
    super.key,
    required this.label,
    this.highlighted = false,
    this.icon,
  });

  final String label;
  final bool highlighted;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final color = highlighted
        ? DesktopUiTheme.accent
        : Colors.white.withValues(alpha: 0.10);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: highlighted
              ? DesktopUiTheme.accent.withValues(alpha: 0.8)
              : DesktopUiTheme.border,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 16, color: DesktopUiTheme.textPrimary),
            const SizedBox(width: 8),
          ],
          Text(
            label,
            style: const TextStyle(
              color: DesktopUiTheme.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class UiTagChip extends StatelessWidget {
  const UiTagChip({
    super.key,
    required this.label,
  });

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: DesktopUiTheme.border),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: DesktopUiTheme.textSecondary,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class UiPosterCard extends StatelessWidget {
  const UiPosterCard({
    super.key,
    required this.index,
    this.width = 176,
  });

  final int index;
  final double width;

  String get _posterUrl =>
      'https://placehold.co/400x600/101826/8EA1BB/png?text=POSTER+${index + 1}';

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: UiHoverScale(
        child: SizedBox(
          width: width,
          child: AspectRatio(
            aspectRatio: 2 / 3,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: Stack(
                children: [
                  Positioned.fill(child: UiPlaceholderImage(url: _posterUrl)),
                  Positioned(
                    top: 10,
                    right: 10,
                    child: Container(
                      width: 22,
                      height: 22,
                      decoration: BoxDecoration(
                        color: DesktopUiTheme.accent,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                  ),
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [
                            Colors.transparent,
                            Colors.black.withValues(alpha: 0.78),
                          ],
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    left: 12,
                    right: 12,
                    bottom: 12,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Placeholder Title ${index + 1}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: DesktopUiTheme.textPrimary,
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          'Placeholder Subtitle',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: DesktopUiTheme.textSecondary,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
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

class UiCategoryCard extends StatelessWidget {
  const UiCategoryCard({
    super.key,
    required this.index,
  });

  final int index;

  static const List<List<Color>> _gradients = [
    [Color(0xFF164E63), Color(0xFF1E3A8A)],
    [Color(0xFF1F2937), Color(0xFF0E7490)],
    [Color(0xFF312E81), Color(0xFF155E75)],
    [Color(0xFF1E3A8A), Color(0xFF0F766E)],
  ];

  @override
  Widget build(BuildContext context) {
    final colors = _gradients[index % _gradients.length];
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: UiHoverScale(
        scale: 1.03,
        child: Container(
          width: 380,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: colors,
            ),
            borderRadius: BorderRadius.circular(20),
            boxShadow: const [
              BoxShadow(
                color: Color(0x4A000000),
                blurRadius: 24,
                offset: Offset(0, 12),
              ),
            ],
          ),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Placeholder Category',
                      style: TextStyle(
                        color: DesktopUiTheme.textPrimary,
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    SizedBox(height: 10),
                    Text(
                      'Placeholder Description Placeholder Description',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Color(0xD7CFD8E8),
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 16),
              SizedBox(
                width: 122,
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    _buildCollage(0, 0, 0),
                    _buildCollage(1, 26, 12),
                    _buildCollage(2, 50, 24),
                    _buildCollage(3, 72, 34),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCollage(int slot, double top, double left) {
    return Positioned(
      left: left,
      top: top,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: SizedBox(
          width: 56,
          height: 82,
          child: UiPlaceholderImage(
            url:
                'https://placehold.co/180x260/0D1320/B7C4D8/png?text=C${index + 1}-${slot + 1}',
          ),
        ),
      ),
    );
  }
}

class UiEpisodeCard extends StatelessWidget {
  const UiEpisodeCard({
    super.key,
    required this.index,
  });

  final int index;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: UiHoverScale(
        child: SizedBox(
          width: 310,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: AspectRatio(
              aspectRatio: 16 / 9,
              child: Stack(
                children: [
                  Positioned.fill(
                    child: UiPlaceholderImage(
                      url:
                          'https://placehold.co/640x360/111A2A/A6B6CD/png?text=EPISODE+${index + 1}',
                    ),
                  ),
                  Positioned(
                    top: 10,
                    right: 10,
                    child: Container(
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.84),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: const Icon(
                        Icons.check_rounded,
                        size: 14,
                        color: Color(0xFF0F172A),
                      ),
                    ),
                  ),
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [
                            Colors.transparent,
                            Colors.black.withValues(alpha: 0.72),
                          ],
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    left: 12,
                    right: 12,
                    bottom: 10,
                    child: Text(
                      'Placeholder Episode ${index + 1}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: DesktopUiTheme.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
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

class UiSeasonCard extends StatelessWidget {
  const UiSeasonCard({
    super.key,
    required this.index,
  });

  final int index;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: UiHoverScale(
        child: Container(
          width: 150,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: DesktopUiTheme.surfaceStrong,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: DesktopUiTheme.border),
          ),
          child: Column(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: AspectRatio(
                  aspectRatio: 2 / 3,
                  child: UiPlaceholderImage(
                    url:
                        'https://placehold.co/240x360/0F172A/B5C2D8/png?text=SEASON+${index + 1}',
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'Placeholder ${index + 1}',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: DesktopUiTheme.textPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 4),
              const Text(
                'Placeholder Sub',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: DesktopUiTheme.textSecondary,
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class UiCastCard extends StatelessWidget {
  const UiCastCard({
    super.key,
    required this.index,
  });

  final int index;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: UiHoverScale(
        child: Container(
          width: 126,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
          decoration: BoxDecoration(
            color: DesktopUiTheme.surfaceStrong,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: DesktopUiTheme.border),
          ),
          child: Column(
            children: [
              ClipOval(
                child: SizedBox(
                  width: 70,
                  height: 70,
                  child: UiPlaceholderImage(
                    url:
                        'https://placehold.co/160x160/122033/9FB1CA/png?text=CAST+${index + 1}',
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'Placeholder ${index + 1}',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: DesktopUiTheme.textPrimary,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 2),
              const Text(
                'Placeholder Role',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: DesktopUiTheme.textSecondary,
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class UiInfoCard extends StatelessWidget {
  const UiInfoCard({
    super.key,
    required this.index,
  });

  final int index;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.07),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: DesktopUiTheme.border),
        boxShadow: const [
          BoxShadow(
            color: Color(0x36000000),
            blurRadius: 18,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Placeholder Label',
            style: TextStyle(
              color: DesktopUiTheme.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
          SizedBox(height: 8),
          Text(
            'Placeholder Value Placeholder Value',
            style: TextStyle(
              color: DesktopUiTheme.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w700,
              height: 1.35,
            ),
          ),
          SizedBox(height: 6),
          Text(
            'Placeholder Description Placeholder Description',
            style: TextStyle(
              color: DesktopUiTheme.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w500,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}
