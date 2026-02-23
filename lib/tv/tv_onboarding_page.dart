import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lin_player_state/lin_player_state.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../server_page.dart';
import '../services/tv_remote/tv_remote_service.dart';
import 'tv_widgets.dart';

class TvOnboardingPage extends StatefulWidget {
  const TvOnboardingPage({super.key, required this.appState});

  final AppState appState;

  @override
  State<TvOnboardingPage> createState() => _TvOnboardingPageState();
}

class _TvOnboardingPageState extends State<TvOnboardingPage> {
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    // Make pairing available out-of-box on Android TV, especially on first launch.
    unawaited(TvRemoteService.instance.start(appState: widget.appState));
    _refreshTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {});
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final remote = TvRemoteService.instance;
    final url = remote.firstRemoteUrl;
    final addressText = url == null ? '正在获取局域网地址…' : url.toString();

    final size = MediaQuery.sizeOf(context);
    final isNarrow = size.width < 900;

    return Scaffold(
      appBar: AppBar(
        title: const Text('TV 配对'),
        centerTitle: true,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(28, 20, 28, 24),
          child: isNarrow
              ? _NarrowLayout(
                  theme: theme,
                  addressText: addressText,
                  url: url,
                  appState: widget.appState,
                )
              : _WideLayout(
                  theme: theme,
                  addressText: addressText,
                  url: url,
                  appState: widget.appState,
                ),
        ),
      ),
    );
  }
}

class _WideLayout extends StatelessWidget {
  const _WideLayout({
    required this.theme,
    required this.addressText,
    required this.url,
    required this.appState,
  });

  final ThemeData theme;
  final String addressText;
  final Uri? url;
  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          flex: 5,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '用手机扫码添加服务器',
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w700,
                  height: 1.05,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                '手机与 TV 需在同一局域网。扫码后可在手机端填写 Emby/Jellyfin/WebDAV 的地址/账号/密码等信息，提交后 TV 会自动添加服务器。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 18),
              TvActionCard(
                autofocus: true,
                title: '打开服务器列表',
                subtitle: '查看/切换/删除服务器',
                icon: Icons.dns_outlined,
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => ServerPage(appState: appState)),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                addressText,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const Spacer(),
              Text(
                '提示：你也可以在「设置 → TV 专区」关闭“手机扫码控制”。',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 22),
        Expanded(
          flex: 4,
          child: Center(
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.65),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: theme.colorScheme.outlineVariant),
              ),
              child: url == null
                  ? const SizedBox(
                      height: 260,
                      width: 260,
                      child: Center(child: CircularProgressIndicator()),
                    )
                  : QrImageView(
                      data: url.toString(),
                      size: 260,
                      backgroundColor: Colors.white,
                    ),
            ),
          ),
        ),
      ],
    );
  }
}

class _NarrowLayout extends StatelessWidget {
  const _NarrowLayout({
    required this.theme,
    required this.addressText,
    required this.url,
    required this.appState,
  });

  final ThemeData theme;
  final String addressText;
  final Uri? url;
  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return ListView(
      children: [
        Text(
          '用手机扫码添加服务器',
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 10),
        Center(
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.65),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: theme.colorScheme.outlineVariant),
            ),
            child: url == null
                ? const SizedBox(
                    height: 240,
                    width: 240,
                    child: Center(child: CircularProgressIndicator()),
                  )
                : QrImageView(
                    data: url.toString(),
                    size: 240,
                    backgroundColor: Colors.white,
                  ),
          ),
        ),
        const SizedBox(height: 12),
        Text(
          addressText,
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 18),
        TvActionCard(
          title: '打开服务器列表',
          subtitle: '查看/切换/删除服务器',
          icon: Icons.dns_outlined,
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ServerPage(appState: appState)),
          ),
        ),
      ],
    );
  }
}

