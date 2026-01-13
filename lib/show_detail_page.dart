import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'services/emby_api.dart';
import 'state/app_state.dart';
import 'play_network_page.dart';

class ShowDetailPage extends StatefulWidget {
  const ShowDetailPage({
    super.key,
    required this.itemId,
    required this.title,
    required this.appState,
    this.isTv = false,
  });

  final String itemId;
  final String title;
  final AppState appState;
  final bool isTv;

  @override
  State<ShowDetailPage> createState() => _ShowDetailPageState();
}

class _ShowDetailPageState extends State<ShowDetailPage> {
  MediaItem? _detail;
  List<MediaItem> _seasons = [];
  List<MediaItem> _similar = [];
  bool _loading = true;
  String? _error;
  MediaItem? _featuredEpisode;
  List<String> _album = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final api = EmbyApi(hostOrUrl: widget.appState.baseUrl!, preferredScheme: 'https');
    try {
      final detail = await api.fetchItemDetail(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        itemId: widget.itemId,
      );
      final seasons = await api.fetchSeasons(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        seriesId: widget.itemId,
      );
      MediaItem? firstEp;
      if (seasons.items.isNotEmpty) {
        final eps = await api.fetchEpisodes(
          token: widget.appState.token!,
          baseUrl: widget.appState.baseUrl!,
          userId: widget.appState.userId!,
          seasonId: seasons.items.first.id,
        );
        if (eps.items.isNotEmpty) firstEp = eps.items.first;
      }
      PagedResult<MediaItem> similar = PagedResult(const [], 0);
      try {
        similar = await api.fetchSimilar(
          token: widget.appState.token!,
          baseUrl: widget.appState.baseUrl!,
          userId: widget.appState.userId!,
          itemId: widget.itemId,
          limit: 12,
        );
      } catch (_) {}
      _album = [
        EmbyApi.imageUrl(
          baseUrl: widget.appState.baseUrl!,
          itemId: widget.itemId,
          token: widget.appState.token!,
          imageType: 'Primary',
          maxWidth: 800,
        ),
        EmbyApi.imageUrl(
          baseUrl: widget.appState.baseUrl!,
          itemId: widget.itemId,
          token: widget.appState.token!,
          imageType: 'Backdrop',
          maxWidth: 1200,
        ),
      ];
      setState(() {
        _detail = detail;
        _seasons = seasons.items;
        _featuredEpisode = firstEp;
        _similar = similar.items;
      });
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_error != null || _detail == null) {
      return Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        body: Center(child: Text(_error ?? '加载失败')),
      );
    }
    final item = _detail!;
    final hero = EmbyApi.imageUrl(
      baseUrl: widget.appState.baseUrl!,
      itemId: item.id,
      token: widget.appState.token!,
      imageType: 'Primary',
      maxWidth: 1200,
    );

    return Scaffold(
      body: RefreshIndicator(
        onRefresh: _load,
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              pinned: true,
              expandedHeight: 320,
              flexibleSpace: FlexibleSpaceBar(
                background: Stack(
                  fit: StackFit.expand,
                  children: [
                    Image.network(hero, fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => const ColoredBox(color: Colors.black26)),
                    Container(
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [Colors.transparent, Colors.black54],
                        ),
                      ),
                    ),
                    Positioned(
                      left: 16,
                      bottom: 16,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(item.name,
                              style: Theme.of(context)
                                  .textTheme
                                  .headlineSmall
                                  ?.copyWith(color: Colors.white, fontWeight: FontWeight.w700)),
                          const SizedBox(height: 8),
                          Wrap(
                            spacing: 8,
                            children: [
                              if (item.communityRating != null)
                                _pill(context, '★ ${item.communityRating!.toStringAsFixed(1)}'),
                              if (item.premiereDate != null)
                                _pill(context, item.premiereDate!.split('T').first),
                              if (item.genres.isNotEmpty) _pill(context, item.genres.first),
                              _pill(context, '${_seasons.length} 季'),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (_featuredEpisode != null)
                      _playButton(
                        context,
                        label: '播放 S${_featuredEpisode!.seasonNumber ?? 1}:E${_featuredEpisode!.episodeNumber ?? 1}',
                        onTap: () {
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => EpisodeDetailPage(
                                episode: _featuredEpisode!,
                                appState: widget.appState,
                                isTv: widget.isTv,
                              ),
                            ),
                          );
                        },
                    ),
                    const SizedBox(height: 12),
                    Text(item.overview, style: Theme.of(context).textTheme.bodyMedium),
                    const SizedBox(height: 16),
                    if (_album.isNotEmpty) ...[
                      Text('相册', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      SizedBox(
                        height: 140,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: _album.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 10),
                          itemBuilder: (context, index) {
                            final url = _album[index];
                            return ClipRRect(
                              borderRadius: BorderRadius.circular(12),
                              child: Image.network(url,
                                  width: 220,
                                  height: 140,
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, __, ___) =>
                                      const SizedBox(width: 220, height: 140, child: ColoredBox(color: Colors.black26))),
                            );
                          },
                        ),
                      ),
                      const SizedBox(height: 16),
                    ],
                    Text('季', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 10,
                      runSpacing: 10,
                      children: _seasons
                          .map((s) => ActionChip(
                                label: Text(s.name.isNotEmpty ? s.name : '季'),
                                onPressed: () {
                                  Navigator.of(context).push(
                                    MaterialPageRoute(
                                      builder: (_) => SeasonEpisodesPage(
                                        season: s,
                                        appState: widget.appState,
                                        isTv: widget.isTv,
                                      ),
                                    ),
                                  );
                                },
                              ))
                          .toList(),
                      ),
                    const SizedBox(height: 16),
                    if (_similar.isNotEmpty) ...[
                      Text('更多类似', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      SizedBox(
                        height: 240,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: _similar.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 12),
                          itemBuilder: (context, index) {
                            final s = _similar[index];
                            final img = s.hasImage
                                ? EmbyApi.imageUrl(
                                    baseUrl: widget.appState.baseUrl!,
                                    itemId: s.id,
                                    token: widget.appState.token!,
                                    maxWidth: 400,
                                  )
                                : null;
                            return InkWell(
                              onTap: () {
                                Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => ShowDetailPage(
                                      itemId: s.id,
                                      title: s.name,
                                      appState: widget.appState,
                                      isTv: widget.isTv,
                                    ),
                                  ),
                                );
                              },
                              child: SizedBox(
                                width: 140,
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    ClipRRect(
                                      borderRadius: BorderRadius.circular(10),
                                  child: img != null
                                          ? SizedBox(
                                              height: 180,
                                              width: 140,
                                              child: Image.network(img,
                                                  fit: BoxFit.cover,
                                                  errorBuilder: (_, __, ___) => const ColoredBox(color: Colors.black26)),
                                            )
                                          : const SizedBox(
                                              height: 180, width: 140, child: ColoredBox(color: Colors.black26)),
                                    ),
                                    const SizedBox(height: 6),
                                    Text(s.name,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: Theme.of(context).textTheme.bodyMedium),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                    const SizedBox(height: 16),
                    _externalLinksSection(context, item, widget.appState),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class SeasonEpisodesPage extends StatefulWidget {
  const SeasonEpisodesPage({
    super.key,
    required this.season,
    required this.appState,
    this.isTv = false,
  });

  final MediaItem season;
  final AppState appState;
  final bool isTv;

  @override
  State<SeasonEpisodesPage> createState() => _SeasonEpisodesPageState();
}

class _SeasonEpisodesPageState extends State<SeasonEpisodesPage> {
  List<MediaItem> _episodes = [];
  bool _loading = true;
  String? _error;
  MediaItem? _detailSeason;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final api = EmbyApi(hostOrUrl: widget.appState.baseUrl!, preferredScheme: 'https');
    try {
      final eps = await api.fetchEpisodes(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        seasonId: widget.season.id,
      );
      final detail = await api.fetchItemDetail(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        itemId: widget.season.id,
      );
      setState(() {
        _episodes = eps.items;
        _detailSeason = detail;
      });
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final seasonName = _detailSeason?.name ?? widget.season.name;
    return Scaffold(
      appBar: AppBar(title: Text(seasonName)),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text(_error!))
              : Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (_episodes.isNotEmpty)
                        GridView.builder(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 4,
                            childAspectRatio: 1.4,
                            crossAxisSpacing: 10,
                            mainAxisSpacing: 10,
                          ),
                          itemCount: _episodes.length,
                          itemBuilder: (context, index) {
                            final e = _episodes[index];
                            final dur = e.runTimeTicks != null
                                ? Duration(microseconds: (e.runTimeTicks! / 10).round())
                                : null;
                            return InkWell(
                              borderRadius: BorderRadius.circular(12),
                              onTap: () {
                                Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => EpisodeDetailPage(
                                      episode: e,
                                      appState: widget.appState,
                                      isTv: widget.isTv,
                                    ),
                                  ),
                                );
                              },
                              child: Container(
                                decoration: BoxDecoration(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .surfaceContainerHighest
                                      .withValues(alpha: 0.3),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                padding: const EdgeInsets.all(12),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text('${index + 1}',
                                        style: Theme.of(context)
                                            .textTheme
                                            .titleLarge
                                            ?.copyWith(fontWeight: FontWeight.bold)),
                                    const SizedBox(height: 6),
                                    Text(e.name,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: Theme.of(context).textTheme.bodyMedium),
                                    if (dur != null)
                                      Text(_fmt(dur),
                                          style: Theme.of(context).textTheme.bodySmall),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                    ],
                  ),
                ),
    );
  }
}

class EpisodeDetailPage extends StatefulWidget {
  const EpisodeDetailPage({
    super.key,
    required this.episode,
    required this.appState,
    this.isTv = false,
  });

  final MediaItem episode;
  final AppState appState;
  final bool isTv;

  @override
  State<EpisodeDetailPage> createState() => _EpisodeDetailPageState();
}

class _EpisodeDetailPageState extends State<EpisodeDetailPage> {
  PlaybackInfoResult? _playInfo;
  String? _error;
  bool _loading = true;
  MediaItem? _detail;
  List<ChapterInfo> _chapters = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final api = EmbyApi(hostOrUrl: widget.appState.baseUrl!, preferredScheme: 'https');
    try {
      final detail = await api.fetchItemDetail(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        itemId: widget.episode.id,
      );
      final info = await api.fetchPlaybackInfo(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        userId: widget.appState.userId!,
        deviceId: widget.appState.deviceId,
        itemId: widget.episode.id,
      );
      final chaps = await api.fetchChapters(
        token: widget.appState.token!,
        baseUrl: widget.appState.baseUrl!,
        itemId: widget.episode.id,
      );
      setState(() {
        _playInfo = info;
        _detail = detail;
        _chapters = chaps;
      });
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ep = widget.episode;
    return Scaffold(
      appBar: AppBar(title: Text(ep.name)),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text(_error!))
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(ep.name, style: Theme.of(context).textTheme.titleLarge),
                      const SizedBox(height: 6),
                      if (_detail?.overview.isNotEmpty == true) Text(_detail!.overview),
                      const SizedBox(height: 12),
                      if (_playInfo != null) _mediaSourcesSection(context, _playInfo!),
                      const SizedBox(height: 12),
                      _playButton(
                        context,
                        label: '播放',
                        onTap: () {
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => PlayNetworkPage(
                                title: ep.name,
                                itemId: ep.id,
                                appState: widget.appState,
                                isTv: widget.isTv,
                              ),
                            ),
                          );
                        },
                      ),
                      const SizedBox(height: 16),
                      if (_detail?.people.isNotEmpty == true)
                        _peopleSection(context, _detail!.people, widget.appState),
                      if (_playInfo != null) ...[
                        const SizedBox(height: 16),
                        _mediaInfo(context, _playInfo!),
                      ],
                      if (_chapters.isNotEmpty) ...[
                        const SizedBox(height: 16),
                        Text('章节', style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: _chapters
                              .map((c) => Chip(
                                    label: Text('${c.name} ${_fmt(c.start)}'),
                                  ))
                              .toList(),
                        ),
                      ],
                    ],
                  ),
                ),
    );
  }
}

