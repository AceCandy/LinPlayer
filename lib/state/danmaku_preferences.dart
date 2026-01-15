enum DanmakuLoadMode {
  local,
  online,
}

DanmakuLoadMode danmakuLoadModeFromId(String? id) {
  switch (id) {
    case 'online':
      return DanmakuLoadMode.online;
    case 'local':
    default:
      return DanmakuLoadMode.local;
  }
}

extension DanmakuLoadModeX on DanmakuLoadMode {
  String get id {
    switch (this) {
      case DanmakuLoadMode.local:
        return 'local';
      case DanmakuLoadMode.online:
        return 'online';
    }
  }

  String get label {
    switch (this) {
      case DanmakuLoadMode.local:
        return '本地';
      case DanmakuLoadMode.online:
        return '在线';
    }
  }
}
