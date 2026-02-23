import 'package:lin_player_prefs/lin_player_prefs.dart';
import 'package:lin_player_state/lin_player_state.dart';

bool _isPrivateIpv4Host(String host) {
  final parts = host.split('.');
  if (parts.length != 4) return false;
  final octets = <int>[];
  for (final p in parts) {
    final v = int.tryParse(p);
    if (v == null || v < 0 || v > 255) return false;
    octets.add(v);
  }

  final a = octets[0];
  final b = octets[1];

  if (a == 10) return true;
  if (a == 127) return true;
  if (a == 169 && b == 254) return true;
  if (a == 192 && b == 168) return true;
  if (a == 172 && b >= 16 && b <= 31) return true;
  return false;
}

bool shouldProxyPlaybackUri(Uri uri) {
  final scheme = uri.scheme.toLowerCase();
  if (scheme != 'http' && scheme != 'https') return false;

  final host = uri.host.trim().toLowerCase();
  if (host.isEmpty) return false;
  if (host == 'localhost' || host == '127.0.0.1' || host == '::1') {
    return false;
  }
  if (_isPrivateIpv4Host(host)) return false;

  return true;
}

/// Returns the MPV `http-proxy` URL to use for this [uri], or null for direct.
String? resolvePlaybackHttpProxyForUri({
  required AppState appState,
  required Uri uri,
}) {
  if (appState.playbackProxyMode != PlaybackProxyMode.custom) return null;
  final proxyUrl = appState.playbackProxyUrl.trim();
  if (proxyUrl.isEmpty) return null;
  if (!shouldProxyPlaybackUri(uri)) return null;
  return proxyUrl;
}

