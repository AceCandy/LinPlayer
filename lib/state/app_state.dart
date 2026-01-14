import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/emby_api.dart';
import 'server_profile.dart';

class AppState extends ChangeNotifier {
  static const _kServersKey = 'servers_v1';
  static const _kActiveServerIdKey = 'activeServerId_v1';
  static const _kThemeModeKey = 'themeMode_v1';
  static const _kDynamicColorKey = 'dynamicColor_v1';

  final List<ServerProfile> _servers = [];
  String? _activeServerId;

  List<DomainInfo> _domains = [];
  List<LibraryInfo> _libraries = [];
  final Map<String, List<MediaItem>> _itemsCache = {};
  final Map<String, int> _itemsTotal = {};
  final Map<String, List<MediaItem>> _homeSections = {};
  late final String _deviceId = _randomId();
  ThemeMode _themeMode = ThemeMode.system;
  bool _useDynamicColor = true;
  bool _loading = false;
  String? _error;

  static String _randomId() {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    final rand = DateTime.now().microsecondsSinceEpoch;
    return List.generate(16, (i) => chars[(rand + i * 31) % chars.length]).join();
  }

  List<ServerProfile> get servers => List.unmodifiable(_servers);
  String? get activeServerId => _activeServerId;
  ServerProfile? get activeServer => _servers.firstWhereOrNull((s) => s.id == _activeServerId);
  bool get hasActiveServer => activeServer != null;

  String? get baseUrl => activeServer?.baseUrl;
  String? get token => activeServer?.token;
  String? get userId => activeServer?.userId;

  String get deviceId => _deviceId;
  List<DomainInfo> get domains => _domains;
  List<LibraryInfo> get libraries => _libraries;
  List<MediaItem> getItems(String parentId) => _itemsCache[parentId] ?? [];
  int getTotal(String parentId) => _itemsTotal[parentId] ?? 0;
  List<MediaItem> getHome(String key) => _homeSections[key] ?? [];
  ThemeMode get themeMode => _themeMode;
  bool get useDynamicColor => _useDynamicColor;

  Iterable<HomeEntry> get homeEntries sync* {
    for (final entry in _homeSections.entries) {
      if (!entry.key.startsWith('lib_')) continue;
      final libId = entry.key.substring(4);
      if (activeServer?.hiddenLibraries.contains(libId) == true) continue;
      final name = _libraries
          .firstWhere(
            (l) => l.id == libId,
            orElse: () => LibraryInfo(id: libId, name: '未知媒体库', type: ''),
          )
          .name;
      yield HomeEntry(key: entry.key, displayName: name, items: entry.value);
    }
  }

  bool get isLoading => _loading;
  String? get error => _error;

  Future<void> loadFromStorage() async {
    final prefs = await SharedPreferences.getInstance();

    _themeMode = _decodeThemeMode(prefs.getString(_kThemeModeKey));
    _useDynamicColor = prefs.getBool(_kDynamicColorKey) ?? true;

    final rawServers = prefs.getString(_kServersKey);
    _servers.clear();
    if (rawServers != null && rawServers.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(rawServers);
        if (decoded is List) {
          for (final item in decoded) {
            if (item is Map<String, dynamic>) {
              final s = ServerProfile.fromJson(item);
              if (s.id.isNotEmpty && s.baseUrl.isNotEmpty && s.token.isNotEmpty) {
                _servers.add(s);
              }
            }
          }
        }
      } catch (_) {
        // ignore broken storage
      }
    }

    // Migration from the old single-server storage keys.
    if (_servers.isEmpty) {
      final baseUrl = prefs.getString('baseUrl');
      final token = prefs.getString('token');
      final userId = prefs.getString('userId');
      if (baseUrl != null && token != null && userId != null) {
        _servers.add(
          ServerProfile(
            id: _randomId(),
            name: _suggestServerName(baseUrl),
            baseUrl: baseUrl,
            token: token,
            userId: userId,
            hiddenLibraries: (prefs.getStringList('hiddenLibs') ?? const <String>[]).toSet(),
          ),
        );
        await _persistServers(prefs);
      }
    }

    _activeServerId = prefs.getString(_kActiveServerIdKey);
    if (_activeServerId != null && activeServer == null) {
      _activeServerId = null;
      await prefs.remove(_kActiveServerIdKey);
    }

