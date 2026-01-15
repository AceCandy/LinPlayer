import 'package:flutter/material.dart';

import '../../state/app_state.dart';

Future<void> showThemeSheet(BuildContext context, AppState appState) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (ctx) {
      return AnimatedBuilder(
        animation: appState,
        builder: (context, _) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('主题', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 12),
                SegmentedButton<ThemeMode>(
                  segments: const [
                    ButtonSegment(value: ThemeMode.system, label: Text('系统')),
                    ButtonSegment(value: ThemeMode.light, label: Text('浅色')),
                    ButtonSegment(value: ThemeMode.dark, label: Text('深色')),
                  ],
                  selected: {appState.themeMode},
                  onSelectionChanged: (s) => appState.setThemeMode(s.first),
                ),
                const SizedBox(height: 10),
                SwitchListTile(
                  value: appState.useDynamicColor,
                  onChanged: (v) => appState.setUseDynamicColor(v),
                  title: const Text('莫奈取色（Material You）'),
                  subtitle: const Text('Android 12+ 生效，其它平台自动回退'),
                  contentPadding: EdgeInsets.zero,
                ),
              ],
            ),
          );
        },
      );
    },
  );
}
