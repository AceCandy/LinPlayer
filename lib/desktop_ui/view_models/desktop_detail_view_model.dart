import 'package:flutter/foundation.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';
import 'package:lin_player_state/lin_player_state.dart';

import '../../server_adapters/server_access.dart';

class DesktopDetailViewModel extends ChangeNotifier {
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
  String? _error;
  bool _favorite = false;
  ServerAccess? _access;

  MediaItem get detail => _detail;
  List<MediaItem> get seasons => _seasons;
  List<MediaItem> get episodes => _episodes;
  List<MediaItem> get similar => _similar;
  List<MediaPerson> get people => _people;
  PlaybackInfoResult? get playbackInfo => _playbackInfo;
  bool get loading => _loading;
  String? get error => _error;
  bool get favorite => _favorite;
  ServerAccess? get access => _access;

  void toggleFavorite() {
    _favorite = !_favorite;
    notifyListeners();
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

  Future<void> load({bool forceRefresh = false}) async {
    if (_loading && !forceRefresh) return;

    _loading = true;
    _error = null;
    notifyListeners();

    final currentAccess =
        resolveServerAccess(appState: appState, server: server);
    if (currentAccess == null) {
      _loading = false;
      _error = 'No active media server session';
      notifyListeners();
      return;
    }

    _access = currentAccess;
    _playbackInfo = null;

    try {
      var detailItem = _seedItem;
      try {
        detailItem = await currentAccess.adapter.fetchItemDetail(
          currentAccess.auth,
          itemId: _seedItem.id,
        );
      } catch (_) {
        // Keep seed item as fallback when detail API partially fails.
      }

      final similarFuture = currentAccess.adapter
          .fetchSimilar(
            currentAccess.auth,
            itemId: detailItem.id,
            limit: 30,
          )
          .then((result) => result.items)
          .catchError((_) => const <MediaItem>[]);
      final Future<PlaybackInfoResult?> playbackFuture = currentAccess.adapter
          .fetchPlaybackInfo(
            currentAccess.auth,
            itemId: detailItem.id,
          )
          .then<PlaybackInfoResult?>((value) => value)
          .catchError((_) => null);

      List<MediaItem> seasons = const <MediaItem>[];
      List<MediaItem> episodes = const <MediaItem>[];

      final type = detailItem.type.trim().toLowerCase();
      final isSeries = type == 'series';
      final isSeason = type == 'season';
      final isEpisode = type == 'episode';

      final seriesId = (isSeries
              ? detailItem.id
              : (detailItem.seriesId ?? '').trim().isNotEmpty
                  ? detailItem.seriesId!.trim()
                  : (isSeason ? (detailItem.parentId ?? '').trim() : ''))
          .trim();

      if (seriesId.isNotEmpty) {
        try {
          final seasonResult = await currentAccess.adapter.fetchSeasons(
            currentAccess.auth,
            seriesId: seriesId,
          );
          seasons = seasonResult.items
              .where((s) => s.type.trim().toLowerCase() == 'season')
              .toList(growable: false);
        } catch (_) {
          seasons = const <MediaItem>[];
        }

        final mutable = List<MediaItem>.from(seasons);
        mutable.sort((a, b) {
          final aNo = a.seasonNumber ?? a.episodeNumber ?? 0;
          final bNo = b.seasonNumber ?? b.episodeNumber ?? 0;
          final diff = aNo.compareTo(bNo);
          if (diff != 0) return diff;
          return a.name.toLowerCase().compareTo(b.name.toLowerCase());
        });
        seasons = mutable;
      }

      if (seasons.isEmpty && seriesId.isNotEmpty) {
        if (isSeason) {
          seasons = [detailItem];
        } else if (isEpisode && (detailItem.parentId ?? '').trim().isNotEmpty) {
          final sNo = detailItem.seasonNumber ?? 1;
          final seasonName = detailItem.seasonName.trim().isNotEmpty
              ? detailItem.seasonName.trim()
              : 'Season $sNo';
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
              name: 'Season 1',
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
              seasonName: 'Season 1',
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

      if (seasonIdForEpisodes.isNotEmpty) {
        try {
          final episodeResult = await currentAccess.adapter.fetchEpisodes(
            currentAccess.auth,
            seasonId: seasonIdForEpisodes,
          );
          final items = List<MediaItem>.from(episodeResult.items);
          items.sort((a, b) {
            final aNo = a.episodeNumber ?? 0;
            final bNo = b.episodeNumber ?? 0;
            final diff = aNo.compareTo(bNo);
            if (diff != 0) return diff;
            return a.name.toLowerCase().compareTo(b.name.toLowerCase());
          });
          episodes = items.take(24).toList(growable: false);
        } catch (_) {
          episodes = const <MediaItem>[];
        }
      }

      final similarItems = await similarFuture;
      final playbackInfo = await playbackFuture;

      _detail = detailItem;
      _seasons = seasons;
      _episodes = episodes;
      _similar =
          similarItems.where((item) => item.id != detailItem.id).toList();
      _people = detailItem.people;
      _playbackInfo = playbackInfo;
    } catch (e) {
      _error = e.toString();
      _playbackInfo = null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }
}
