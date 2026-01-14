class ServerProfile {
  ServerProfile({
    required this.id,
    required this.name,
    required this.baseUrl,
    required this.token,
    required this.userId,
    this.remark,
    Set<String>? hiddenLibraries,
    Map<String, String>? domainRemarks,
  })  : hiddenLibraries = hiddenLibraries ?? <String>{},
        domainRemarks = domainRemarks ?? <String, String>{};

  final String id;
  String name;
  String? remark;

  /// Current selected base url (may point to a "line"/domain).
  String baseUrl;

  String token;
  String userId;

  final Set<String> hiddenLibraries;

  /// User-defined remarks for domains/lines. Key is domain url.
  final Map<String, String> domainRemarks;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'remark': remark,
        'baseUrl': baseUrl,
        'token': token,
        'userId': userId,
        'hiddenLibraries': hiddenLibraries.toList(),
        'domainRemarks': domainRemarks,
      };

  factory ServerProfile.fromJson(Map<String, dynamic> json) {
    return ServerProfile(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      remark: json['remark'] as String?,
      baseUrl: json['baseUrl'] as String? ?? '',
      token: json['token'] as String? ?? '',
      userId: json['userId'] as String? ?? '',
      hiddenLibraries: ((json['hiddenLibraries'] as List?)?.cast<String>() ?? const <String>[])
          .toSet(),
      domainRemarks: (json['domainRemarks'] as Map?)?.map(
            (key, value) => MapEntry(key.toString(), value.toString()),
          ) ??
          <String, String>{},
    );
  }
}

