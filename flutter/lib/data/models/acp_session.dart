import 'package:equatable/equatable.dart';

class AcpSession extends Equatable {
  final String sessionId;
  final String cwd;
  final String title;
  final String updatedAt;

  const AcpSession({
    required this.sessionId,
    required this.cwd,
    required this.title,
    required this.updatedAt,
  });

  AcpSession copyWith({
    String? sessionId,
    String? cwd,
    String? title,
    String? updatedAt,
  }) {
    return AcpSession(
      sessionId: sessionId ?? this.sessionId,
      cwd: cwd ?? this.cwd,
      title: title ?? this.title,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, dynamic> toJson() => {
    'sessionId': sessionId,
    'cwd': cwd,
    'title': title,
    'updatedAt': updatedAt,
  };

  factory AcpSession.fromJson(Map<String, dynamic> json) => AcpSession(
    sessionId: json['sessionId'] as String? ?? '',
    cwd: json['cwd'] as String? ?? '',
    title: json['title'] as String? ?? '',
    updatedAt: json['updatedAt'] as String? ?? '',
  );

  @override
  List<Object?> get props => [sessionId, cwd, title, updatedAt];
}
