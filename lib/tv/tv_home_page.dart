import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:lin_player_core/state/media_server_type.dart';
import 'package:lin_player_state/lin_player_state.dart';

import '../home_page.dart';
import '../library_page.dart';
import '../search_page.dart';
import '../server_page.dart';
import '../settings_page.dart';
import '../webdav_home_page.dart';
import '../services/built_in_proxy/built_in_proxy_service.dart';
import '../services/tv_remote/tv_remote_service.dart';
import 'tv_widgets.dart';

class TvHomePage extends StatelessWidget {
  const TvHomePage({super.key, required this.appState});

  final AppState appState;

  void _push(BuildContext context, Widget page) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => page));
  }

  Widget _pickActiveServerHome() {
    final active = appState.activeServer;
    if (active == null || !appState.hasActiveServerProfile) {
      return ServerPage(appState: appState);
    }
    if (active.serverType == MediaServerType.webdav) {
      return WebDavHomePage(appState: appState);
    }
    return HomePage(appState: appState);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: appState,
      builder: (context, _) {
        final theme = Theme.of(context);
        final hasServers = appState.servers.isNotEmpty;
        if (!hasServers) {
          return ServerPage(appState: appState);
        }

        final active = appState.activeServer;
        final activeName = active?.name;
        final proxyStatus = BuiltInProxyService.instance.status;
        final remoteUrl = TvRemoteService.instance.firstRemoteUrl;

        final size = MediaQuery.sizeOf(context);
        final cols = (size.width / 360).floor().clamp(2, 5);

        return Scaffold(
          body: SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(28, 22, 28, 26),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'LinPlayer',
                    style: theme.textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                      height: 1.05,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    activeName == null ? 'TV 首页' : '当前服务器：$activeName',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 18),
                  Expanded(
                    child: GridView.count(
                      crossAxisCount: cols,
                      mainAxisSpacing: 16,
                      crossAxisSpacing: 16,
                      childAspectRatio: 1.35,
                      children: [
                        TvActionCard(
                          autofocus: true,
                          title: '进入媒体',
                          subtitle: activeName ?? '选择/添加服务器',
                          icon: Icons.movie_outlined,
                          onPressed: () => _push(context, _pickActiveServerHome()),
                        ),
                        TvActionCard(
                          title: '服务器',
                          subtitle: '添加/切换/管理',
                          icon: Icons.dns_outlined,
                          onPressed: () =>
                              _push(context, ServerPage(appState: appState)),
                        ),
                        TvActionCard(
                          title: '媒体库',
                          subtitle: '按库浏览',
                          icon: Icons.video_library_outlined,
                          enabled: appState.hasActiveServer,
                          onPressed: appState.hasActiveServer
                              ? () => _push(context, LibraryPage(appState: appState))
                              : null,
                        ),
                        TvActionCard(
                          title: '搜索',
                          subtitle: '搜索影片/剧集',
                          icon: Icons.search,
                          enabled: appState.hasActiveServer,
                          onPressed: appState.hasActiveServer
                              ? () => _push(context, SearchPage(appState: appState))
                              : null,
                        ),
                        TvActionCard(
                          title: '手机扫码',
                          subtitle: remoteUrl == null ? '配对后输入服务器信息' : '已就绪：${remoteUrl.host}:${remoteUrl.port}',
                          icon: Icons.qr_code_2_outlined,
                          onPressed: () =>
                              _push(context, ServerPage(appState: appState)),
                        ),
                        TvActionCard(
                          title: '网络加速',
                          subtitle: proxyStatus.isSupported
                              ? proxyStatus.message
                              : '仅 Android TV',
                          icon: Icons.shield_outlined,
                          onPressed: () => _push(context, SettingsPage(appState: appState)),
                        ),
                        TvActionCard(
                          title: '设置',
                          subtitle: '外观/播放/TV',
                          icon: Icons.settings_outlined,
                          onPressed: () => _push(context, SettingsPage(appState: appState)),
                        ),
                        if (!kIsWeb &&
                            defaultTargetPlatform == TargetPlatform.android &&
                            appState.hasActiveServer) ...[
                          TvActionCard(
                            title: '本地播放',
                            subtitle: '文件/本地媒体',
                            icon: Icons.folder_open,
                            onPressed: () => _push(context, HomePage(appState: appState)),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '提示：在「设置 → TV 专区」可以开关“手机扫码控制 / 内置代理（mihomo）”。',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
