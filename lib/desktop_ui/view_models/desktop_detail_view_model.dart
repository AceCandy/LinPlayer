import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';
import 'package:lin_player_state/lin_player_state.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../server_adapters/server_access.dart';

class DesktopDetailViewModel extends ChangeNotifier {
  static const Duration _kSeasonsCacheTtl = Duration(days: 7);
  static const Duration _kSeasonsCachePurgeThrottle = Duration(hours: 6);
  static const String _kSeasonsCachePrefix = 'desktopSeasonsCache_v1:';
  static int _seasonsCacheLastPurgeAtMs = 0;

  DesktopDetailViewModel({
    required this.appState,
    required MediaItem item,
    this.server,
  })  : _seedItem = item,
        _detail = item;

  final AppState appState;
  final ServerProfile? server;
  final MediaItem _seedItem;

  MediaItem _detail;
  List<MediaItem> _seasons = const <MediaItem>[];
  List<MediaItem> _episodes = const <MediaItem>[];
  List<MediaItem> _similar = const <MediaItem>[];
  List<MediaPerson> _people = const <MediaPerson>[];
  PlaybackInfoResult? _playbackInfo;
  bool _loading = false;
  bool _episodesLoading = false;
  String? _error;
  bool _favorite = false;
  ServerAccess? _access;
  bool _disposed = false;
  int _loadGeneration = 0;

  MediaItem get detail => _detail;
  List<MediaItem> get seasons => _seasons;
  List<MediaItem> get episodes => _episodes;
  bool get episodesLoading => _episodesLoading;
  List<MediaItem> get similar => _similar;
  List<MediaPerson> get people => _people;
  PlaybackInfoResult? get playbackInfo => _playbackInfo;
  bool get loading => _loading;
  String? get error => _error;
  bool get favorite => _favorite;
  ServerAccess? get access => _access;

  void toggleFavorite() {
    _favorite = !_favorite;
    _safeNotifyListeners();
  }

  String? itemImageUrl(
    MediaItem item, {
    String imageType = 'Primary',
    int maxWidth = 900,
  }) {
    final currentAccess = _access;
    if (currentAccess == null) return null;
    if (!item.hasImage && imageType == 'Primary') return null;
    return currentAccess.adapter.imageUrl(
      currentAccess.auth,
      itemId: item.id,
      imageType: imageType,
      maxWidth: maxWidth,
    );
  }

  String? personImageUrl(MediaPerson person, {int maxWidth = 300}) {
    final currentAccess = _access;
    if (currentAccess == null) return null;
    if (person.id.trim().isEmpty) return null;
    return currentAccess.adapter.personImageUrl(
      currentAccess.auth,
      personId: person.id,
      maxWidth: maxWidth,
    );
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  void _safeNotifyListeners() {
    if (_disposed) return;
    notifyListeners();
  }

  bool _isActiveLoad(int generation) =>
      !_disposed && _loadGeneration == generation;

  static String _seasonsCacheKey({
    required String serverId,
    required String seriesId,
  }) =>
      '$_kSeasonsCachePrefix$serverId:$seriesId';

  static String _resolveSeriesId(MediaItem item) {
    final type = item.type.trim().toLowerCase();
    if (type == 'series') return item.id.trim();

    final seriesId = (item.seriesId ?? '').trim();
    if (seriesId.isNotEmpty) return seriesId;

    if (type == 'season') return (item.parentId ?? '').trim();
    return '';
  }

  Future<List<MediaItem>> _fetchSimilarItems({
    required ServerAccess access,
    required MediaItem detailItem,
  }) async {
    final detailId = detailItem.id.trim();
    if (detailId.isEmpty) return const <MediaItem>[];

    return access.adapter
        .fetchSimilar(
          access.auth,
          itemId: detailId,
          limit: 36,
        )
        .then((result) => result.items)
        .catchError((_) => const <MediaItem>[]);
  }

  static int? _readIntOpt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.round();
    if (value is String) return int.tryParse(value.trim());
    return int.tryParse(value.toString().trim());
  }

