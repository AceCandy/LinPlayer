import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:lin_player/services/webdav_api.dart';

class _RealHttpOverrides extends HttpOverrides {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('validateRoot works when endpoint rejects /path/ but accepts /path',
      () async {
    final requested = <String>[];

    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));

    server.listen((req) async {
      requested.add('${req.method.toUpperCase()} ${req.uri.path}');
      await req.drain<void>();

      if (req.method.toUpperCase() != 'PROPFIND') {
        req.response.statusCode = HttpStatus.methodNotAllowed;
        await req.response.close();
        return;
      }

      if (req.uri.path == '/dav/') {
        req.response.statusCode = HttpStatus.notFound;
        await req.response.close();
        return;
      }

      if (req.uri.path == '/dav') {
        req.response.statusCode = 207;
        req.response.headers.contentType =
            ContentType('application', 'xml', charset: 'utf-8');
        req.response.write('''<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav</d:href>
    <d:propstat>
      <d:status>HTTP/1.1 200 OK</d:status>
      <d:prop />
    </d:propstat>
  </d:response>
</d:multistatus>''');
        await req.response.close();
        return;
      }

      req.response.statusCode = HttpStatus.notFound;
      await req.response.close();
    });

    await HttpOverrides.runWithHttpOverrides(() async {
      final baseUri =
          Uri.parse('http://${server.address.address}:${server.port}/dav');
      final api = WebDavApi(baseUri: baseUri, username: '', password: '');

      await api.validateRoot();
    }, _RealHttpOverrides());

    expect(requested, ['PROPFIND /dav']);
  });

  test('listDirectory resolves relative hrefs under /dav', () async {
    final requested = <String>[];

    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));

    server.listen((req) async {
      requested.add('${req.method.toUpperCase()} ${req.uri.path}');
      await req.drain<void>();

      if (req.method.toUpperCase() != 'PROPFIND') {
        req.response.statusCode = HttpStatus.methodNotAllowed;
        await req.response.close();
        return;
      }

      if (req.uri.path == '/dav/') {
        req.response.statusCode = HttpStatus.notFound;
        await req.response.close();
        return;
      }

      if (req.uri.path == '/dav') {
        req.response.statusCode = 207;
        req.response.headers.contentType =
            ContentType('application', 'xml', charset: 'utf-8');
        req.response.write('''<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav</d:href>
    <d:propstat>
      <d:status>HTTP/1.1 200 OK</d:status>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>video.mp4</d:href>
    <d:propstat>
      <d:status>HTTP/1.1 200 OK</d:status>
      <d:prop>
        <d:displayname>video.mp4</d:displayname>
        <d:getcontentlength>123</d:getcontentlength>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>''');
        await req.response.close();
        return;
      }

      req.response.statusCode = HttpStatus.notFound;
      await req.response.close();
    });

    final list = await HttpOverrides.runWithHttpOverrides(() async {
      final baseUri =
          Uri.parse('http://${server.address.address}:${server.port}/dav');
      final api = WebDavApi(baseUri: baseUri, username: '', password: '');

      return api.listDirectory(baseUri);
    }, _RealHttpOverrides());

    expect(requested, ['PROPFIND /dav']);
    expect(list.length, 1);
    expect(list.single.name, 'video.mp4');
    expect(list.single.isDirectory, isFalse);
    expect(list.single.uri.path, '/dav/video.mp4');
  });
}
