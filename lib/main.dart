import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';

import 'login_page.dart';
import 'home_page.dart';
import 'state/app_state.dart';
import 'src/ui/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Ensure native media backends (mpv) are ready before any player is created.
  MediaKit.ensureInitialized();
  final appState = AppState();
  await appState.loadFromStorage();
  runApp(LinPlayerApp(appState: appState));
}

class LinPlayerApp extends StatelessWidget {
  const LinPlayerApp({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: appState,
      builder: (context, _) {
        final isLoggedIn = appState.token != null;
        return MaterialApp(
          title: 'LinPlayer',
          debugShowCheckedModeBanner: false,
          themeMode: ThemeMode.dark,
          darkTheme: AppTheme.dark(),
          theme: AppTheme.dark(),
          home: isLoggedIn ? HomePage(appState: appState) : LoginPage(appState: appState),
        );
      },
    );
  }
}
