import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

enum DesktopShortcutAction {
  playPause,
  seekBackward,
  seekForward,
  levelUp,
  levelDown,
  volumeUp,
  volumeDown,
  brightnessUp,
  brightnessDown,
  toggleFullscreen,
  togglePanelRoute,
  togglePanelVersion,
  togglePanelAudio,
  togglePanelSubtitle,
  togglePanelDanmaku,
  togglePanelEpisode,
  togglePanelAnime4k,
}

DesktopShortcutAction? desktopShortcutActionTryFromId(String? id) {
  switch ((id ?? '').trim()) {
    case 'playPause':
      return DesktopShortcutAction.playPause;
    case 'seekBackward':
      return DesktopShortcutAction.seekBackward;
    case 'seekForward':
      return DesktopShortcutAction.seekForward;
    case 'levelUp':
      return DesktopShortcutAction.levelUp;
    case 'levelDown':
      return DesktopShortcutAction.levelDown;
    case 'volumeUp':
      return DesktopShortcutAction.volumeUp;
    case 'volumeDown':
      return DesktopShortcutAction.volumeDown;
    case 'brightnessUp':
      return DesktopShortcutAction.brightnessUp;
    case 'brightnessDown':
      return DesktopShortcutAction.brightnessDown;
    case 'toggleFullscreen':
      return DesktopShortcutAction.toggleFullscreen;
    case 'togglePanelRoute':
      return DesktopShortcutAction.togglePanelRoute;
    case 'togglePanelVersion':
      return DesktopShortcutAction.togglePanelVersion;
    case 'togglePanelAudio':
      return DesktopShortcutAction.togglePanelAudio;
    case 'togglePanelSubtitle':
      return DesktopShortcutAction.togglePanelSubtitle;
    case 'togglePanelDanmaku':
      return DesktopShortcutAction.togglePanelDanmaku;
    case 'togglePanelEpisode':
      return DesktopShortcutAction.togglePanelEpisode;
    case 'togglePanelAnime4k':
      return DesktopShortcutAction.togglePanelAnime4k;
  }
  return null;
}

extension DesktopShortcutActionX on DesktopShortcutAction {
  String get id {
    switch (this) {
      case DesktopShortcutAction.playPause:
        return 'playPause';
      case DesktopShortcutAction.seekBackward:
        return 'seekBackward';
      case DesktopShortcutAction.seekForward:
        return 'seekForward';
      case DesktopShortcutAction.levelUp:
        return 'levelUp';
      case DesktopShortcutAction.levelDown:
        return 'levelDown';
      case DesktopShortcutAction.volumeUp:
        return 'volumeUp';
      case DesktopShortcutAction.volumeDown:
        return 'volumeDown';
      case DesktopShortcutAction.brightnessUp:
        return 'brightnessUp';
      case DesktopShortcutAction.brightnessDown:
        return 'brightnessDown';
      case DesktopShortcutAction.toggleFullscreen:
        return 'toggleFullscreen';
      case DesktopShortcutAction.togglePanelRoute:
        return 'togglePanelRoute';
      case DesktopShortcutAction.togglePanelVersion:
        return 'togglePanelVersion';
      case DesktopShortcutAction.togglePanelAudio:
        return 'togglePanelAudio';
      case DesktopShortcutAction.togglePanelSubtitle:
        return 'togglePanelSubtitle';
      case DesktopShortcutAction.togglePanelDanmaku:
        return 'togglePanelDanmaku';
      case DesktopShortcutAction.togglePanelEpisode:
        return 'togglePanelEpisode';
      case DesktopShortcutAction.togglePanelAnime4k:
        return 'togglePanelAnime4k';
    }
  }

