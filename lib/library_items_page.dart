import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import 'services/emby_api.dart';
import 'state/app_state.dart';
import 'play_network_page.dart';

class LibraryItemsPage extends StatefulWidget {
  const LibraryItemsPage({
    super.key,
    required this.appState,
    required this.parentId,
    required this.title,
  });

  final AppState appState;
  final String parentId;
  final String title;

  @override
  State<LibraryItemsPage> createState() => _LibraryItemsPageState();
}

class _LibraryItemsPageState extends State<LibraryItemsPage> {
  late Future<List<MediaItem>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.appState.fetchItems(widget.parentId);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
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
            return const Center(child: Text('此目录暂无可浏览项目'));
          }
          return ListView.separated(
            itemCount: items.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final item = items[index];
              final image = item.hasImage
                  ? EmbyApi.imageUrl(
                      baseUrl: widget.appState.baseUrl!,
                      itemId: item.id,
                      token: widget.appState.token!,
                      maxWidth: 300,
                    )
                  : null;

              String subtitle = item.type;
              if (item.type == 'Episode') {
                final s = item.seasonNumber ?? 0;
                final e = item.episodeNumber ?? 0;
                subtitle = 'S${s.toString().padLeft(2, '0')}E${e.toString().padLeft(2, '0')}';
              }
              if (item.overview.isNotEmpty) {
                subtitle = '$subtitle · ${item.overview}';
              }

              return ListTile(
                leading: image != null
                    ? CachedNetworkImage(
                        imageUrl: image,
                        width: 80,
                        height: 80,
                        fit: BoxFit.cover,
                        placeholder: (_, __) =>
                            const SizedBox(width: 80, height: 80, child: Icon(Icons.image)),
                        errorWidget: (_, __, ___) =>
                            const SizedBox(width: 80, height: 80, child: Icon(Icons.broken_image)),
                      )
                    : const SizedBox(width: 80, height: 80, child: Icon(Icons.image)),
                title: Text(item.name),
                subtitle: Text(
                  subtitle,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: item.type == 'Episode'
                    ? const Icon(Icons.play_arrow)
                    : const Icon(Icons.chevron_right),
                onTap: () {
                  if (item.type == 'Episode') {
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
                  } else {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => LibraryItemsPage(
                          appState: widget.appState,
                          parentId: item.id,
                          title: item.name,
                        ),
                      ),
                    );
                  }
                },
              );
            },
          );
        },
      ),
    );
  }
}
