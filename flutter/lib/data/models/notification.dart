import 'package:equatable/equatable.dart';

class Notification extends Equatable {
  final String id;
  final String title;
  final String body;
  final String source;
  final String? sourceDetail;
  final DateTime timestamp;
  final bool read;
  final int fileCount;
  final List<NotificationFile>? files;

  const Notification({
    required this.id,
    required this.title,
    required this.body,
    required this.source,
    this.sourceDetail,
    required this.timestamp,
    required this.read,
    required this.fileCount,
    this.files,
  });

  factory Notification.fromJson(Map<String, dynamic> json) => Notification(
    id: json['id'] as String,
    title: json['title'] as String,
    body: json['body'] as String,
    source: json['source'] as String,
    sourceDetail: json['sourceDetail'] as String?,
    timestamp: DateTime.fromMillisecondsSinceEpoch(json['timestampMillis'] as int),
    read: json['read'] as bool? ?? false,
    fileCount: json['fileCount'] as int? ?? 0,
    files: (json['files'] as List<dynamic>?)
        ?.map((f) => NotificationFile.fromJson(f as Map<String, dynamic>))
        .toList(),
  );

  @override
  List<Object?> get props => [id, title, body, source, sourceDetail, timestamp, read, fileCount, files];
}

class NotificationFile extends Equatable {
  final String id;
  final String filename;
  final String mimeType;
  final int size;

  const NotificationFile({
    required this.id,
    required this.filename,
    required this.mimeType,
    required this.size,
  });

  factory NotificationFile.fromJson(Map<String, dynamic> json) => NotificationFile(
    id: json['id'] as String,
    filename: json['filename'] as String,
    mimeType: json['mimeType'] as String,
    size: json['size'] as int,
  );

  @override
  List<Object?> get props => [id, filename, mimeType, size];
}