  String get label {
    switch (this) {
      case DesktopShortcutAction.playPause:
        return '播放/暂停';
      case DesktopShortcutAction.seekBackward:
        return '快退';
      case DesktopShortcutAction.seekForward:
        return '快进';
      case DesktopShortcutAction.levelUp:
        return '音量/亮度 +';
      case DesktopShortcutAction.levelDown:
        return '音量/亮度 -';
      case DesktopShortcutAction.volumeUp:
        return '音量 +';
      case DesktopShortcutAction.volumeDown:
        return '音量 -';
      case DesktopShortcutAction.brightnessUp:
        return '亮度 +';
      case DesktopShortcutAction.brightnessDown:
        return '亮度 -';
      case DesktopShortcutAction.toggleFullscreen:
        return '切换全屏';
      case DesktopShortcutAction.togglePanelRoute:
        return '线路选择';
      case DesktopShortcutAction.togglePanelVersion:
        return '版本选择';
      case DesktopShortcutAction.togglePanelAudio:
        return '音轨面板';
      case DesktopShortcutAction.togglePanelSubtitle:
        return '字幕面板';
      case DesktopShortcutAction.togglePanelDanmaku:
        return '弹幕面板';
      case DesktopShortcutAction.togglePanelEpisode:
        return '选集面板';
      case DesktopShortcutAction.togglePanelAnime4k:
        return 'Anime4K 面板';
    }
  }

  String get hint {
    switch (this) {
      case DesktopShortcutAction.levelUp:
      case DesktopShortcutAction.levelDown:
        return '（选中条）';
      case DesktopShortcutAction.volumeUp:
      case DesktopShortcutAction.volumeDown:
      case DesktopShortcutAction.brightnessUp:
      case DesktopShortcutAction.brightnessDown:
        return '（固定）';
      case DesktopShortcutAction.togglePanelVersion:
        return '（仅在线播放）';
      default:
        return '';
    }
  }
}

@immutable
class DesktopKeyBinding {
  const DesktopKeyBinding({
    required this.keyId,
    this.ctrl = false,
    this.alt = false,
    this.shift = false,
    this.meta = false,
  });

  final int keyId;
  final bool ctrl;
  final bool alt;
  final bool shift;
  final bool meta;

  LogicalKeyboardKey? get key => LogicalKeyboardKey.findKeyByKeyId(keyId);

  static bool _readBool(dynamic v) {
    if (v is bool) return v;
    if (v is num) return v != 0;
    if (v is String) {
      final s = v.trim().toLowerCase();
      if (s == 'true' || s == '1' || s == 'yes' || s == 'y') return true;
      if (s == 'false' || s == '0' || s == 'no' || s == 'n') return false;
    }
    return false;
  }

  static int? _readInt(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.round();
    if (v is String) {
      final s = v.trim().toLowerCase();
      if (s.startsWith('0x')) {
        return int.tryParse(s.substring(2), radix: 16);
      }
      return int.tryParse(s);
    }
    return null;
  }

  static DesktopKeyBinding? fromJson(dynamic value) {
    if (value == null) return null;
    if (value is! Map) return null;
    final keyId = _readInt(value['keyId']);
    if (keyId == null) return null;
    return DesktopKeyBinding(
      keyId: keyId,
      ctrl: _readBool(value['ctrl']),
      alt: _readBool(value['alt']),
      shift: _readBool(value['shift']),
      meta: _readBool(value['meta']),
    );
  }

  Map<String, dynamic> toJson() => {
        'keyId': keyId,
        'ctrl': ctrl,
        'alt': alt,
        'shift': shift,
        'meta': meta,
      };

  String format() {
    final parts = <String>[];
    if (ctrl) parts.add('Ctrl');
    if (alt) parts.add('Alt');
    if (shift) parts.add('Shift');
    if (meta) parts.add('Meta');
    final key = this.key;
    parts.add(key == null ? _fmtKeyId(keyId) : desktopKeyLabel(key));
    return parts.join('+');
  }

  static String _fmtKeyId(int keyId) => '0x${keyId.toRadixString(16)}';

  bool matchesKey({
    required LogicalKeyboardKey key,
    required bool ctrlPressed,
    required bool altPressed,
    required bool shiftPressed,
    required bool metaPressed,
  }) {
    return key.keyId == keyId &&
        ctrlPressed == ctrl &&
        altPressed == alt &&
        shiftPressed == shift &&
        metaPressed == meta;
  }

