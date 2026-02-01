import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class TvProxyPanelPage extends StatefulWidget {
  const TvProxyPanelPage({super.key, required this.url});

  final Uri url;

  @override
  State<TvProxyPanelPage> createState() => _TvProxyPanelPageState();
}

class _TvProxyPanelPageState extends State<TvProxyPanelPage> {
  late final WebViewController _controller;
  bool _loading = true;

  @override
  void initState() {
    super.initState();

    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (!mounted) return;
            setState(() => _loading = true);
          },
          onPageFinished: (_) {
            if (!mounted) return;
            setState(() => _loading = false);
          },
        ),
      )
      ..loadRequest(widget.url);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('代理面板'),
        centerTitle: true,
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: () => _controller.reload(),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          WebViewWidget(controller: _controller),
          if (_loading)
            const Align(
              alignment: Alignment.topCenter,
              child: LinearProgressIndicator(minHeight: 2),
            ),
        ],
      ),
    );
  }
}

