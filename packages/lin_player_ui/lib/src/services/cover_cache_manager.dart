import 'package:flutter_cache_manager/flutter_cache_manager.dart';

class CoverCacheManager extends CacheManager {
  static const _key = 'lin_player_cover_cache_v1';

  // Default behavior: limit cache size & auto-expire stale entries.
  static const _limitedStalePeriod = Duration(days: 30);
  static const _limitedMaxObjects = 800;

  // "Unlimited" is practically unlimited; still bounded by device storage.
  static const _unlimitedStalePeriod = Duration(days: 36500);
  static const _unlimitedMaxObjects = 0x7fffffff;

  static bool _unlimited = false;
  static bool get unlimited => _unlimited;
  static void setUnlimited(bool value) => _unlimited = value;

  static final CoverCacheManager instance = CoverCacheManager._();

  CoverCacheManager._()
      : super(
          _CoverCacheConfig(
            cacheKey: _key,
            unlimited: () => _unlimited,
          ),
        );
}

class _CoverCacheConfig implements Config {
  _CoverCacheConfig({
    required this.cacheKey,
    required this.unlimited,
  })  : repo = JsonCacheInfoRepository(databaseName: cacheKey),
        fileSystem = IOFileSystem(cacheKey),
        fileService = _DynamicDurationHttpFileService(
          validDuration: () => unlimited()
              ? CoverCacheManager._unlimitedStalePeriod
              : CoverCacheManager._limitedStalePeriod,
        );

  final bool Function() unlimited;

  @override
  final String cacheKey;

  @override
  Duration get stalePeriod => unlimited()
      ? CoverCacheManager._unlimitedStalePeriod
      : CoverCacheManager._limitedStalePeriod;

  @override
  int get maxNrOfCacheObjects => unlimited()
      ? CoverCacheManager._unlimitedMaxObjects
      : CoverCacheManager._limitedMaxObjects;

  @override
  final CacheInfoRepository repo;

  @override
  final FileSystem fileSystem;

  @override
  final FileService fileService;
}

class _DynamicDurationHttpFileService extends HttpFileService {
  _DynamicDurationHttpFileService({required this.validDuration});

  final Duration Function() validDuration;

  @override
  Future<FileServiceResponse> get(
    String url, {
    Map<String, String>? headers,
  }) async {
    final response = await super.get(url, headers: headers);
    return _FixedDurationFileServiceResponse(
      delegate: response,
      validDuration: validDuration(),
    );
  }
}

class _FixedDurationFileServiceResponse implements FileServiceResponse {
  const _FixedDurationFileServiceResponse({
    required this.delegate,
    required this.validDuration,
  });

  final FileServiceResponse delegate;
  final Duration validDuration;

  @override
  Stream<List<int>> get content => delegate.content;

  @override
  int? get contentLength => delegate.contentLength;

  @override
  int get statusCode => delegate.statusCode;

  @override
  DateTime get validTill => DateTime.now().add(validDuration);

  @override
  String? get eTag => delegate.eTag;

  @override
  String get fileExtension => delegate.fileExtension;
}