  @override
  bool operator ==(Object other) {
    return other is DesktopKeyBinding &&
        other.keyId == keyId &&
        other.ctrl == ctrl &&
        other.alt == alt &&
        other.shift == shift &&
        other.meta == meta;
  }

  @override
  int get hashCode => Object.hash(keyId, ctrl, alt, shift, meta);
}

String desktopKeyLabel(LogicalKeyboardKey key) {
  if (key == LogicalKeyboardKey.arrowLeft) return '←';
  if (key == LogicalKeyboardKey.arrowRight) return '→';
  if (key == LogicalKeyboardKey.arrowUp) return '↑';
  if (key == LogicalKeyboardKey.arrowDown) return '↓';
  if (key == LogicalKeyboardKey.space) return 'Space';
  if (key == LogicalKeyboardKey.enter || key == LogicalKeyboardKey.numpadEnter) {
    return 'Enter';
  }
  if (key == LogicalKeyboardKey.escape) return 'Esc';
  if (key == LogicalKeyboardKey.backspace) return 'Backspace';
  if (key == LogicalKeyboardKey.delete) return 'Delete';

  final label = key.keyLabel.trim();
  if (label.isNotEmpty) {
    if (label.length == 1) return label.toUpperCase();
    return label;
  }
  final debug = (key.debugName ?? '').trim();
  if (debug.isNotEmpty) return debug;
  return '0x${key.keyId.toRadixString(16)}';
}

enum DesktopMouseSideButtonAction {
  none,
  seekBackward,
  seekForward,
  playPause,
}

DesktopMouseSideButtonAction desktopMouseSideButtonActionFromId(String? id) {
  switch ((id ?? '').trim()) {
    case 'seekBackward':
      return DesktopMouseSideButtonAction.seekBackward;
    case 'seekForward':
      return DesktopMouseSideButtonAction.seekForward;
    case 'playPause':
      return DesktopMouseSideButtonAction.playPause;
    case 'none':
    default:
      return DesktopMouseSideButtonAction.none;
  }
}

extension DesktopMouseSideButtonActionX on DesktopMouseSideButtonAction {
  String get id {
    switch (this) {
      case DesktopMouseSideButtonAction.none:
        return 'none';
      case DesktopMouseSideButtonAction.seekBackward:
        return 'seekBackward';
      case DesktopMouseSideButtonAction.seekForward:
        return 'seekForward';
      case DesktopMouseSideButtonAction.playPause:
        return 'playPause';
    }
  }

  String get label {
    switch (this) {
      case DesktopMouseSideButtonAction.none:
        return '不处理';
      case DesktopMouseSideButtonAction.seekBackward:
        return '快退';
      case DesktopMouseSideButtonAction.seekForward:
        return '快进';
      case DesktopMouseSideButtonAction.playPause:
        return '播放/暂停';
    }
  }
}

@immutable
class DesktopShortcutBindings {
  const DesktopShortcutBindings({
    required this.keyBindings,
    required this.mouseBackButtonAction,
    required this.mouseForwardButtonAction,
  });

  final Map<DesktopShortcutAction, DesktopKeyBinding?> keyBindings;
  final DesktopMouseSideButtonAction mouseBackButtonAction;
  final DesktopMouseSideButtonAction mouseForwardButtonAction;

  static final DesktopShortcutBindings defaults = DesktopShortcutBindings(
    keyBindings: _defaultKeyBindings,
    mouseBackButtonAction: DesktopMouseSideButtonAction.seekBackward,
    mouseForwardButtonAction: DesktopMouseSideButtonAction.seekForward,
  );

