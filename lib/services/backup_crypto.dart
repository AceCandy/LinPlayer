import 'dart:convert';
import 'dart:math';

import 'package:cryptography/cryptography.dart';

class BackupCrypto {
  static const kKdf = 'pbkdf2-hmac-sha256';
  static const kCipher = 'aes-256-gcm';
  static const kDefaultIterations = 200000;
  static const kSaltBytes = 16;
  static const kNonceBytes = 12;
  static const kKeyBits = 256;

  static List<int> _randomBytes(int length) {
    final rnd = Random.secure();
    return List<int>.generate(length, (_) => rnd.nextInt(256), growable: false);
  }

  static Pbkdf2 _pbkdf2(int iterations) {
    return Pbkdf2(
      macAlgorithm: Hmac.sha256(),
      iterations: iterations,
      bits: kKeyBits,
    );
  }

  static AesGcm _aesGcm() => AesGcm.with256bits();

  static Future<SecretKey> _deriveKey({
    required String passphrase,
    required List<int> salt,
    required int iterations,
  }) async {
    final secret = SecretKey(utf8.encode(passphrase));
    return _pbkdf2(iterations).deriveKey(secretKey: secret, nonce: salt);
  }

  static Future<Map<String, dynamic>> encryptJson({
    required String plaintextJson,
    required String passphrase,
  }) async {
    final p = passphrase.trim();
    if (p.isEmpty) throw const FormatException('Empty passphrase');

    final salt = _randomBytes(kSaltBytes);
    final nonce = _randomBytes(kNonceBytes);
    final key = await _deriveKey(
      passphrase: p,
      salt: salt,
      iterations: kDefaultIterations,
    );

    final secretBox = await _aesGcm().encrypt(
      utf8.encode(plaintextJson),
      secretKey: key,
      nonce: nonce,
    );

    return {
      'kdf': kKdf,
      'iterations': kDefaultIterations,
      'salt': base64Encode(salt),
      'cipher': kCipher,
      'nonce': base64Encode(secretBox.nonce),
      'cipherText': base64Encode(secretBox.cipherText),
      'mac': base64Encode(secretBox.mac.bytes),
    };
  }

  static Future<String> decryptJson({
    required Map<String, dynamic> encrypted,
    required String passphrase,
  }) async {
    final p = passphrase.trim();
    if (p.isEmpty) throw const FormatException('Empty passphrase');

    final kdf = (encrypted['kdf'] ?? '').toString().trim();
    final cipher = (encrypted['cipher'] ?? '').toString().trim();
    if (kdf != kKdf) throw FormatException('Unsupported kdf: $kdf');
    if (cipher != kCipher) throw FormatException('Unsupported cipher: $cipher');

    final iterationsRaw = encrypted['iterations'];
    final iterations = switch (iterationsRaw) {
      int v => v,
      num v => v.round(),
      String v => int.tryParse(v.trim()),
      _ => null,
    };
    if (iterations == null || iterations < 10000) {
      throw FormatException('Invalid iterations: ${encrypted['iterations']}');
    }

    final saltB64 = (encrypted['salt'] ?? '').toString().trim();
    final nonceB64 = (encrypted['nonce'] ?? '').toString().trim();
    final cipherTextB64 = (encrypted['cipherText'] ?? '').toString().trim();
    final macB64 = (encrypted['mac'] ?? '').toString().trim();

    if (saltB64.isEmpty ||
        nonceB64.isEmpty ||
        cipherTextB64.isEmpty ||
        macB64.isEmpty) {
      throw const FormatException('Invalid encrypted payload');
    }

    final salt = base64Decode(saltB64);
    final nonce = base64Decode(nonceB64);
    final cipherText = base64Decode(cipherTextB64);
    final macBytes = base64Decode(macB64);

    final key = await _deriveKey(
      passphrase: p,
      salt: salt,
      iterations: iterations,
    );

    final secretBox = SecretBox(
      cipherText,
      nonce: nonce,
      mac: Mac(macBytes),
    );

    final clearBytes = await _aesGcm().decrypt(
      secretBox,
      secretKey: key,
    );
    return utf8.decode(clearBytes);
  }
}