String _fmt(Duration d) {
  final h = d.inHours;
  final m = d.inMinutes.remainder(60);
  final s = d.inSeconds.remainder(60);
  if (h > 0) return '${h}h ${m}m ${s}s';
  return '${m}m ${s}s';
}

Widget _pill(BuildContext context, String text) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text, style: const TextStyle(color: Colors.white)),
    );

Widget _playButton(BuildContext context, {required String label, required VoidCallback onTap}) {
  return GestureDetector(
    onTap: onTap,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(30),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.play_arrow, color: Theme.of(context).colorScheme.onPrimaryContainer),
          const SizedBox(width: 6),
          Text(label,
              style: TextStyle(
                  color: Theme.of(context).colorScheme.onPrimaryContainer,
                  fontWeight: FontWeight.w600,
                  fontSize: 16)),
        ],
      ),
    ),
  );
}

Widget _peopleSection(BuildContext context, List<MediaPerson> people, AppState appState) {
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('演职人员', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      SizedBox(
        height: 150,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: people.length,
          separatorBuilder: (_, __) => const SizedBox(width: 12),
          itemBuilder: (context, index) {
            final p = people[index];
            final img = EmbyApi.personImageUrl(
              baseUrl: appState.baseUrl ?? '',
              personId: p.id,
              token: appState.token ?? '',
              maxWidth: 200,
            );
            return Column(
              children: [
                CircleAvatar(
                  radius: 42,
                  backgroundImage: img.isNotEmpty ? NetworkImage(img) : null,
                  backgroundColor: Colors.white24,
                  child: img.isEmpty ? Text(p.name.isNotEmpty ? p.name[0] : '?') : null,
                ),
                const SizedBox(height: 6),
                Text(p.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(p.role, style: Theme.of(context).textTheme.bodySmall),
              ],
            );
          },
        ),
      ),
    ],
  );
}

