import 'package:flutter_test/flutter_test.dart';

import 'package:lin_player/main.dart';
import 'package:lin_player/state/app_state.dart';

void main() {
  testWidgets('Shows server screen by default', (WidgetTester tester) async {
    final appState = AppState();
    await tester.pumpWidget(LinPlayerApp(appState: appState));
    expect(find.text('还没有服务器，点右上角“+”添加。'), findsOneWidget);
  });
}