  static final Map<DesktopShortcutAction, DesktopKeyBinding?> _defaultKeyBindings =
      Map.unmodifiable({
    DesktopShortcutAction.playPause:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.space.keyId),
    DesktopShortcutAction.seekBackward:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.arrowLeft.keyId),
    DesktopShortcutAction.seekForward:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.arrowRight.keyId),
    DesktopShortcutAction.levelUp:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.arrowUp.keyId),
    DesktopShortcutAction.levelDown:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.arrowDown.keyId),
    DesktopShortcutAction.volumeUp: null,
    DesktopShortcutAction.volumeDown: null,
    DesktopShortcutAction.brightnessUp: DesktopKeyBinding(
      keyId: LogicalKeyboardKey.arrowUp.keyId,
      shift: true,
    ),
    DesktopShortcutAction.brightnessDown: DesktopKeyBinding(
      keyId: LogicalKeyboardKey.arrowDown.keyId,
      shift: true,
    ),
    DesktopShortcutAction.toggleFullscreen:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyF.keyId),
    DesktopShortcutAction.togglePanelRoute:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyR.keyId),
    DesktopShortcutAction.togglePanelVersion:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyV.keyId),
    DesktopShortcutAction.togglePanelAudio:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyA.keyId),
    DesktopShortcutAction.togglePanelSubtitle:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyS.keyId),
    DesktopShortcutAction.togglePanelDanmaku:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyD.keyId),
    DesktopShortcutAction.togglePanelEpisode:
        DesktopKeyBinding(keyId: LogicalKeyboardKey.keyE.keyId),
    DesktopShortcutAction.togglePanelAnime4k: null,
  });

  DesktopKeyBinding? bindingOf(DesktopShortcutAction action) =>
      keyBindings[action];

  DesktopShortcutBindings copyWithKeyBinding(
    DesktopShortcutAction action,
    DesktopKeyBinding? binding,
  ) {
    final next = Map<DesktopShortcutAction, DesktopKeyBinding?>.from(keyBindings);
    next[action] = binding;
    return DesktopShortcutBindings(
      keyBindings: Map.unmodifiable(next),
      mouseBackButtonAction: mouseBackButtonAction,
      mouseForwardButtonAction: mouseForwardButtonAction,
    );
  }

  DesktopShortcutBindings copyWithMouseBackButtonAction(
    DesktopMouseSideButtonAction action,
  ) {
    return DesktopShortcutBindings(
      keyBindings: keyBindings,
      mouseBackButtonAction: action,
      mouseForwardButtonAction: mouseForwardButtonAction,
    );
  }

  DesktopShortcutBindings copyWithMouseForwardButtonAction(
    DesktopMouseSideButtonAction action,
  ) {
    return DesktopShortcutBindings(
      keyBindings: keyBindings,
      mouseBackButtonAction: mouseBackButtonAction,
      mouseForwardButtonAction: action,
    );
  }

  Map<String, dynamic> toJson() => {
        'keys': {
          for (final a in DesktopShortcutAction.values)
            a.id: keyBindings[a]?.toJson(),
        },
        'mouse': {
          'back': mouseBackButtonAction.id,
          'forward': mouseForwardButtonAction.id,
        },
      };

  factory DesktopShortcutBindings.fromJson(dynamic value) {
    final fallback = DesktopShortcutBindings.defaults;
    if (value is! Map) return fallback;

    final nextKeyBindings =
        Map<DesktopShortcutAction, DesktopKeyBinding?>.from(fallback.keyBindings);
    final rawKeys = value['keys'];
    if (rawKeys is Map) {
      for (final entry in rawKeys.entries) {
        final action = desktopShortcutActionTryFromId(entry.key.toString());
        if (action == null) continue;
        final rawBinding = entry.value;
        if (rawBinding == null) {
          nextKeyBindings[action] = null;
          continue;
        }
        final binding = DesktopKeyBinding.fromJson(rawBinding);
        if (binding == null) continue;
        nextKeyBindings[action] = binding;
      }
    }

    var back = fallback.mouseBackButtonAction;
    var forward = fallback.mouseForwardButtonAction;
    final rawMouse = value['mouse'];
    if (rawMouse is Map) {
      back = desktopMouseSideButtonActionFromId(rawMouse['back']?.toString());
      forward =
          desktopMouseSideButtonActionFromId(rawMouse['forward']?.toString());
    }

    return DesktopShortcutBindings(
      keyBindings: Map.unmodifiable(nextKeyBindings),
      mouseBackButtonAction: back,
      mouseForwardButtonAction: forward,
    );
  }
}