    notifyListeners();
  }

  Future<void> leaveServer() async {
    _activeServerId = null;
    _domains = [];
    _libraries = [];
    _itemsCache.clear();
    _itemsTotal.clear();
    _homeSections.clear();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kActiveServerIdKey);
    notifyListeners();
  }

  Future<void> addServer({
    required String hostOrUrl,
    required String scheme,
    String? port,
    required String username,
    required String password,
    String? displayName,
    String? remark,
  }) async {
    _loading = true;
    _error = null;
    notifyListeners();

    try {
      final api = EmbyApi(hostOrUrl: hostOrUrl, preferredScheme: scheme, port: port);
      final auth = await api.authenticate(
        username: username,
        password: password,
        deviceId: _deviceId,
      );

      final lines = await api.fetchDomains(auth.token, auth.baseUrlUsed, allowFailure: true);
      final libs = await api.fetchLibraries(
        token: auth.token,
        baseUrl: auth.baseUrlUsed,
        userId: auth.userId,
      );

      final name = (displayName ?? '').trim().isNotEmpty
          ? displayName!.trim()
          : _suggestServerName(auth.baseUrlUsed);

      final existingIndex = _servers.indexWhere((s) => s.baseUrl == auth.baseUrlUsed);
      final server = ServerProfile(
        id: existingIndex >= 0 ? _servers[existingIndex].id : _randomId(),
        name: name,
        remark: (remark ?? '').trim().isEmpty ? null : remark!.trim(),
        baseUrl: auth.baseUrlUsed,
        token: auth.token,
        userId: auth.userId,
        hiddenLibraries: existingIndex >= 0 ? _servers[existingIndex].hiddenLibraries : null,
        domainRemarks: existingIndex >= 0 ? _servers[existingIndex].domainRemarks : null,
      );

      if (existingIndex >= 0) {
        _servers[existingIndex] = server;
      } else {
        _servers.add(server);
      }

      _activeServerId = server.id;
      _domains = lines;
      _libraries = libs;
      _itemsCache.clear();
      _itemsTotal.clear();
      _homeSections.clear();

      final prefs = await SharedPreferences.getInstance();
      await _persistServers(prefs);
      await prefs.setString(_kActiveServerIdKey, server.id);
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> enterServer(String serverId) async {
    if (_activeServerId != serverId) {
      final server = _servers.firstWhereOrNull((s) => s.id == serverId);
      if (server == null) return;

      _activeServerId = serverId;
      _domains = [];
      _libraries = [];
      _itemsCache.clear();
      _itemsTotal.clear();
      _homeSections.clear();
      _error = null;

      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_kActiveServerIdKey, serverId);
      notifyListeners();
    }

    await refreshDomains();
    await refreshLibraries();
    await loadHome();
  }

  Future<void> removeServer(String serverId) async {
    final idx = _servers.indexWhere((s) => s.id == serverId);
    if (idx < 0) return;
    final removingActive = _activeServerId == serverId;
    _servers.removeAt(idx);
    final prefs = await SharedPreferences.getInstance();
    await _persistServers(prefs);
    if (removingActive) {
      await leaveServer();
    } else {
      notifyListeners();
    }
  }

  Future<void> updateServerMeta(
    String serverId, {
    String? name,
    String? remark,
  }) async {
    final server = _servers.firstWhereOrNull((s) => s.id == serverId);
    if (server == null) return;
    if (name != null && name.trim().isNotEmpty) server.name = name.trim();
    if (remark != null) server.remark = remark.trim().isEmpty ? null : remark.trim();
    final prefs = await SharedPreferences.getInstance();
    await _persistServers(prefs);
    notifyListeners();
  }

  Future<void> refreshDomains() async {
    if (baseUrl == null || token == null) return;
    _loading = true;
    notifyListeners();
    try {
      final api = EmbyApi(hostOrUrl: baseUrl!, preferredScheme: 'https');
      _domains = await api.fetchDomains(token!, baseUrl!, allowFailure: true);
      _error = null;
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> refreshLibraries() async {
    if (baseUrl == null || token == null || userId == null) return;
    _loading = true;
    notifyListeners();
    try {
      final api = EmbyApi(hostOrUrl: baseUrl!, preferredScheme: 'https');
      _libraries = await api.fetchLibraries(
        token: token!,
        baseUrl: baseUrl!,
        userId: userId!,
      );
      _itemsCache.clear();
      _itemsTotal.clear();
      _homeSections.clear();
      _error = null;
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> loadItems({
    required String parentId,
    int startIndex = 0,
    int limit = 30,
    String? includeItemTypes,
    String? searchTerm,
    bool recursive = false,
    bool excludeFolders = true,
    String? sortBy,
    String sortOrder = 'Descending',
  }) async {
    if (baseUrl == null || token == null || userId == null) {
      throw Exception('未选择服务器');
    }
    final api = EmbyApi(hostOrUrl: baseUrl!, preferredScheme: 'https');
    final result = await api.fetchItems(
      token: token!,
      baseUrl: baseUrl!,
      userId: userId!,
      parentId: parentId,
      startIndex: startIndex,
      limit: limit,
      includeItemTypes: includeItemTypes,
      searchTerm: searchTerm,
      recursive: recursive,
      excludeFolders: excludeFolders,
      sortBy: sortBy,
      sortOrder: sortOrder,
    );
    final list = _itemsCache[parentId] ?? [];
    if (startIndex == 0) {
      _itemsCache[parentId] = result.items;
    } else {
      list.addAll(result.items);
      _itemsCache[parentId] = list;
    }
    _itemsTotal[parentId] = result.total;
    notifyListeners();
  }

  Future<void> loadHome() async {
    if (baseUrl == null || token == null || userId == null) return;
    final api = EmbyApi(hostOrUrl: baseUrl!, preferredScheme: 'https');
    final Map<String, List<MediaItem>> libraryShows = {};
    for (final lib in _libraries) {
      try {
        final fetched = await api.fetchItems(
          token: token!,
          baseUrl: baseUrl!,
          userId: userId!,
          parentId: lib.id,
          includeItemTypes: 'Series,Movie',
          recursive: true,
          excludeFolders: false,
          limit: 12,
          sortBy: 'DateCreated',
        );
        libraryShows['lib_${lib.id}'] = fetched.items;
        _itemsTotal[lib.id] = fetched.total;
      } catch (_) {
        // ignore failures per library
      }
    }
    _homeSections
      ..clear()
      ..addAll(libraryShows);
    notifyListeners();
  }

  Future<void> setBaseUrl(String url) async {
    final server = activeServer;
    if (server == null) return;
    server.baseUrl = url;
    final prefs = await SharedPreferences.getInstance();
    await _persistServers(prefs);
    notifyListeners();
  }

  String? domainRemark(String url) => activeServer?.domainRemarks[url];

  Future<void> setDomainRemark(String url, String? remark) async {
    final server = activeServer;
    if (server == null) return;
    final v = (remark ?? '').trim();
    if (v.isEmpty) {
      server.domainRemarks.remove(url);
    } else {
      server.domainRemarks[url] = v;
    }
    final prefs = await SharedPreferences.getInstance();
    await _persistServers(prefs);
    notifyListeners();
  }

  void toggleLibraryHidden(String libId) async {
    final server = activeServer;
    if (server == null) return;
    if (server.hiddenLibraries.contains(libId)) {
      server.hiddenLibraries.remove(libId);
    } else {
      server.hiddenLibraries.add(libId);
    }
    final prefs = await SharedPreferences.getInstance();
    await _persistServers(prefs);
    notifyListeners();
  }

  bool isLibraryHidden(String libId) => activeServer?.hiddenLibraries.contains(libId) == true;

  void sortLibrariesByName() {
    _libraries.sort((a, b) => a.name.compareTo(b.name));
    notifyListeners();
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kThemeModeKey, _encodeThemeMode(mode));
    notifyListeners();
  }

  Future<void> setUseDynamicColor(bool enabled) async {
    _useDynamicColor = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kDynamicColorKey, enabled);
    notifyListeners();
  }

  static ThemeMode _decodeThemeMode(String? raw) {
    switch (raw) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }

  static String _encodeThemeMode(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.light:
        return 'light';
      case ThemeMode.dark:
        return 'dark';
      case ThemeMode.system:
        return 'system';
    }
  }

  static String _suggestServerName(String baseUrl) {
    try {
      final uri = Uri.parse(baseUrl);
      if (uri.host.isNotEmpty) return uri.host;
    } catch (_) {}
    return baseUrl;
  }

  Future<void> _persistServers(SharedPreferences prefs) async {
    await prefs.setString(
      _kServersKey,
      jsonEncode(_servers.map((s) => s.toJson()).toList()),
    );
  }
}

class HomeEntry {
  final String key;
  final String displayName;
  final List<MediaItem> items;
  HomeEntry({required this.key, required this.displayName, required this.items});
}

extension _FirstWhereOrNull<E> on List<E> {
  E? firstWhereOrNull(bool Function(E) test) {
    for (final e in this) {
      if (test(e)) return e;
    }
    return null;
  }
}
