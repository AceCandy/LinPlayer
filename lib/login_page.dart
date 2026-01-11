import 'package:flutter/material.dart';

import 'state/app_state.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key, required this.appState});

  final AppState appState;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _hostCtrl = TextEditingController();
  final _portCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _pwdCtrl = TextEditingController();
  String _scheme = 'https';

  @override
  void dispose() {
    _hostCtrl.dispose();
    _portCtrl.dispose();
    _userCtrl.dispose();
    _pwdCtrl.dispose();
    super.dispose();
  }

  String _defaultPortForScheme(String s) => s == 'http' ? '80' : '443';

  void _applyDefaultPort() {
    _portCtrl.text = _defaultPortForScheme(_scheme);
    setState(() {});
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await widget.appState.login(
      hostOrUrl: _hostCtrl.text.trim(),
      scheme: _scheme,
      port: _portCtrl.text.trim().isEmpty ? null : _portCtrl.text.trim(),
      username: _userCtrl.text.trim(),
      password: _pwdCtrl.text,
    );
    if (widget.appState.error != null && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(widget.appState.error!)),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final loading = widget.appState.isLoading;
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SizedBox(height: 12),
                  const Text(
                    '连接服务器',
                    style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 24),
                  Row(
                    children: [
                      Expanded(
                        flex: 2,
                        child: DropdownButtonFormField<String>(
                          initialValue: _scheme,
                          decoration: const InputDecoration(
                            labelText: '协议',
                            border: OutlineInputBorder(),
                          ),
                          items: const [
                            DropdownMenuItem(value: 'https', child: Text('https')),
                            DropdownMenuItem(value: 'http', child: Text('http')),
                          ],
                          onChanged: (v) {
                            if (v == null) return;
                            setState(() {
                              _scheme = v;
                              if (_portCtrl.text.isEmpty ||
                                  _portCtrl.text == '80' ||
                                  _portCtrl.text == '443') {
                                _portCtrl.text = _defaultPortForScheme(v);
                              }
                            });
                          },
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        flex: 5,
                        child: TextFormField(
                          controller: _hostCtrl,
                          decoration: const InputDecoration(
                            labelText: '服务器地址',
                            hintText: '例如：emby.hills.com 或 1.2.3.4',
                            border: OutlineInputBorder(),
                          ),
                          keyboardType: TextInputType.url,
                          validator: (v) =>
                              (v == null || v.isEmpty) ? '请输入服务器地址' : null,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _portCtrl,
                    decoration: InputDecoration(
                      labelText: '端口（留空默认 ${_scheme == 'http' ? '80' : '443'}）',
                      border: const OutlineInputBorder(),
                      suffixIcon: IconButton(
                        tooltip: '使用默认端口',
                        icon: const Icon(Icons.refresh),
                        onPressed: _applyDefaultPort,
                      ),
                    ),
                    keyboardType: TextInputType.number,
                    validator: (v) {
                      if (v == null || v.isEmpty) return null;
                      final n = int.tryParse(v);
                      if (n == null || n <= 0 || n > 65535) return '端口不合法';
                      return null;
                    },
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _userCtrl,
                    decoration: const InputDecoration(
                      labelText: '账号',
                      border: OutlineInputBorder(),
                    ),
                    validator: (v) =>
                        (v == null || v.isEmpty) ? '请输入账号' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _pwdCtrl,
                    decoration: const InputDecoration(
                      labelText: '密码',
                      border: OutlineInputBorder(),
                    ),
                    obscureText: true,
                    validator: (v) =>
                        (v == null || v.isEmpty) ? '请输入密码' : null,
                  ),
                  const SizedBox(height: 20),
                  SizedBox(
                    height: 48,
                    child: FilledButton(
                      onPressed: loading ? null : _submit,
                      child: loading
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('连接'),
                    ),
                  ),
                  if (widget.appState.error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      widget.appState.error!,
                      style: const TextStyle(color: Colors.redAccent),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