Widget _mediaSourcesSection(BuildContext context, PlaybackInfoResult info) {
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('版本', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 6),
      ...info.mediaSources.map((ms) {
        final map = ms as Map<String, dynamic>;
        final name = map['Name'] ?? map['Container'] ?? '默认';
        final size = map['Size'] != null
            ? ' · ${(map['Size'] / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB'
            : '';
        final vc = map['VideoCodec'] ?? '';
        final ac = map['AudioCodec'] ?? '';
        return Card(
          child: ListTile(
            title: Text('$name$size'),
            subtitle: Text('$vc  $ac'),
          ),
        );
      }),
    ],
  );
}

Widget _mediaInfo(BuildContext context, PlaybackInfoResult info) {
  final map = info.mediaSources.first as Map<String, dynamic>;
  final streams = (map['MediaStreams'] as List?) ?? [];
  final video = streams.where((e) => (e as Map)['Type'] == 'Video').map((e) => e as Map).toList();
  final audio = streams.where((e) => (e as Map)['Type'] == 'Audio').map((e) => e as Map).toList();
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('媒体信息', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          _infoCard('视频', video.map((v) => '${v['DisplayTitle'] ?? ''}\n${v['Codec'] ?? ''}').join('\n')),
          _infoCard('音频', audio.map((a) => '${a['DisplayTitle'] ?? ''}\n${a['Codec'] ?? ''}').join('\n')),
        ],
      ),
    ],
  );
}

Widget _infoCard(String title, String body) => SizedBox(
      width: 220,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              Text(body, style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
      ),
    );

Widget _externalLinksSection(BuildContext context, MediaItem item, AppState appState) {
  final tmdbId = item.providerIds.entries
      .firstWhere((e) => e.key.toLowerCase().contains('tmdb'), orElse: () => const MapEntry('', ''))
      .value;
  if (tmdbId.isEmpty) return const SizedBox.shrink();
  final isSeries = item.type.toLowerCase() == 'series';
  final url = isSeries
      ? 'https://www.themoviedb.org/tv/$tmdbId'
      : 'https://www.themoviedb.org/movie/$tmdbId';
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('外部链接', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Wrap(
        spacing: 8,
        children: [
          ActionChip(
            avatar: const Icon(Icons.link),
            label: const Text('TMDB'),
            onPressed: () async {
              final opened = await launchUrlString(url);
              if (!opened && context.mounted) {
                ScaffoldMessenger.of(context)
                    .showSnackBar(const SnackBar(content: Text('无法打开链接')));
              }
            },
          ),
        ],
      )
    ],
  );
}
