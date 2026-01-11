import 'package:flutter/material.dart';

import 'services/emby_api.dart';
import 'state/app_state.dart';
import 'library_items_page.dart';

class LibraryPage extends StatelessWidget {
  const LibraryPage({super.key, required this.appState});

  final AppState appState;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: appState,
      builder: (context, _) {
        final libs = appState.libraries;
        return Scaffold(
          appBar: AppBar(
            title: const Text('媒体库'),
            actions: [
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: appState.isLoading ? null : () => appState.refreshLibraries(),
              ),
            ],
          ),
          body: appState.isLoading
              ? const Center(child: CircularProgressIndicator())
              : libs.isEmpty
                  ? const Center(child: Text('暂无媒体库，点击右上角刷新重试'))
                  : ListView.builder(
                      itemCount: libs.length,
                      itemBuilder: (context, index) {
                        final LibraryInfo lib = libs[index];
                        return ListTile(
                          leading: const Icon(Icons.folder),
                          title: Text(lib.name),
                          subtitle: Text(lib.id),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () {
                            Navigator.of(context).push(
                              MaterialPageRoute(
                                builder: (_) =>
                                    LibraryItemsPage(appState: appState, library: lib),
                              ),
                            );
                          },
                        );
                      },
                    ),
        );
      },
    );
  }
}
