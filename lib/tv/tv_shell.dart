import 'package:flutter/material.dart';
import 'package:lin_player_state/lin_player_state.dart';

import 'tv_home_page.dart';

class TvShell extends StatelessWidget {
  const TvShell({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return TvHomePage(appState: appState);
  }
}

