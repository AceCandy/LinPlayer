enum MediaServerType {
  emby,
  jellyfin,
  plex,
  webdav,
}

MediaServerType mediaServerTypeFromId(String? id) {
  switch ((id ?? '').trim().toLowerCase()) {
    case 'jellyfin':
      return MediaServerType.jellyfin;
    case 'plex':
      return MediaServerType.plex;
    case 'webdav':
      return MediaServerType.webdav;
    case 'emby':
    default:
      return MediaServerType.emby;
  }
}

extension MediaServerTypeX on MediaServerType {
  String get id {
    switch (this) {
      case MediaServerType.emby:
        return 'emby';
      case MediaServerType.jellyfin:
        return 'jellyfin';
      case MediaServerType.plex:
        return 'plex';
      case MediaServerType.webdav:
        return 'webdav';
    }
  }

  String get label {
    switch (this) {
      case MediaServerType.emby:
        return 'Emby';
      case MediaServerType.jellyfin:
        return 'Jellyfin';
      case MediaServerType.plex:
        return 'Plex';
      case MediaServerType.webdav:
        return 'WebDAV';
    }
  }

  bool get isEmbyLike =>
      this == MediaServerType.emby || this == MediaServerType.jellyfin;

  bool get isWebDav => this == MediaServerType.webdav;
}
