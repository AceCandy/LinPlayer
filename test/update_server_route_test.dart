import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:lin_player_state/app_state.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('switching to a custom route keeps previous route as custom', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      'servers_v1',
      jsonEncode([
        {
          'id': 'srv_1',
          'serverType': 'emby',
          'username': 'demo',
          'name': 'Demo',
          'baseUrl': 'https://route1.example.com',
          'token': 't',
          'userId': 'u',
          'apiPrefix': 'emby',
          'hiddenLibraries': const <String>[],
          'domainRemarks': const <String, String>{},
          'customDomains': const [
            {
              'name': 'Route 2',
              'url': 'https://route2.example.com',
            },
          ],
        },
      ]),
    );
    await prefs.setString('activeServerId_v1', 'srv_1');

    final appState = AppState();
    await appState.loadFromStorage();

    expect(appState.baseUrl, 'https://route1.example.com');
    expect(appState.customDomains.map((e) => e.url), [
      'https://route2.example.com',
    ]);

    await appState.updateServerRoute('srv_1',
        url: 'https://route2.example.com');

    expect(appState.baseUrl, 'https://route2.example.com');
    expect(appState.customDomains.map((e) => e.url).toSet(), {
      'https://route2.example.com',
      'https://route1.example.com',
    });
  });
}
