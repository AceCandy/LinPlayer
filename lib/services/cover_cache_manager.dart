import 'package:flutter_cache_manager/flutter_cache_manager.dart';

class CoverCacheManager extends CacheManager {
  static const _key = 'lin_player_cover_cache_v1';
  static final CoverCacheManager instance = CoverCacheManager._();

  CoverCacheManager._()
      : super(
          Config(
            _key,
            stalePeriod: const Duration(days: 30),
            maxNrOfCacheObjects: 800,
            repo: JsonCacheInfoRepository(databaseName: _key),
            fileService: _FixedDurationHttpFileService(
              validDuration: const Duration(days: 30),
            ),
          ),
        );
}

class _FixedDurationHttpFileService extends HttpFileService {
  _FixedDurationHttpFileService({required this.validDuration});

  final Duration validDuration;

  @override
  Future<FileServiceResponse> get(
    String url, {
    Map<String, String>? headers,
  }) async {
    final response = await super.get(url, headers: headers);
    return _FixedDurationFileServiceResponse(
      delegate: response,
      validDuration: validDuration,
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

