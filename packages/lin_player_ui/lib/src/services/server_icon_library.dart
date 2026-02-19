import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import 'package:lin_player_server_api/network/lin_http_client.dart';

class ServerIconLibrarySource {
  const ServerIconLibrarySource({
    required this.id,
    required this.url,
    required this.library,
    required this.error,
  });

  final String id;
  final String? url;
  final ServerIconLibrary? library;
  final Object? error;

  String get displayName {
    final name = (library?.name ?? '').trim();
    if (name.isNotEmpty) return name;
    return (url ?? id).trim();
  }
}

class ServerIconLibrary {
  const ServerIconLibrary({
    required this.name,
    required this.description,
    required this.icons,
  });

  final String name;
  final String description;
  final List<ServerIconEntry> icons;

  static const Duration defaultTimeout = Duration(seconds: 8);

  static final Map<String, Future<ServerIconLibrary>> _cachedRemote = {};

  static final http.Client _client = LinHttpClientFactory.createClient();

  static Future<ServerIconLibrary> loadFromUrl(
    String url, {
    bool refresh = false,
    Duration timeout = defaultTimeout,
  }) {
    final key = url.trim();
    if (refresh) _cachedRemote.remove(key);
    return _cachedRemote[key] ??= _loadFromUrl(key, timeout: timeout);
  }

  static Future<ServerIconLibrary> _loadFromUrl(
    String url, {
    required Duration timeout,
  }) async {
    final uri = Uri.tryParse(url);
    if (uri == null || !uri.hasScheme) {
      throw FormatException('Invalid icon library url: $url');
    }
    if (uri.scheme != 'http' && uri.scheme != 'https') {
      throw FormatException('Only http/https is supported: $url');
    }

    final response = await _client.get(
      uri,
      headers: const {
        'Accept': 'application/json,text/plain,*/*',
      },
    ).timeout(timeout, onTimeout: () {
      throw TimeoutException('Timeout fetching $url');
    });

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HttpException(
        'HTTP ${response.statusCode}',
        uri: uri,
      );
    }

    final decoded = jsonDecode(response.body);
    return _parseDecoded(decoded,
        fallbackName: uri.host.isEmpty ? url : uri.host);
  }

  factory ServerIconLibrary.fromJson(Map<String, dynamic> json) {
    final name = (json['name'] ?? json['title'] ?? '').toString();
    final description = (json['description'] ?? json['desc'] ?? '').toString();

    final iconsRaw = _extractIconsRaw(json);
    final icons = _parseIconsRawList(iconsRaw);

    return ServerIconLibrary(
      name: name,
      description: description,
      icons: icons,
    );
  }

  static Object? _extractIconsRaw(Map<String, dynamic> json) {
    const keys = ['icons', 'data', 'list', 'items', 'result'];
    for (final key in keys) {
      final v = json[key];
      if (v is List) return v;
      if (v is Map) {
        final map = v.map((k, v) => MapEntry(k.toString(), v));
        for (final nestedKey in keys) {
          final nested = map[nestedKey];
          if (nested is List) return nested;
        }
      }
    }
    return null;
  }

  static List<ServerIconEntry> _parseIconsRawList(Object? raw) {
    if (raw is! List) return const <ServerIconEntry>[];

    final icons = <ServerIconEntry>[];
    for (final item in raw) {
      if (item is String) {
        final url = item.trim();
        if (url.isEmpty) continue;
        final name = ServerIconEntry.deriveNameFromUrl(url);
        if (name.isEmpty) continue;
        icons.add(ServerIconEntry(name: name, url: url));
        continue;
      }

      if (item is Map) {
        final entry = ServerIconEntry.fromJson(
          item.map((k, v) => MapEntry(k.toString(), v)),
        );
        if (entry.name.trim().isEmpty || entry.url.trim().isEmpty) continue;
        icons.add(entry);
      }
    }
    return icons;
  }

  static ServerIconLibrary _parseDecoded(
    Object? decoded, {
    required String fallbackName,
  }) {
    if (decoded is Map) {
      final map = decoded.map((k, v) => MapEntry(k.toString(), v));
      final parsed = ServerIconLibrary.fromJson(map);
      if (parsed.name.trim().isNotEmpty) return parsed;
      return ServerIconLibrary(
        name: fallbackName,
        description: parsed.description,
        icons: parsed.icons,
      );
    }
    if (decoded is List) {
      return ServerIconLibrary.fromJson({
        'name': fallbackName,
        'description': '',
        'icons': decoded,
      });
    }
    throw const FormatException(
      'Invalid icon library json: expected an object or a list',
    );
  }

  static Future<ServerIconLibraries> loadAll({
    List<String> extraUrls = const [],
    bool refresh = false,
    Duration timeout = defaultTimeout,
  }) async {
    final urls =
        extraUrls.map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
    final remoteSources = await Future.wait(
      urls.map((u) async {
        try {
          final lib = await loadFromUrl(
            u,
            refresh: refresh,
            timeout: timeout,
          );
          return ServerIconLibrarySource(
            id: u,
            url: u,
            library: lib,
            error: null,
          );
        } catch (e) {
          return ServerIconLibrarySource(
            id: u,
            url: u,
            library: null,
            error: e,
          );
        }
      }),
    );

    return ServerIconLibraries(sources: remoteSources);
  }

  static void clearRemoteCache([String? url]) {
    if (url == null) {
      _cachedRemote.clear();
      return;
    }
    _cachedRemote.remove(url.trim());
  }
}

class ServerIconLibraries {
  const ServerIconLibraries({required this.sources});

  final List<ServerIconLibrarySource> sources;

  List<ServerIconLibrarySource> get availableSources =>
      sources.where((s) => s.library != null).toList(growable: false);
}

class ServerIconEntry {
  const ServerIconEntry({required this.name, required this.url});

  final String name;
  final String url;

  factory ServerIconEntry.fromJson(Map<String, dynamic> json) {
    final name =
        (json['name'] ?? json['title'] ?? json['label'] ?? json['text'] ?? '')
            .toString();
    final url = (json['url'] ??
            json['icon'] ??
            json['image'] ??
            json['img'] ??
            json['src'] ??
            json['href'] ??
            '')
        .toString();
    final fixedUrl = url.trim();
    final fixedName = name.trim().isNotEmpty
        ? name
        : (fixedUrl.isEmpty ? '' : deriveNameFromUrl(fixedUrl));
    return ServerIconEntry(name: fixedName, url: fixedUrl);
  }

  static String deriveNameFromUrl(String url) {
    final raw = url.trim();
    if (raw.isEmpty) return '';
    final uri = Uri.tryParse(raw);
    if (uri == null) return raw;
    if (uri.pathSegments.isNotEmpty) {
      final last = uri.pathSegments.last.trim();
      if (last.isNotEmpty) return last;
    }
    if (uri.host.trim().isNotEmpty) return uri.host.trim();
    return raw;
  }
}