  static Map<String, dynamic>? _coerceStringKeyedMap(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return value.map((k, v) => MapEntry(k.toString(), v));
    }
    return null;
  }

  static List<MediaItem> _sortSeasons(List<MediaItem> seasons) {
    final mutable = List<MediaItem>.from(seasons);
    mutable.sort((a, b) {
      final aNo = a.seasonNumber ?? a.episodeNumber ?? 0;
      final bNo = b.seasonNumber ?? b.episodeNumber ?? 0;
      final diff = aNo.compareTo(bNo);
      if (diff != 0) return diff;
      return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    });
    return mutable;
  }

  static List<MediaItem> _sortEpisodes(List<MediaItem> episodes) {
    final mutable = List<MediaItem>.from(episodes);
    mutable.sort((a, b) {
      final aSeason = a.seasonNumber ?? 0;
      final bSeason = b.seasonNumber ?? 0;
      final seasonDiff = aSeason.compareTo(bSeason);
      if (seasonDiff != 0) return seasonDiff;

      final aNo = a.episodeNumber ?? 0;
      final bNo = b.episodeNumber ?? 0;
      final diff = aNo.compareTo(bNo);
      if (diff != 0) return diff;
      return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    });
    return mutable;
  }

  Future<List<MediaItem>> _fetchEpisodePreview({
    required ServerAccess access,
    required String seasonId,
    String? seriesIdForRecursive,
  }) async {
    final resolvedSeasonId = seasonId.trim();
    final resolvedSeriesId = (seriesIdForRecursive ?? '').trim();
    if (resolvedSeasonId.isEmpty) return const <MediaItem>[];

    try {
      final episodeResult = await access.adapter.fetchEpisodes(
        access.auth,
        seasonId: resolvedSeasonId,
      );
      final preview =
          _sortEpisodes(episodeResult.items).take(24).toList(growable: false);
      if (preview.isNotEmpty || resolvedSeriesId.isEmpty) return preview;
    } catch (_) {
      if (resolvedSeriesId.isEmpty) return const <MediaItem>[];
    }

    try {
      final recursiveResult = await access.adapter.fetchItems(
        access.auth,
        parentId: resolvedSeriesId,
        includeItemTypes: 'Episode',
        recursive: true,
        limit: 200,
        sortBy: 'IndexNumber',
        sortOrder: 'Ascending',
      );
      return _sortEpisodes(recursiveResult.items)
          .take(24)
          .toList(growable: false);
    } catch (_) {
      return const <MediaItem>[];
    }
  }

  Future<void> _retryLoadEpisodes({
    required int loadGeneration,
    required ServerAccess access,
    required String seasonId,
    String? seriesIdForRecursive,
  }) async {
    const delays = <Duration>[
      Duration(seconds: 1),
      Duration(seconds: 2),
      Duration(seconds: 4),
      Duration(seconds: 8),
      Duration(seconds: 16),
    ];
    for (final delay in delays) {
      await Future.delayed(delay);
      if (!_isActiveLoad(loadGeneration)) return;

      final episodes = await _fetchEpisodePreview(
        access: access,
        seasonId: seasonId,
        seriesIdForRecursive: seriesIdForRecursive,
      );
      if (!_isActiveLoad(loadGeneration)) return;
      if (episodes.isEmpty) continue;

      _episodes = episodes;
      _episodesLoading = false;
      _safeNotifyListeners();
      return;
    }

    if (!_isActiveLoad(loadGeneration)) return;
    _episodesLoading = false;
    _safeNotifyListeners();
  }

  static Future<void> _purgeExpiredSeasonCache() async {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (nowMs - _seasonsCacheLastPurgeAtMs <=
        _kSeasonsCachePurgeThrottle.inMilliseconds) {
      return;
    }
    _seasonsCacheLastPurgeAtMs = nowMs;

    final prefs = await SharedPreferences.getInstance();
    final keys = prefs.getKeys();
    if (keys.isEmpty) return;

    for (final key in keys) {
      if (!key.startsWith(_kSeasonsCachePrefix)) continue;
      final raw = prefs.getString(key);
      if (raw == null || raw.trim().isEmpty) {
        await prefs.remove(key);
        continue;
      }

      try {
        final decoded = jsonDecode(raw);
        if (decoded is! Map) {
          await prefs.remove(key);
          continue;
        }
        final lastAccessAtMs = _readIntOpt(decoded['lastAccessAtMs']) ?? 0;
        if (lastAccessAtMs <= 0 ||
            nowMs - lastAccessAtMs > _kSeasonsCacheTtl.inMilliseconds) {
          await prefs.remove(key);
        }
      } catch (_) {
        await prefs.remove(key);
      }
    }
  }

  static Future<List<MediaItem>?> _restoreSeasonsFromCache({
    required String serverId,
    required String seriesId,
  }) async {
    if (serverId.trim().isEmpty || seriesId.trim().isEmpty) return null;

    final prefs = await SharedPreferences.getInstance();
    final key = _seasonsCacheKey(serverId: serverId.trim(), seriesId: seriesId);
    final raw = prefs.getString(key);
    if (raw == null || raw.trim().isEmpty) return null;

    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        await prefs.remove(key);
        return null;
      }

      final nowMs = DateTime.now().millisecondsSinceEpoch;
      final lastAccessAtMs = _readIntOpt(decoded['lastAccessAtMs']) ?? 0;
      if (lastAccessAtMs <= 0 ||
          nowMs - lastAccessAtMs > _kSeasonsCacheTtl.inMilliseconds) {
        await prefs.remove(key);
        return null;
      }

      final items = decoded['items'];
      if (items is! List) {
        await prefs.remove(key);
        return null;
      }

      final seasons = <MediaItem>[];
      for (final entry in items) {
        final map = _coerceStringKeyedMap(entry);
        if (map == null) continue;
        final item = MediaItem.fromJson(map);
        if (item.id.trim().isEmpty) continue;
        if (item.type.trim().toLowerCase() != 'season') continue;
        seasons.add(item);
      }

      if (seasons.isEmpty) {
        await prefs.remove(key);
        return null;
      }

      final updated = <String, dynamic>{
        'lastAccessAtMs': nowMs,
        'items': seasons.map((e) => e.toJson()).toList(growable: false),
      };
      await prefs.setString(key, jsonEncode(updated));

      return _sortSeasons(seasons);
    } catch (_) {
      await prefs.remove(key);
      return null;
    }
  }

  static Future<void> _persistSeasonsToCache({
    required String serverId,
    required String seriesId,
    required List<MediaItem> seasons,
  }) async {
    if (serverId.trim().isEmpty || seriesId.trim().isEmpty) return;
    if (seasons.isEmpty) return;

    final prefs = await SharedPreferences.getInstance();
    final key = _seasonsCacheKey(serverId: serverId.trim(), seriesId: seriesId);
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final data = <String, dynamic>{
      'lastAccessAtMs': nowMs,
      'items': seasons.map((e) => e.toJson()).toList(growable: false),
    };
    await prefs.setString(key, jsonEncode(data));
  }

  Future<void> load({bool forceRefresh = false}) async {
    if (_loading && !forceRefresh) return;

    final loadGeneration = ++_loadGeneration;
    _loading = true;
    _episodesLoading = false;
    _error = null;
    _safeNotifyListeners();

    unawaited(_purgeExpiredSeasonCache());

    final currentAccess =
        resolveServerAccess(appState: appState, server: server);
    if (currentAccess == null) {
      if (_isActiveLoad(loadGeneration)) {
        _loading = false;
        _episodesLoading = false;
        _error = 'No active media server session';
        _safeNotifyListeners();
      }
      return;
    }

    _access = currentAccess;
    _playbackInfo = null;

    try {
      final cacheServerId =
          (server?.id ?? appState.activeServerId ?? '').trim();
      final seedSeriesId = _resolveSeriesId(_seedItem);
      final cachedSeasonsFuture =
          !forceRefresh && cacheServerId.isNotEmpty && seedSeriesId.isNotEmpty
              ? _restoreSeasonsFromCache(
                  serverId: cacheServerId,
                  seriesId: seedSeriesId,
                )
              : Future<List<MediaItem>?>.value(null);

      final detailFuture = currentAccess.adapter
          .fetchItemDetail(
            currentAccess.auth,
            itemId: _seedItem.id,
          )
          .then<MediaItem>((value) => value)
          .catchError((_) => _seedItem);

      final cachedSeasons = await cachedSeasonsFuture;
      if (!_isActiveLoad(loadGeneration)) return;
      if (cachedSeasons != null && cachedSeasons.isNotEmpty) {
        _seasons = cachedSeasons;
        _safeNotifyListeners();
      }

      final detailItem = await detailFuture;
      if (!_isActiveLoad(loadGeneration)) return;
      _detail = detailItem;
      _people = detailItem.people;
      _safeNotifyListeners();

      List<MediaItem> seasons = const <MediaItem>[];

      final type = detailItem.type.trim().toLowerCase();
      final isMovie = type == 'movie';
      final isSeries = type == 'series';
      final isSeason = type == 'season';
      final isEpisode = type == 'episode';

      final Future<PlaybackInfoResult?> playbackFuture = (isEpisode || isMovie)
          ? currentAccess.adapter
              .fetchPlaybackInfo(
                currentAccess.auth,
                itemId: detailItem.id,
              )
              .then<PlaybackInfoResult?>((value) => value)
              .catchError((_) => null)
          : Future<PlaybackInfoResult?>.value(null);

      final seriesId = _resolveSeriesId(detailItem);
      seasons = cachedSeasons ?? const <MediaItem>[];

      if (seasons.isEmpty &&
          !forceRefresh &&
          cacheServerId.isNotEmpty &&
          seriesId.isNotEmpty &&
          seriesId != seedSeriesId) {
        final resolvedCached = await _restoreSeasonsFromCache(
          serverId: cacheServerId,
          seriesId: seriesId,
        );
        if (resolvedCached != null && resolvedCached.isNotEmpty) {
          seasons = resolvedCached;
          _seasons = seasons;
          _safeNotifyListeners();
        }
      }

      if ((forceRefresh || seasons.isEmpty) && seriesId.isNotEmpty) {
        List<MediaItem> fetchedSeasons = const <MediaItem>[];
        try {
          final seasonResult = await currentAccess.adapter.fetchSeasons(
            currentAccess.auth,
            seriesId: seriesId,
          );
          fetchedSeasons = seasonResult.items
              .where((s) => s.type.trim().toLowerCase() == 'season')
              .toList(growable: false);
        } catch (_) {
          fetchedSeasons = const <MediaItem>[];
        }

        fetchedSeasons = _sortSeasons(fetchedSeasons);
        if (fetchedSeasons.isNotEmpty) {
          seasons = fetchedSeasons;
          _seasons = seasons;
          _safeNotifyListeners();
          if (cacheServerId.isNotEmpty) {
            unawaited(
              _persistSeasonsToCache(
                serverId: cacheServerId,
                seriesId: seriesId,
                seasons: seasons,
              ),
            );
          }
        }
      } else if (seasons.isNotEmpty) {
        seasons = _sortSeasons(seasons);
        if (!listEquals(_seasons, seasons)) {
          _seasons = seasons;
          _safeNotifyListeners();
        }
      }

      if (seasons.isEmpty && seriesId.isNotEmpty) {
        if (isSeason) {
          seasons = [detailItem];
        } else if (isEpisode && (detailItem.parentId ?? '').trim().isNotEmpty) {
          final sNo = detailItem.seasonNumber ?? 1;
          final seasonName = detailItem.seasonName.trim().isNotEmpty
              ? detailItem.seasonName.trim()
              : '第$sNo季';
          seasons = [
            MediaItem(
              id: detailItem.parentId!.trim(),
              name: seasonName,
              type: 'Season',
              overview: '',
              communityRating: null,
              premiereDate: null,
              genres: const [],
              runTimeTicks: null,
              sizeBytes: null,
              container: null,
              providerIds: const {},
              seriesId: seriesId,
              seriesName: detailItem.seriesName,
              seasonName: seasonName,
              seasonNumber: sNo,
              episodeNumber: null,
              hasImage: detailItem.hasImage,
              playbackPositionTicks: 0,
              people: const [],
              parentId: seriesId,
            ),
          ];
        } else if (isSeries) {
          seasons = [
            MediaItem(
              id: seriesId,
              name: '第1季',
              type: 'Season',
              overview: '',
              communityRating: null,
              premiereDate: null,
              genres: const [],
              runTimeTicks: null,
              sizeBytes: null,
              container: null,
              providerIds: const {},
              seriesId: seriesId,
              seriesName: detailItem.name,
              seasonName: '第1季',
              seasonNumber: 1,
              episodeNumber: null,
              hasImage: detailItem.hasImage,
              playbackPositionTicks: 0,
              people: const [],
              parentId: seriesId,
            ),
          ];
        }
      }

      if (seasons.isNotEmpty && !listEquals(_seasons, seasons)) {
        _seasons = seasons;
        _safeNotifyListeners();
      }

      final seasonIdForEpisodes = (isSeason
              ? detailItem.id.trim()
              : isEpisode
                  ? (detailItem.parentId ?? '').trim()
                  : seasons.isNotEmpty
                      ? seasons.first.id.trim()
                      : isSeries
                          ? detailItem.id.trim()
                          : (detailItem.parentId ?? '').trim())
          .trim();

      if (!isMovie && seasonIdForEpisodes.isNotEmpty) {
        _episodesLoading = true;
        _safeNotifyListeners();

        final recursiveFallbackId =
            isSeries && seasonIdForEpisodes == seriesId ? seriesId : null;
        final episodePreview = await _fetchEpisodePreview(
          access: currentAccess,
          seasonId: seasonIdForEpisodes,
          seriesIdForRecursive: recursiveFallbackId,
        );
        if (!_isActiveLoad(loadGeneration)) return;
        _episodes = episodePreview;
        _safeNotifyListeners();

        if (episodePreview.isNotEmpty) {
          _episodesLoading = false;
          _safeNotifyListeners();
        } else {
          unawaited(
            _retryLoadEpisodes(
              loadGeneration: loadGeneration,
              access: currentAccess,
              seasonId: seasonIdForEpisodes,
              seriesIdForRecursive: recursiveFallbackId,
            ),
          );
        }
      } else {
        _episodesLoading = false;
      }

      final playbackInfo = await playbackFuture;
      if (!_isActiveLoad(loadGeneration)) return;
      _playbackInfo = playbackInfo;

      final relatedItems = await _fetchSimilarItems(
        access: currentAccess,
        detailItem: detailItem,
      ).catchError((_) => const <MediaItem>[]);
      if (!_isActiveLoad(loadGeneration)) return;
      _similar = relatedItems
          .where((item) => item.id.trim().isNotEmpty && item.id != detailItem.id)
          .toList(growable: false);
    } catch (e) {
      if (_isActiveLoad(loadGeneration)) {
        _error = e.toString();
        _playbackInfo = null;
        _episodesLoading = false;
      }
    } finally {
      if (_isActiveLoad(loadGeneration)) {
        _loading = false;
        _safeNotifyListeners();
      }
    }
  }
}
