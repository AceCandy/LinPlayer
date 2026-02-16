import 'dart:async';
import 'dart:ui';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';

import '../../server_adapters/server_access.dart';
import '../theme/desktop_theme_extension.dart';
import '../view_models/desktop_detail_view_model.dart';
import '../widgets/desktop_action_button_group.dart';
import '../widgets/desktop_hero_section.dart';
import '../widgets/desktop_horizontal_section.dart';
import '../widgets/desktop_media_card.dart';
import '../widgets/desktop_media_meta.dart';
import '../widgets/hover_effect_wrapper.dart';

class DesktopDetailPage extends StatefulWidget {
  const DesktopDetailPage({
    super.key,
    required this.viewModel,
    this.onOpenItem,
    this.onPlayPressed,
  });

  final DesktopDetailViewModel viewModel;
  final ValueChanged<MediaItem>? onOpenItem;
  final VoidCallback? onPlayPressed;

  @override
  State<DesktopDetailPage> createState() => _DesktopDetailPageState();
}

class _DesktopDetailPageState extends State<DesktopDetailPage> {
  @override
  void initState() {
    super.initState();
    unawaited(widget.viewModel.load());
  }

  @override
  void didUpdateWidget(covariant DesktopDetailPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.viewModel, widget.viewModel)) {
      unawaited(widget.viewModel.load(forceRefresh: true));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.viewModel,
      builder: (context, _) {
        final desktopTheme = DesktopThemeExtension.of(context);
        final vm = widget.viewModel;
        final type = vm.detail.type.trim().toLowerCase();
        final isEpisode = type == 'episode';

        if (vm.loading && vm.error == null && vm.detail.id.isEmpty) {
          return const Center(child: CircularProgressIndicator());
        }

        return ClipRRect(
          borderRadius: BorderRadius.circular(20),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: desktopTheme.surface.withValues(alpha: 0.66),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: desktopTheme.border),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(22, 22, 22, 30),
                child: CustomScrollView(
                  slivers: [
                    SliverToBoxAdapter(
                      child: isEpisode
                          ? _EpisodeHeroSection(
                              item: vm.detail,
                              access: vm.access,
                              actionButtons: DesktopActionButtonGroup(
                                onPlay: widget.onPlayPressed,
                                onToggleFavorite: vm.toggleFavorite,
                                isFavorite: vm.favorite,
                              ),
                            )
                          : DesktopHeroSection(
                              item: vm.detail,
                              access: vm.access,
                              overview: vm.detail.overview,
                              actionButtons: DesktopActionButtonGroup(
                                onPlay: widget.onPlayPressed,
                                onToggleFavorite: vm.toggleFavorite,
                                isFavorite: vm.favorite,
                              ),
                            ),
                    ),
                    if ((vm.error ?? '').trim().isNotEmpty) ...[
                      const SliverToBoxAdapter(child: SizedBox(height: 16)),
                      SliverToBoxAdapter(
                        child: _ErrorBanner(message: vm.error!),
                      ),
                    ],
                    if (isEpisode) ...[
                      const SliverToBoxAdapter(child: SizedBox(height: 32)),
                      SliverToBoxAdapter(
                        child: _MediaInfoPanelSection(item: vm.detail),
                      ),
                    ],
                    const SliverToBoxAdapter(child: SizedBox(height: 32)),
                    SliverToBoxAdapter(
                      child: DesktopHorizontalSection(
                        title: isEpisode ? 'More Episodes' : 'Episode Preview',
                        subtitle: vm.seasons.isEmpty
                            ? null
                            : 'From ${vm.seasons.length} seasons',
                        emptyLabel: 'No episodes available',
                        viewportHeight: 200,
                        children: vm.episodes
                            .map(
                              (item) => _EpisodePreviewCard(
                                item: item,
                                access: vm.access,
                                onTap: widget.onOpenItem == null
                                    ? null
                                    : () => widget.onOpenItem!(item),
                              ),
                            )
                            .toList(growable: false),
                      ),
                    ),
                    if (vm.seasons.isNotEmpty) ...[
                      const SliverToBoxAdapter(child: SizedBox(height: 40)),
                      SliverToBoxAdapter(
                        child: DesktopHorizontalSection(
                          title: 'Seasons',
                          emptyLabel: 'No seasons available',
                          viewportHeight: 282,
                          children: vm.seasons
                              .map(
                                (item) => _SeasonCard(
                                  item: item,
                                  access: vm.access,
                                  onTap: widget.onOpenItem == null
                                      ? null
                                      : () => widget.onOpenItem!(item),
                                ),
                              )
                              .toList(growable: false),
                        ),
                      ),
                    ],
                    const SliverToBoxAdapter(child: SizedBox(height: 40)),
                    SliverToBoxAdapter(
                      child: DesktopHorizontalSection(
                        title: 'Cast',
                        emptyLabel: 'No cast data',
                        viewportHeight: 190,
                        children: vm.people
                            .map(
                              (person) => _PeopleCard(
                                name: person.name,
                                role: person.role,
                                imageUrl: vm.personImageUrl(person),
                              ),
                            )
                            .toList(growable: false),
                      ),
                    ),
                    const SliverToBoxAdapter(child: SizedBox(height: 40)),
                    SliverToBoxAdapter(
                      child: DesktopHorizontalSection(
                        title: 'Similar',
                        emptyLabel: 'No recommendations yet',
                        viewportHeight: 352,
                        children: vm.similar
                            .map(
                              (item) => DesktopMediaCard(
                                item: item,
                                access: vm.access,
                                width: 206,
                                onTap: widget.onOpenItem == null
                                    ? null
                                    : () => widget.onOpenItem!(item),
                              ),
                            )
                            .toList(growable: false),
                      ),
                    ),
                    const SliverToBoxAdapter(child: SizedBox(height: 40)),
                    SliverToBoxAdapter(
                      child: _ExternalLinkRow(item: vm.detail),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _EpisodeHeroSection extends StatelessWidget {
  const _EpisodeHeroSection({
    required this.item,
    required this.access,
    required this.actionButtons,
  });

  final MediaItem item;
  final ServerAccess? access;
  final Widget actionButtons;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final backdropUrl = _imageUrl(
      access: access,
      item: item,
      type: 'Backdrop',
      maxWidth: 1600,
    );
    final shotUrl = _imageUrl(
      access: access,
      item: item,
      type: 'Primary',
      maxWidth: 920,
    );
    final tags = <String>[
      mediaTypeLabel(item),
      mediaYear(item),
      mediaRuntimeLabel(item),
      item.seriesName,
      if ((item.seasonNumber ?? 0) > 0) 'S${item.seasonNumber}',
      if ((item.episodeNumber ?? 0) > 0) 'E${item.episodeNumber}',
    ].where((it) => it.trim().isNotEmpty).toList(growable: false);

    return ClipRRect(
      borderRadius: BorderRadius.circular(20),
      child: SizedBox(
        height: 360,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (backdropUrl != null && backdropUrl.isNotEmpty)
              CachedNetworkImage(
                imageUrl: backdropUrl,
                fit: BoxFit.cover,
                placeholder: (_, __) => const SizedBox.shrink(),
                errorWidget: (_, __, ___) => const SizedBox.shrink(),
              ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft,
                  end: Alignment.centerRight,
                  colors: [
                    desktopTheme.background.withValues(alpha: 0.92),
                    Colors.black.withValues(alpha: 0.52),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(22),
              child: Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: SizedBox(
                      width: 420,
                      child: AspectRatio(
                        aspectRatio: 16 / 9,
                        child: _NetworkOrFallbackImage(
                          imageUrl: shotUrl,
                          fallbackLabel:
                              item.name.trim().isEmpty ? 'Episode' : item.name,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 22),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          item.name.trim().isEmpty
                              ? 'Untitled Episode'
                              : item.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: desktopTheme.textPrimary,
                            fontSize: 34,
                            fontWeight: FontWeight.w800,
                            height: 1.08,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: tags
                              .map((value) => _TagChip(value: value))
                              .toList(growable: false),
                        ),
                        const SizedBox(height: 16),
                        actionButtons,
                        const SizedBox(height: 14),
                        if (item.overview.trim().isNotEmpty)
                          Text(
                            item.overview,
                            maxLines: 4,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: desktopTheme.textMuted,
                              height: 1.5,
                              fontSize: 14,
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TagChip extends StatelessWidget {
  const _TagChip({required this.value});

  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        value,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _EpisodePreviewCard extends StatelessWidget {
  const _EpisodePreviewCard({
    required this.item,
    required this.access,
    this.onTap,
  });

  final MediaItem item;
  final ServerAccess? access;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final backdrop = _imageUrl(
      access: access,
      item: item,
      type: 'Backdrop',
      maxWidth: 980,
    );
    final fallback = _imageUrl(
      access: access,
      item: item,
      type: 'Primary',
      maxWidth: 620,
    );
    final imageUrl = (backdrop ?? '').trim().isNotEmpty ? backdrop : fallback;

    return SizedBox(
      width: 310,
      child: HoverEffectWrapper(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        hoverScale: 1.05,
        child: Stack(
          fit: StackFit.expand,
          children: [
            _NetworkOrFallbackImage(
              imageUrl: imageUrl,
              fallbackLabel: item.name,
            ),
            Positioned.fill(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Colors.transparent,
                      Colors.black.withValues(alpha: 0.76),
                    ],
                    stops: const [0.50, 1],
                  ),
                ),
              ),
            ),
            Positioned(
              top: 10,
              right: 10,
              child: Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Icon(
                  item.played ? Icons.check_rounded : Icons.play_arrow_rounded,
                  size: 14,
                  color: const Color(0xFF0A172A),
                ),
              ),
            ),
            Positioned(
              left: 12,
              right: 12,
              bottom: 10,
              child: Text(
                item.name.trim().isEmpty ? 'Episode' : item.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: desktopTheme.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SeasonCard extends StatelessWidget {
  const _SeasonCard({
    required this.item,
    required this.access,
    this.onTap,
  });

  final MediaItem item;
  final ServerAccess? access;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final imageUrl = _imageUrl(
      access: access,
      item: item,
      type: 'Primary',
      maxWidth: 520,
    );
    final subtitle = [
      if ((item.seasonNumber ?? 0) > 0) 'Season ${item.seasonNumber}',
      mediaYear(item),
    ].where((it) => it.trim().isNotEmpty).join('  •  ');

    return SizedBox(
      width: 156,
      child: HoverEffectWrapper(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        hoverScale: 1.05,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: desktopTheme.surfaceElevated,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: desktopTheme.border),
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 10, 10, 12),
            child: Column(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: AspectRatio(
                    aspectRatio: 2 / 3,
                    child: _NetworkOrFallbackImage(
                      imageUrl: imageUrl,
                      fallbackLabel: item.name,
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  item.name.trim().isEmpty ? 'Season' : item.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: desktopTheme.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                if (subtitle.trim().isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: desktopTheme.textMuted,
                      fontSize: 11.5,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PeopleCard extends StatelessWidget {
  const _PeopleCard({
    required this.name,
    required this.role,
    required this.imageUrl,
  });

  final String name;
  final String role;
  final String? imageUrl;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);

    return SizedBox(
      width: 126,
      child: HoverEffectWrapper(
        borderRadius: BorderRadius.circular(16),
        hoverScale: 1.05,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: desktopTheme.surfaceElevated,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: desktopTheme.border),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
            child: Column(
              children: [
                ClipOval(
                  child: SizedBox(
                    width: 72,
                    height: 72,
                    child: _PersonAvatar(imageUrl: imageUrl),
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  name.trim().isEmpty ? 'Unknown' : name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: desktopTheme.textPrimary,
                    fontWeight: FontWeight.w700,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  role.trim().isEmpty ? 'Cast' : role,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: desktopTheme.textMuted,
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PersonAvatar extends StatelessWidget {
  const _PersonAvatar({required this.imageUrl});

  final String? imageUrl;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    if ((imageUrl ?? '').trim().isNotEmpty) {
      return CachedNetworkImage(
        imageUrl: imageUrl!,
        fit: BoxFit.cover,
        placeholder: (_, __) => const SizedBox.shrink(),
        errorWidget: (_, __, ___) => DecoratedBox(
          decoration: BoxDecoration(
            color: desktopTheme.surface,
          ),
          child:
              Icon(Icons.person_outline_rounded, color: desktopTheme.textMuted),
        ),
      );
    }
    return DecoratedBox(
      decoration: BoxDecoration(
        color: desktopTheme.surface,
      ),
      child: Icon(Icons.person_outline_rounded, color: desktopTheme.textMuted),
    );
  }
}

class _ExternalLinkRow extends StatelessWidget {
  const _ExternalLinkRow({required this.item});

  final MediaItem item;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final labels = _buildExternalLabels(item);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'External Links',
          style: TextStyle(
            color: desktopTheme.textPrimary,
            fontSize: 24,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: labels
              .map(
                (label) => Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.10),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: desktopTheme.border),
                  ),
                  child: Text(
                    label,
                    style: TextStyle(
                      color: desktopTheme.textPrimary,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              )
              .toList(growable: false),
        ),
      ],
    );
  }
}

class _MediaInfoPanelSection extends StatelessWidget {
  const _MediaInfoPanelSection({required this.item});

  final MediaItem item;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final info = <MapEntry<String, String>>[
      MapEntry('Type', mediaTypeLabel(item)),
      MapEntry('Runtime', mediaRuntimeLabel(item)),
      MapEntry('Year', mediaYear(item)),
      MapEntry(
        'Rating',
        item.communityRating == null
            ? '--'
            : item.communityRating!.toStringAsFixed(1),
      ),
      MapEntry('Container',
          (item.container ?? '').trim().isEmpty ? '--' : item.container!),
      MapEntry(
          'Series', item.seriesName.trim().isEmpty ? '--' : item.seriesName),
      MapEntry('Season',
          (item.seasonNumber ?? 0) <= 0 ? '--' : '${item.seasonNumber}'),
      MapEntry('Episode',
          (item.episodeNumber ?? 0) <= 0 ? '--' : '${item.episodeNumber}'),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Media Info',
          style: TextStyle(
            color: desktopTheme.textPrimary,
            fontSize: 24,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 16),
        LayoutBuilder(
          builder: (context, constraints) {
            final maxWidth = constraints.maxWidth;
            final columns = switch (maxWidth) {
              > 1280 => 4,
              > 860 => 3,
              > 580 => 2,
              _ => 1,
            };
            final cellWidth = (maxWidth - 16 * (columns - 1)) / columns;

            return Wrap(
              spacing: 16,
              runSpacing: 16,
              children: info
                  .take(4)
                  .map(
                    (entry) => SizedBox(
                      width: cellWidth,
                      child: Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.08),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: desktopTheme.border),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(alpha: 0.22),
                              blurRadius: 16,
                              offset: const Offset(0, 8),
                            ),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              entry.key,
                              style: TextStyle(
                                color: desktopTheme.textMuted,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              entry.value,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: desktopTheme.textPrimary,
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  )
                  .toList(growable: false),
            );
          },
        ),
      ],
    );
  }
}

class _NetworkOrFallbackImage extends StatelessWidget {
  const _NetworkOrFallbackImage({
    required this.imageUrl,
    required this.fallbackLabel,
  });

  final String? imageUrl;
  final String fallbackLabel;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    if ((imageUrl ?? '').trim().isNotEmpty) {
      return CachedNetworkImage(
        imageUrl: imageUrl!,
        fit: BoxFit.cover,
        placeholder: (_, __) => const SizedBox.shrink(),
        errorWidget: (_, __, ___) => _fallback(desktopTheme),
      );
    }
    return _fallback(desktopTheme);
  }

  Widget _fallback(DesktopThemeExtension desktopTheme) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            desktopTheme.surface,
            desktopTheme.surfaceElevated,
          ],
        ),
      ),
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Text(
            fallbackLabel.trim().isEmpty ? 'No Image' : fallbackLabel,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: desktopTheme.textMuted,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0x33D64646),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0x66FF7777)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            const Icon(Icons.error_outline_rounded, color: Color(0xFFFF9D9D)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String? _imageUrl({
  required ServerAccess? access,
  required MediaItem item,
  required String type,
  required int maxWidth,
}) {
  final currentAccess = access;
  if (currentAccess == null) return null;
  if (!item.hasImage && type == 'Primary') return null;
  return currentAccess.adapter.imageUrl(
    currentAccess.auth,
    itemId: item.id,
    imageType: type,
    maxWidth: maxWidth,
  );
}

List<String> _buildExternalLabels(MediaItem item) {
  final providers = item.providerIds.keys
      .where((key) => key.trim().isNotEmpty)
      .map((key) => key.trim().toUpperCase())
      .toList(growable: false);
  if (providers.isNotEmpty) {
    return providers.take(6).toList(growable: false);
  }
  return const [
    'OFFICIAL SITE',
    'COMMUNITY',
    'FANDOM',
    'TRAILER',
  ];
}
