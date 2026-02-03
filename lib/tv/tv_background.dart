import 'dart:io';
import 'dart:ui';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:lin_player_prefs/lin_player_prefs.dart';
import 'package:lin_player_state/lin_player_state.dart';

class TvBackground extends StatelessWidget {
  const TvBackground({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    final mode = appState.tvBackgroundMode;
    final scheme = Theme.of(context).colorScheme;
    final isDark = scheme.brightness == Brightness.dark;
    final opacity = appState.tvBackgroundOpacity.clamp(0.0, 1.0).toDouble();
    final blurSigma = appState.tvBackgroundBlurSigma.clamp(0.0, 30.0).toDouble();

    Widget? backdrop;
    switch (mode) {
      case TvBackgroundMode.none:
        backdrop = null;
      case TvBackgroundMode.solidColor:
        backdrop = ColoredBox(color: Color(appState.tvBackgroundColor));
      case TvBackgroundMode.image:
        backdrop = _buildImage(appState.tvBackgroundImage);
      case TvBackgroundMode.randomApi:
        backdrop = _buildRandomApi();
    }

    if (backdrop == null) {
      return const SizedBox.shrink();
    }

    if (opacity < 1.0) {
      backdrop = Opacity(opacity: opacity, child: backdrop);
    }

    final blurAllowed = mode == TvBackgroundMode.image ||
        mode == TvBackgroundMode.randomApi;
    if (blurAllowed && blurSigma > 0.0) {
      backdrop = ImageFiltered(
        imageFilter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
        child: backdrop,
      );
    }

    final base = ColoredBox(color: scheme.surface);
    final scrim = isDark ? Colors.black : Colors.white;
    final topAlpha = isDark ? 0.18 : 0.10;
    final bottomAlpha = isDark ? 0.40 : 0.22;

    return IgnorePointer(
      child: Stack(
        fit: StackFit.expand,
        children: [
          base,
          backdrop,
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  scrim.withValues(alpha: topAlpha),
                  scrim.withValues(alpha: bottomAlpha),
                ],
              ),
            ),
          ),
          ColoredBox(color: scheme.surface.withValues(alpha: isDark ? 0.06 : 0.10)),
        ],
      ),
    );
  }

  Widget _buildRandomApi() {
    final base = appState.tvBackgroundRandomApiUrl.trim();
    if (base.isEmpty) {
      return const SizedBox.shrink();
    }
    final nonce = appState.tvBackgroundRandomNonce;
    final sep = base.contains('?') ? '&' : '?';
    final url = '$base${sep}t=$nonce';
    return _buildNetworkImage(url);
  }

  Widget _buildImage(String raw) {
    final v = raw.trim();
    if (v.isEmpty) {
      return const SizedBox.shrink();
    }
    final uri = Uri.tryParse(v);
    if (uri != null && (uri.scheme == 'http' || uri.scheme == 'https')) {
      return _buildNetworkImage(v);
    }
    return Image.file(
      File(v),
      fit: BoxFit.cover,
      errorBuilder: (_, __, ___) => const SizedBox.shrink(),
    );
  }

  Widget _buildNetworkImage(String url) {
    return CachedNetworkImage(
      imageUrl: url,
      fit: BoxFit.cover,
      fadeInDuration: const Duration(milliseconds: 180),
      fadeOutDuration: const Duration(milliseconds: 120),
      placeholder: (_, __) => const SizedBox.shrink(),
      errorWidget: (_, __, ___) => const SizedBox.shrink(),
    );
  }
}
