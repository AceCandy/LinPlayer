import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/emby_api.dart';

class AppState extends ChangeNotifier {
  String? _baseUrl;
  String? _token;
  String? _userId;
  List<DomainInfo> _domains = [];
  List<LibraryInfo> _libraries = [];
  final Map<String, List<MediaItem>> _itemsCache = {};
  final Map<String, int> _itemsTotal = {};
  final Map<String, List<MediaItem>> _homeSections = {};
  bool _loading = false;
  String? _error;

  String? get baseUrl => _baseUrl;
  String? get token => _token;
  String? get userId => _userId;
  List<DomainInfo> get domains => _domains;
  List<LibraryInfo> get libraries => _libraries;
  List<MediaItem> getItems(String parentId) => _itemsCache[parentId] ?? [];
  int getTotal(String parentId) => _itemsTotal[parentId] ?? 0;
  List<MediaItem> getHome(String key) => _homeSections[key] ?? [];
  bool get isLoading => _loading;
  String? get error => _error;

  Future<void> loadFromStorage() async {
    final prefs = await SharedPreferences.getInstance();
    _baseUrl = prefs.getString('baseUrl');
    _token = prefs.getString('token');
    _userId = prefs.getString('userId');
    notifyListeners();
  }

  Future<void> logout() async {
    _baseUrl = null;
    _token = null;
    _userId = null;
    _domains = [];
    _libraries = [];
    _itemsCache.clear();
    _itemsTotal.clear();
    _homeSections.clear();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('baseUrl');
    await prefs.remove('token');
    await prefs.remove('userId');
    notifyListeners();
  }

  Future<void> login({
    required String hostOrUrl,
    required String scheme,
    String? port,
    required String username,
    required String password,
  }) async {
    _loading = true;
    _error = null;
    notifyListeners();

    try {
      final api = EmbyApi(hostOrUrl: hostOrUrl, preferredScheme: scheme, port: port);
      final auth = await api.authenticate(username: username, password: password);
      final lines = await api.fetchDomains(auth.token, auth.baseUrlUsed, allowFailure: true);
      final libs = await api.fetchLibraries(
        token: auth.token,
        baseUrl: auth.baseUrlUsed,
        userId: auth.userId,
      );
      _baseUrl = auth.baseUrlUsed;
      _token = auth.token;
      _userId = auth.userId;
      _domains = lines;
      _libraries = libs;
      _itemsCache.clear();
      _itemsTotal.clear();
      _homeSections.clear();

      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('baseUrl', auth.baseUrlUsed);
      await prefs.setString('token', auth.token);
      await prefs.setString('userId', auth.userId);
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> refreshDomains() async {
    if (_baseUrl == null || _token == null) return;
    _loading = true;
    notifyListeners();
    try {
      final api = EmbyApi(hostOrUrl: _baseUrl!, preferredScheme: 'https');
      _domains = await api.fetchDomains(_token!, _baseUrl!, allowFailure: true);
      _error = null;
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> refreshLibraries() async {
    if (_baseUrl == null || _token == null) return;
    _loading = true;
    notifyListeners();
    try {
      final api = EmbyApi(hostOrUrl: _baseUrl!, preferredScheme: 'https');
      _libraries = await api.fetchLibraries(
        token: _token!,
        baseUrl: _baseUrl!,
        userId: _userId!,
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
  }) async {
    if (_baseUrl == null || _token == null || _userId == null) {
      throw Exception('未登录');
    }
    final api = EmbyApi(hostOrUrl: _baseUrl!, preferredScheme: 'https');
    final result = await api.fetchItems(
      token: _token!,
      baseUrl: _baseUrl!,
      userId: _userId!,
      parentId: parentId,
      startIndex: startIndex,
      limit: limit,
      includeItemTypes: includeItemTypes,
      searchTerm: searchTerm,
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
    if (_baseUrl == null || _token == null || _userId == null) return;
    final api = EmbyApi(hostOrUrl: _baseUrl!, preferredScheme: 'https');
    final cw = await api.fetchContinueWatching(
      token: _token!,
      baseUrl: _baseUrl!,
      userId: _userId!,
      limit: 20,
    );
    final movies = await api.fetchLatestMovies(
      token: _token!,
      baseUrl: _baseUrl!,
      userId: _userId!,
      limit: 20,
    );
    final eps = await api.fetchLatestEpisodes(
      token: _token!,
      baseUrl: _baseUrl!,
      userId: _userId!,
      limit: 20,
    );
    _homeSections['continue'] = cw.items;
    _homeSections['movies'] = movies.items;
    _homeSections['episodes'] = eps.items;
    notifyListeners();
  }
}
