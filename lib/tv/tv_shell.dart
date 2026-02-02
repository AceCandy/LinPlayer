import 'package:flutter/material.dart';
import 'package:lin_player_core/state/media_server_type.dart';
import 'package:lin_player_state/lin_player_state.dart';

import '../home_page.dart';
import '../server_page.dart';
import '../webdav_home_page.dart';
import 'tv_onboarding_page.dart';

class TvShell extends StatelessWidget {
  const TvShell({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    if (appState.servers.isEmpty) {
      return TvOnboardingPage(appState: appState);
    }

    final active = appState.activeServer;
    if (active == null || !appState.hasActiveServerProfile) {
      return ServerPage(appState: appState);
    }
    if (active.serverType == MediaServerType.webdav) {
      return WebDavHomePage(appState: appState);
    }
    if (appState.hasActiveServer) {
      return HomePage(appState: appState);
    }
    return ServerPage(appState: appState);
  }
}
