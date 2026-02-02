import 'package:flutter/widgets.dart';

class TvRemoteCommandDispatcher {
  TvRemoteCommandDispatcher._();

  static final TvRemoteCommandDispatcher instance = TvRemoteCommandDispatcher._();

  GlobalKey<NavigatorState>? _navigatorKey;

  void bindNavigatorKey(GlobalKey<NavigatorState> navigatorKey) {
    _navigatorKey = navigatorKey;
  }

  void dispatch(String name, Map<String, dynamic> payload) {
    switch (name) {
      case 'nav.up':
        _focus(TraversalDirection.up);
        return;
      case 'nav.down':
        _focus(TraversalDirection.down);
        return;
      case 'nav.left':
        _focus(TraversalDirection.left);
        return;
      case 'nav.right':
        _focus(TraversalDirection.right);
        return;
      case 'nav.select':
        _activate();
        return;
      case 'nav.back':
        _back();
        return;
      case 'nav.home':
        _home();
        return;
      default:
        return;
    }
  }

  void _focus(TraversalDirection direction) {
    final focus = FocusManager.instance.primaryFocus;
    focus?.focusInDirection(direction);
  }

  void _activate() {
    final ctx = FocusManager.instance.primaryFocus?.context;
    if (ctx == null) return;
    Actions.invoke(ctx, const ActivateIntent());
  }

  void _back() {
    final nav = _navigatorKey?.currentState;
    if (nav == null) return;
    nav.maybePop();
  }

  void _home() {
    final nav = _navigatorKey?.currentState;
    if (nav == null) return;
    nav.popUntil((r) => r.isFirst);
  }
}
