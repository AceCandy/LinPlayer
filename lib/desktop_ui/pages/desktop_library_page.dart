import 'dart:async';
import 'dart:ui';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';
import 'package:lin_player_state/lin_player_state.dart';

import '../../server_adapters/server_access.dart';
import '../theme/desktop_theme_extension.dart';
import '../widgets/desktop_media_meta.dart';

class DesktopLibraryPage extends StatefulWidget {
  const DesktopLibraryPage({
    super.key,
    required this.appState,
    required this.onOpenItem,
    this.refreshSignal = 0,
  });

  final AppState appState;
  final ValueChanged<MediaItem> onOpenItem;
  final int refreshSignal;

  @override
  State<DesktopLibraryPage> createState() => _DesktopLibraryPageState();
}

class _DesktopLibraryPageState extends State<DesktopLibraryPage> {
  bool _loading = true;
  String? _error;
  Future<List<MediaItem>>? _continueFuture;
  Future<List<MediaItem>>? _recommendFuture;

  @override
  void initState() {
    super.initState();
    unawaited(_bootstrap(forceRefresh: false));
  }

  @override
  void didUpdateWidget(covariant DesktopLibraryPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.refreshSignal != widget.refreshSignal) {
      unawaited(_bootstrap(forceRefresh: true));
    }
  }

  Future<void> _bootstrap({required bool forceRefresh}) async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      if (widget.appState.libraries.isEmpty || forceRefresh) {
        await widget.appState.refreshLibraries();
      }
      await widget.appState.loadHome(forceRefresh: true);
      if (!mounted) return;
      setState(() {
        _continueFuture = widget.appState.loadContinueWatching(
          forceRefresh: forceRefresh,
        );
        _recommendFuture = widget.appState.loadRandomRecommendations(
          forceRefresh: forceRefresh,
        );
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e.toString());
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.appState,
      builder: (context, _) {
        final desktopTheme = DesktopThemeExtension.of(context);
        final access = resolveServerAccess(appState: widget.appState);
        final libraries = widget.appState.libraries
            .where((lib) => !widget.appState.isLibraryHidden(lib.id))
            .toList(growable: false);

        final appStateError = widget.appState.error;
        final effectiveError = (_error ?? '').trim().isNotEmpty
            ? _error
            : ((appStateError ?? '').trim().isNotEmpty && libraries.isEmpty
                ? appStateError
                : null);

        if (_loading && libraries.isEmpty) {
          return const Center(child: CircularProgressIndicator());
        }

        final wallpaperItem = _pickWallpaperItem(libraries);
        final wallpaperUrl = _imageUrl(
              access: access,
              item: wallpaperItem,
              imageType: 'Backdrop',
              maxWidth: 1920,
            ) ??
            _imageUrl(
              access: access,
              item: wallpaperItem,
              imageType: 'Primary',
              maxWidth: 1200,
            );

        return ClipRRect(
          borderRadius: BorderRadius.circular(20),
          child: Stack(
            children: [
              Positioned.fill(
                child: _LibraryBackground(
                  imageUrl: wallpaperUrl,
                  color: desktopTheme.background,
                ),
              ),
              Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Colors.black.withValues(alpha: 0.44),
                        Colors.black.withValues(alpha: 0.58),
                        Colors.black.withValues(alpha: 0.70),
                      ],
                    ),
                  ),
                ),
              ),
              CustomScrollView(
                slivers: [
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(18, 16, 18, 40),
                    sliver: SliverList(
                      delegate: SliverChildListDelegate(
                        [
                          if ((effectiveError ?? '').trim().isNotEmpty)
                            _ErrorBanner(
                              message: effectiveError!,
                              onRetry: () => _bootstrap(forceRefresh: true),
                            ),
                          if ((effectiveError ?? '').trim().isNotEmpty)
                            const SizedBox(height: 18),
                          if (libraries.isNotEmpty)
                            _CategoryStripSection(
                              libraries: libraries,
                              appState: widget.appState,
                              access: access,
                              onOpenItem: widget.onOpenItem,
                            ),
                          if (libraries.isNotEmpty) const SizedBox(height: 34),
                          _buildFutureRowSection(
                            title: 'Continue Watching',
                            future: _continueFuture ??
                                widget.appState.loadContinueWatching(
                                  forceRefresh: false,
                                ),
                            access: access,
                          ),
                          const SizedBox(height: 36),
                          _buildFutureRowSection(
                            title: 'Recommended',
                            future: _recommendFuture ??
                                widget.appState.loadRandomRecommendations(
                                  forceRefresh: false,
                                ),
                            access: access,
                          ),
                          for (final library in libraries) ...[
                            const SizedBox(height: 36),
                            _PosterRowSection(
                              title: library.name,
                              items: widget.appState.getHome('lib_${library.id}'),
                              access: access,
                              onOpenItem: widget.onOpenItem,
                            ),
                          ],
                          if (libraries.isEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 30),
                              child: Text(
                                'No visible libraries',
                                style: TextStyle(
                                  color: desktopTheme.textMuted,
                                  fontSize: 14,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildFutureRowSection({
    required String title,
    required Future<List<MediaItem>> future,
    required ServerAccess? access,
  }) {
    return FutureBuilder<List<MediaItem>>(
      future: future,
      builder: (context, snapshot) {
        final items = snapshot.data ?? const <MediaItem>[];
        return _PosterRowSection(
          title: title,
          items: items,
          access: access,
          onOpenItem: widget.onOpenItem,
          loading: snapshot.connectionState == ConnectionState.waiting,
        );
      },
    );
  }

  MediaItem? _pickWallpaperItem(List<LibraryInfo> libraries) {
    for (final library in libraries) {
      final items = widget.appState.getHome('lib_${library.id}');
      if (items.isEmpty) continue;
      for (final item in items) {
        if (item.hasImage) return item;
      }
      return items.first;
    }
    return null;
  }
}

class _LibraryBackground extends StatelessWidget {
  const _LibraryBackground({
    required this.imageUrl,
    required this.color,
  });

  final String? imageUrl;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final url = (imageUrl ?? '').trim();
    if (url.isEmpty) {
      return ColoredBox(color: color);
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        ImageFiltered(
          imageFilter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
          child: CachedNetworkImage(
            imageUrl: url,
            fit: BoxFit.cover,
            placeholder: (_, __) => ColoredBox(color: color),
            errorWidget: (_, __, ___) => ColoredBox(color: color),
          ),
        ),
        ColoredBox(color: Colors.black.withValues(alpha: 0.26)),
      ],
    );
  }
}

class _CategoryStripSection extends StatelessWidget {
  const _CategoryStripSection({
    required this.libraries,
    required this.appState,
    required this.access,
    required this.onOpenItem,
  });

  final List<LibraryInfo> libraries;
  final AppState appState;
  final ServerAccess? access;
  final ValueChanged<MediaItem> onOpenItem;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Media',
          style: TextStyle(
            color: desktopTheme.textPrimary,
            fontSize: 38,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 14),
        SizedBox(
          height: 168,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: libraries.length,
            itemBuilder: (context, index) {
              final library = libraries[index];
              final preview = appState.getHome('lib_${library.id}');
              return _CategoryCard(
                index: index,
                library: library,
                preview: preview,
                access: access,
                onOpenItem: onOpenItem,
              );
            },
            separatorBuilder: (_, __) => const SizedBox(width: 14),
          ),
        ),
      ],
    );
  }
}

