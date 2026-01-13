import 'package:flutter/material.dart';

import 'services/emby_api.dart';
import 'library_page.dart';
import 'state/app_state.dart';
import 'player_screen.dart';

class DomainListPage extends StatelessWidget {
  const DomainListPage({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: appState,
      builder: (context, _) {
        final domains = appState.domains;
        return Scaffold(
          appBar: AppBar(
            title: const Text('服务器'),
            actions: [
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: appState.isLoading ? null : () => appState.refreshDomains(),
                tooltip: '刷新',
              ),
              IconButton(
                icon: const Icon(Icons.logout),
                onPressed: appState.isLoading ? null : () => appState.logout(),
                tooltip: '退出登录',
              ),
            ],
          ),
          body: appState.isLoading
              ? const Center(child: CircularProgressIndicator())
              : domains.isEmpty
                  ? const Center(child: Text('暂无线路，点击右上角刷新重试'))
                  : ListView.separated(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
                      itemCount: domains.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 12),
                      itemBuilder: (context, index) {
                        final DomainInfo d = domains[index];
                        final name = d.name.isNotEmpty ? d.name : d.url;
                        final initial = name.isNotEmpty ? name[0].toUpperCase() : '?';
                        return Card(
                          child: ListTile(
                            leading: CircleAvatar(
                              backgroundColor:
                                  Theme.of(context).colorScheme.primary.withValues(alpha: 0.14),
                              child:
                                  Text(initial, style: const TextStyle(fontWeight: FontWeight.w700)),
                            ),
                            title: Text(name, style: const TextStyle(fontWeight: FontWeight.w600)),
                            subtitle: Text(d.url, overflow: TextOverflow.ellipsis),
                            trailing: const Icon(Icons.more_vert),
                            onTap: () {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text('已选择：${d.url}')),
                              );
                            },
                          ),
                        );
                      },
                    ),
          floatingActionButton: FloatingActionButton(
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const PlayerScreen()),
              );
            },
            child: const Icon(Icons.add),
          ),
          bottomNavigationBar: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.tonal(
                    onPressed: () {
                      Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => LibraryPage(appState: appState)),
                      );
                    },
                    child: const Text('媒体库'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () {
                      Navigator.of(context).push(
                        MaterialPageRoute(builder: (_) => const PlayerScreen()),
                      );
                    },
                    child: const Text('本地播放器'),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
