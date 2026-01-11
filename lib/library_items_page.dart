import 'package:flutter/material.dart';

import 'services/emby_api.dart';
import 'state/app_state.dart';
import 'play_network_page.dart';

class LibraryItemsPage extends StatefulWidget {
  const LibraryItemsPage({super.key, required this.appState, required this.library});

  final AppState appState;
  final LibraryInfo library;

  @override
  State<LibraryItemsPage> createState() => _LibraryItemsPageState();
}

class _LibraryItemsPageState extends State<LibraryItemsPage> {
  late Future<List<MediaItem>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.appState.fetchItems(widget.library.id);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.library.name),
      ),
      body: FutureBuilder<List<MediaItem>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(child: Text('加载失败: ${snapshot.error}'));
          }
          final items = snapshot.data ?? [];
          if (items.isEmpty) {
            return const Center(child: Text('此媒体库暂无可浏览项目'));
          }
          return ListView.separated(
            itemCount: items.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final item = items[index];
              return ListTile(
                leading: const Icon(Icons.play_circle_outline),
                title: Text(item.name),
                subtitle: Text(item.type),
                onTap: () {
                  final url =
                      '${widget.appState.baseUrl}/Videos/${item.id}/stream?static=true&api_key=${widget.appState.token}';
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => PlayNetworkPage(
                        title: item.name,
                        streamUrl: url,
                      ),
                    ),
                  );
                },
              );
            },
          );
        },
      ),
    );
  }
}
