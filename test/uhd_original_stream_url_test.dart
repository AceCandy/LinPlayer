import 'package:flutter_test/flutter_test.dart';

import 'package:lin_player_server_adapters/lin_player_server_adapters.dart';
import 'package:lin_player_server_adapters/server_adapters/uhd/uhd_emby_like_adapter.dart';

void main() {
  ServerAuthSession auth({
    required String baseUrl,
    required String apiPrefix,
  }) {
    return ServerAuthSession(
      token: 't1',
      baseUrl: baseUrl,
      userId: 'u1',
      apiPrefix: apiPrefix,
      preferredScheme: 'https',
    );
  }

  test('buildOriginalStreamUrl uses configured prefix', () {
    final uri = Uri.parse(
      UhdEmbyLikeAdapter.buildOriginalStreamUrl(
        auth(baseUrl: 'https://example.com', apiPrefix: 'emby'),
        videoId: 'v1',
        container: 'mkv',
      ),
    );
    expect(uri.path, '/emby/videos/v1/original.mkv');
    expect(uri.queryParameters['token'], 't1');
    expect(uri.queryParameters['api_key'], 't1');
  });

  test(
      'buildOriginalStreamUrl de-dupes prefix when baseUrl already includes it',
      () {
    final uri = Uri.parse(
      UhdEmbyLikeAdapter.buildOriginalStreamUrl(
        auth(baseUrl: 'https://example.com/emby', apiPrefix: 'emby'),
        videoId: 'v1',
        container: 'mkv',
      ),
    );
    expect(uri.path, '/emby/videos/v1/original.mkv');
  });

  test('buildOriginalStreamUrl defaults to /emby when apiPrefix is empty', () {
    final uri = Uri.parse(
      UhdEmbyLikeAdapter.buildOriginalStreamUrl(
        auth(baseUrl: 'https://example.com', apiPrefix: ''),
        videoId: 'v1',
        container: 'mkv',
      ),
    );
    expect(uri.path, '/emby/videos/v1/original.mkv');
  });

  test('buildOriginalStreamUrl does not double default prefix', () {
    final uri = Uri.parse(
      UhdEmbyLikeAdapter.buildOriginalStreamUrl(
        auth(baseUrl: 'https://example.com/emby', apiPrefix: ''),
        videoId: 'v1',
        container: 'mkv',
      ),
    );
    expect(uri.path, '/emby/videos/v1/original.mkv');
  });
}
