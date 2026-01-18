enum Anime4kPreset {
  off,
  a,
  b,
  c,
  aa,
  bb,
  ca,
}

Anime4kPreset anime4kPresetFromId(String? id) {
  switch ((id ?? '').trim()) {
    case 'a':
      return Anime4kPreset.a;
    case 'b':
      return Anime4kPreset.b;
    case 'c':
      return Anime4kPreset.c;
    case 'aa':
      return Anime4kPreset.aa;
    case 'bb':
      return Anime4kPreset.bb;
    case 'ca':
      return Anime4kPreset.ca;
    case 'off':
    default:
      return Anime4kPreset.off;
  }
}

extension Anime4kPresetX on Anime4kPreset {
  String get id {
    switch (this) {
      case Anime4kPreset.off:
        return 'off';
      case Anime4kPreset.a:
        return 'a';
      case Anime4kPreset.b:
        return 'b';
      case Anime4kPreset.c:
        return 'c';
      case Anime4kPreset.aa:
        return 'aa';
      case Anime4kPreset.bb:
        return 'bb';
      case Anime4kPreset.ca:
        return 'ca';
    }
  }

  String get label {
    switch (this) {
      case Anime4kPreset.off:
        return '关闭';
      case Anime4kPreset.a:
        return 'A';
      case Anime4kPreset.b:
        return 'B';
      case Anime4kPreset.c:
        return 'C';
      case Anime4kPreset.aa:
        return 'A+A';
      case Anime4kPreset.bb:
        return 'B+B';
      case Anime4kPreset.ca:
        return 'C+A';
    }
  }

  String get description {
    switch (this) {
      case Anime4kPreset.off:
        return '不使用 Anime4K';
      case Anime4kPreset.a:
        return '通用（更强修复）';
      case Anime4kPreset.b:
        return '通用（更柔和、抗振铃）';
      case Anime4kPreset.c:
        return '干净片源（更保守）';
      case Anime4kPreset.aa:
        return '更强（更慢，易过锐）';
      case Anime4kPreset.bb:
        return '更强（更慢）';
      case Anime4kPreset.ca:
        return '更强（偏保守）';
    }
  }

  bool get isOff => this == Anime4kPreset.off;
}

