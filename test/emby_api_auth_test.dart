import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:lin_player_core/state/media_server_type.dart';
import 'package:lin_player_server_api/services/emby_api.dart';

void main() {
  test('EmbyApi.authenticate parses non-string user id', () async {
    final client = MockClient((request) async {
      if (request.method == 'POST' &&
          request.url.path == '/emby/Users/AuthenticateByName') {
        return http.Response(
          jsonEncode({
            'AccessToken': 't1',
            'User': {'Id': 123},
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      return http.Response('Not Found', 404);
    });

    final api = EmbyApi(
      hostOrUrl: 'https://example.com',
      preferredScheme: 'https',
      serverType: MediaServerType.emby,
      deviceId: 'd1',
      client: client,
    );

    final auth = await api.authenticate(
      username: 'u1',
      password: 'p1',
      deviceId: 'd1',
      serverType: MediaServerType.emby,
    );

    expect(auth.token, 't1');
    expect(auth.userId, '123');
    expect(auth.apiPrefixUsed, 'emby');
    expect(auth.baseUrlUsed, 'https://example.com');
  });

  test('EmbyApi.authenticate falls back to Users/Me', () async {
    var authed = false;
    final client = MockClient((request) async {
      if (request.method == 'POST' &&
          request.url.path == '/emby/Users/AuthenticateByName') {
        authed = true;
        return http.Response(
          jsonEncode({
            'AccessToken': 't1',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      if (request.method == 'GET' && request.url.path == '/emby/Users/Me') {
        return http.Response(
          jsonEncode({
            'Id': 'u1',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      return http.Response('Not Found', 404);
    });

    final api = EmbyApi(
      hostOrUrl: 'https://example.com',
      preferredScheme: 'https',
      serverType: MediaServerType.emby,
      deviceId: 'd1',
      client: client,
    );

    final auth = await api.authenticate(
      username: 'u1',
      password: 'p1',
      deviceId: 'd1',
      serverType: MediaServerType.emby,
    );

    expect(authed, isTrue);
    expect(auth.token, 't1');
    expect(auth.userId, 'u1');
  });

  test('EmbyApi.authenticate supports UHD root prefix', () async {
    final client = MockClient((request) async {
      if (request.method == 'POST' && request.url.path == '/Users/AuthenticateByName') {
        return http.Response(
          jsonEncode({
            'AccessToken': 't1',
            'User': {'Id': 'u1'},
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      return http.Response('Not Found', 404);
    });

    final api = EmbyApi(
      hostOrUrl: 'https://example.com',
      preferredScheme: 'https',
      serverType: MediaServerType.uhd,
      deviceId: 'd1',
      client: client,
    );

    final auth = await api.authenticate(
      username: 'u1',
      password: 'p1',
      deviceId: 'd1',
      serverType: MediaServerType.uhd,
    );

    expect(auth.token, 't1');
    expect(auth.userId, 'u1');
    expect(auth.apiPrefixUsed, '');
    expect(auth.baseUrlUsed, 'https://example.com');
  });

  test('EmbyApi.fetchSeasons uses Shows seasons with api_key for UHD', () async {
    final client = MockClient((request) async {
      if (request.method == 'GET' &&
          request.url.path == '/Shows/s1/Seasons' &&
          request.url.queryParameters['api_key'] == 't1') {
        return http.Response(
          jsonEncode({
            'Items': [
              {'Id': 'season1', 'Name': 'S1', 'Type': 'Season', 'UserData': {}}
            ],
            'TotalRecordCount': 1,
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      return http.Response('Not Found', 404);
    });

    final api = EmbyApi(
      hostOrUrl: 'https://example.com',
      preferredScheme: 'https',
      apiPrefix: '',
      serverType: MediaServerType.uhd,
      deviceId: 'd1',
      client: client,
    );

    final res = await api.fetchSeasons(
      token: 't1',
      baseUrl: 'https://example.com',
      userId: 'u1',
      seriesId: 's1',
    );

    expect(res.total, 1);
    expect(res.items.length, 1);
    expect(res.items.first.id, 'season1');
  });
}