class _CategoryCard extends StatelessWidget {
  const _CategoryCard({
    required this.index,
    required this.library,
    required this.preview,
    required this.access,
    required this.onOpenItem,
  });

  final int index;
  final LibraryInfo library;
  final List<MediaItem> preview;
  final ServerAccess? access;
  final ValueChanged<MediaItem> onOpenItem;

  static const List<List<Color>> _palette = [
    [Color(0xFF0C7B7B), Color(0xFF128CB2)],
    [Color(0xFFAA111B), Color(0xFF5F1C87)],
    [Color(0xFF1E2B8B), Color(0xFF185EA1)],
    [Color(0xFF6116A5), Color(0xFF2766C6)],
    [Color(0xFF2A6C90), Color(0xFF4B87B9)],
    [Color(0xFFA55B80), Color(0xFFB57A9C)],
    [Color(0xFF993976), Color(0xFFA84D8B)],
    [Color(0xFF19703D), Color(0xFF26924A)],
  ];

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final colors = _palette[index % _palette.length];
    final posters = preview.take(4).toList(growable: false);

    return SizedBox(
      width: 180,
      child: Column(
        children: [
          Expanded(
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: preview.isEmpty ? null : () => onOpenItem(preview.first),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: colors,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.28),
                      blurRadius: 16,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: Padding(
                  padding: const EdgeInsets.all(10),
                  child: Stack(
                    clipBehavior: Clip.none,
                    children: [
                      Align(
                        alignment: Alignment.topLeft,
                        child: Text(
                          library.name.trim().isEmpty ? 'Library' : library.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      for (var i = 0; i < 4; i++)
                        _CategoryPoster(
                          left: 68 + i * 21,
                          top: 8 + i * 6,
                          item: i < posters.length ? posters[i] : null,
                          access: access,
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            library.name.trim().isEmpty ? 'Library' : library.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: desktopTheme.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _CategoryPoster extends StatelessWidget {
  const _CategoryPoster({
    required this.left,
    required this.top,
    required this.item,
    required this.access,
  });

  final double left;
  final double top;
  final MediaItem? item;
  final ServerAccess? access;

  @override
  Widget build(BuildContext context) {
    final imageUrl = _imageUrl(
      access: access,
      item: item,
      imageType: 'Primary',
      maxWidth: 280,
    );

    return Positioned(
      left: left,
      top: top,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: SizedBox(
          width: 44,
          height: 62,
          child: imageUrl == null
              ? const ColoredBox(color: Color(0x44000000))
              : CachedNetworkImage(
                  imageUrl: imageUrl,
                  fit: BoxFit.cover,
                  placeholder: (_, __) => const ColoredBox(
                    color: Color(0x44000000),
                  ),
                  errorWidget: (_, __, ___) => const ColoredBox(
                    color: Color(0x44000000),
                  ),
                ),
        ),
      ),
    );
  }
}

class _PosterRowSection extends StatelessWidget {
  const _PosterRowSection({
    required this.title,
    required this.items,
    required this.access,
    required this.onOpenItem,
    this.loading = false,
  });

  final String title;
  final List<MediaItem> items;
  final ServerAccess? access;
  final ValueChanged<MediaItem> onOpenItem;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                title.trim().isEmpty ? 'Section' : title,
                style: TextStyle(
                  color: desktopTheme.textPrimary,
                  fontSize: 44,
                  fontWeight: FontWeight.w800,
                  height: 1.02,
                ),
              ),
            ),
            Text(
              'View All',
              style: TextStyle(
                color: desktopTheme.textMuted.withValues(alpha: 0.92),
                fontSize: 22,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        if (loading)
          SizedBox(
            height: 320,
            child: Center(
              child: Text(
                'Loading...',
                style: TextStyle(color: desktopTheme.textMuted),
              ),
            ),
          )
        else if (items.isEmpty)
          SizedBox(
            height: 320,
            child: Center(
              child: Text(
                'No media found',
                style: TextStyle(color: desktopTheme.textMuted),
              ),
            ),
          )
        else
          SizedBox(
            height: 350,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              clipBehavior: Clip.none,
              itemCount: items.length,
              itemBuilder: (context, index) {
                final item = items[index];
                return _HomePosterCard(
                  item: item,
                  access: access,
                  onTap: () => onOpenItem(item),
                );
              },
              separatorBuilder: (_, __) => const SizedBox(width: 16),
            ),
          ),
      ],
    );
  }
}

class _HomePosterCard extends StatelessWidget {
  const _HomePosterCard({
    required this.item,
    required this.access,
    required this.onTap,
  });

  final MediaItem item;
  final ServerAccess? access;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final desktopTheme = DesktopThemeExtension.of(context);
    final imageUrl = _imageUrl(
      access: access,
      item: item,
      imageType: 'Primary',
      maxWidth: 560,
    );
    final subtitle = _buildSubtitle(item);
    final badge = (item.id.hashCode.abs() % 320) + 1;
    final active = item.playbackPositionTicks > 0;

    return SizedBox(
      width: 186,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                curve: Curves.easeOut,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: active
                        ? const Color(0xFF5AC54A)
                        : desktopTheme.border.withValues(alpha: 0.65),
                    width: active ? 3 : 1,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.35),
                      blurRadius: 18,
                      offset: const Offset(0, 10),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(15),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      if (imageUrl != null)
                        CachedNetworkImage(
                          imageUrl: imageUrl,
                          fit: BoxFit.cover,
                          placeholder: (_, __) => _posterFallback(desktopTheme),
                          errorWidget: (_, __, ___) => _posterFallback(desktopTheme),
                        )
                      else
                        _posterFallback(desktopTheme),
                      Positioned(
                        top: 8,
                        right: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 7,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0xFF4CB948),
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(
                            '$badge',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ),
                      if (active)
                        Align(
                          alignment: Alignment.center,
                          child: Container(
                            width: 58,
                            height: 58,
                            decoration: BoxDecoration(
                              color: const Color(0xD94CB948),
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: const Icon(
                              Icons.play_arrow_rounded,
                              color: Colors.white,
                              size: 34,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              item.name.trim().isEmpty ? 'Untitled' : item.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: desktopTheme.textPrimary,
                fontSize: 15.5,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: desktopTheme.textMuted,
                fontSize: 12.5,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _posterFallback(DesktopThemeExtension desktopTheme) {
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
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({
    required this.message,
    required this.onRetry,
  });

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0x2EE14E4E),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0x66FF8C8C)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, color: Color(0xFFFFB3B3)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 10),
            TextButton(
              onPressed: onRetry,
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }
}

String _buildSubtitle(MediaItem item) {
  final year = mediaYear(item);
  if (year.isEmpty) {
    return item.played ? 'Completed' : 'Ongoing';
  }
  return item.played ? '$year • Completed' : '$year • Ongoing';
}

String? _imageUrl({
  required ServerAccess? access,
  required MediaItem? item,
  required String imageType,
  required int maxWidth,
}) {
  if (access == null || item == null) return null;
  if (imageType == 'Primary' && !item.hasImage) return null;
  return access.adapter.imageUrl(
    access.auth,
    itemId: item.id,
    imageType: imageType,
    maxWidth: maxWidth,
  );
}
