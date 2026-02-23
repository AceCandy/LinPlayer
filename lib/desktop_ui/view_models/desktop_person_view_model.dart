import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';
import 'package:lin_player_state/lin_player_state.dart';

import '../../server_adapters/server_access.dart';

class DesktopPersonViewModel extends ChangeNotifier {
  DesktopPersonViewModel({
    required this.appState,
    required this.personId,
    this.server,
    String? seedName,
  }) : _seedName = (seedName ?? '').trim();

  final AppState appState;
  final String personId;
  final ServerProfile? server;
  final String _seedName;

  MediaItem? _person;
  List<MediaItem> _credits = const <MediaItem>[];
  bool _loading = false;
  String? _error;
  ServerAccess? _access;
  bool _disposed = false;

  MediaItem? get person => _person;
  List<MediaItem> get credits => _credits;
  bool get loading => _loading;
  String? get error => _error;
  ServerAccess? get access => _access;

  String get displayName {
    final name = (_person?.name ?? '').trim();
    if (name.isNotEmpty) return name;
    return _seedName;
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

  Future<void> load({bool forceRefresh = false}) async {
    if (_loading && !forceRefresh) return;

    final id = personId.trim();
    if (id.isEmpty) {
      _error = 'Invalid person id';
      _safeNotifyListeners();
      return;
    }

    _loading = true;
    _error = null;
    _safeNotifyListeners();

    final currentAccess =
        resolveServerAccess(appState: appState, server: server);
    if (currentAccess == null) {
      _loading = false;
      _error = 'No active media server session';
      _safeNotifyListeners();
      return;
    }

      _access = currentAccess;

    try {
      final personFuture = currentAccess.adapter
          .fetchItemDetail(
            currentAccess.auth,
            itemId: id,
          )
          .then<MediaItem?>((value) => value)
          .catchError((_) => null);

      final creditsFuture = currentAccess.adapter
          .fetchItems(
            currentAccess.auth,
            includeItemTypes: 'Movie,Series',
            recursive: true,
            limit: 80,
            sortBy: 'PremiereDate',
            sortOrder: 'Descending',
            personIds: [id],
          )
          .then((result) => result.items)
          .catchError((_) => const <MediaItem>[]);

      final personItem = await personFuture;
      final credits = await creditsFuture;

      _person = personItem;
      _credits = _uniqueById(credits);
    } catch (e) {
      _error = e.toString();
      _person = null;
      _credits = const <MediaItem>[];
    } finally {
      _loading = false;
      _safeNotifyListeners();
    }
  }

  static List<MediaItem> _uniqueById(List<MediaItem> items) {
    final seen = <String>{};
    final result = <MediaItem>[];
    for (final item in items) {
      final id = item.id.trim();
      if (id.isEmpty) continue;
      if (!seen.add(id)) continue;
      result.add(item);
    }
    return result;
  }
}
