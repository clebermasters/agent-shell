import 'package:equatable/equatable.dart';

enum DotFileType {
  shell,
  git,
  vim,
  tmux,
  ssh,
  other;

  String get displayName {
    switch (this) {
      case DotFileType.shell:
        return 'Shell';
      case DotFileType.git:
        return 'Git';
      case DotFileType.vim:
        return 'Vim';
      case DotFileType.tmux:
        return 'Tmux';
      case DotFileType.ssh:
        return 'SSH';
      case DotFileType.other:
        return 'Other';
    }
  }

  String get icon {
    switch (this) {
      case DotFileType.shell:
        return '💻';
      case DotFileType.git:
        return '🔀';
      case DotFileType.vim:
        return '📝';
      case DotFileType.tmux:
        return '🖥️';
      case DotFileType.ssh:
        return '🔐';
      case DotFileType.other:
        return '📄';
    }
  }
}

class DotFile extends Equatable {
  final String path;
  final String name;
  final bool isDirectory;
  final int size;
  final DateTime? modified;
  final String? content;
  final List<DotFileVersion>? versions;
  final bool exists;
  final bool writable;
  final DotFileType fileType;

  const DotFile({
    required this.path,
    required this.name,
    required this.isDirectory,
    required this.size,
    this.modified,
    this.content,
    this.versions,
    this.exists = true,
    this.writable = true,
    this.fileType = DotFileType.other,
  });

  DotFile copyWith({
    String? path,
    String? name,
    bool? isDirectory,
    int? size,
    DateTime? modified,
    String? content,
    List<DotFileVersion>? versions,
    bool? exists,
    bool? writable,
    DotFileType? fileType,
  }) {
    return DotFile(
      path: path ?? this.path,
      name: name ?? this.name,
      isDirectory: isDirectory ?? this.isDirectory,
      size: size ?? this.size,
      modified: modified ?? this.modified,
      content: content ?? this.content,
      versions: versions ?? this.versions,
      exists: exists ?? this.exists,
      writable: writable ?? this.writable,
      fileType: fileType ?? this.fileType,
    );
  }

  Map<String, dynamic> toJson() => {
    'path': path,
    'name': name,
    'isDirectory': isDirectory,
    'size': size,
    'modified': modified?.toIso8601String(),
    'content': content,
    'versions': versions?.map((v) => v.toJson()).toList(),
    'exists': exists,
    'writable': writable,
    'fileType': fileType.name,
  };

  factory DotFile.fromJson(Map<String, dynamic> json) => DotFile(
    path: json['path'] as String,
    name: json['name'] as String,
    isDirectory: json['isDirectory'] as bool? ?? false,
    size: json['size'] as int? ?? 0,
    modified: json['modified'] != null
        ? DateTime.parse(json['modified'] as String)
        : null,
    content: json['content'] as String?,
    versions: json['versions'] != null
        ? (json['versions'] as List)
              .map((v) => DotFileVersion.fromJson(v as Map<String, dynamic>))
              .toList()
        : null,
    exists: json['exists'] as bool? ?? true,
    writable: json['writable'] as bool? ?? true,
    fileType: _parseFileType(json['fileType'] as String?),
  );

  static DotFileType _parseFileType(String? type) {
    switch (type?.toLowerCase()) {
      case 'shell':
        return DotFileType.shell;
      case 'git':
        return DotFileType.git;
      case 'vim':
        return DotFileType.vim;
      case 'tmux':
        return DotFileType.tmux;
      case 'ssh':
        return DotFileType.ssh;
      default:
        return DotFileType.other;
    }
  }

  @override
  List<Object?> get props => [
    path,
    name,
    isDirectory,
    size,
    modified,
    content,
    versions,
    exists,
    writable,
    fileType,
  ];
}

class DotFileVersion extends Equatable {
  final String id;
  final DateTime timestamp;
  final String? commitMessage;
  final int size;
  final String? content;

  const DotFileVersion({
    required this.id,
    required this.timestamp,
    this.commitMessage,
    this.size = 0,
    this.content,
  });

  Map<String, dynamic> toJson() => {
    'id': id,
    'timestamp': timestamp.toIso8601String(),
    'commitMessage': commitMessage,
    'size': size,
    'content': content,
  };

  factory DotFileVersion.fromJson(Map<String, dynamic> json) => DotFileVersion(
    id: json['id'] as String,
    timestamp: DateTime.parse(json['timestamp'] as String),
    commitMessage: json['commitMessage'] as String?,
    size: json['size'] as int? ?? 0,
    content: json['content'] as String?,
  );

  @override
  List<Object?> get props => [id, timestamp, commitMessage, size, content];
}

class DotFileTemplate extends Equatable {
  final String name;
  final DotFileType fileType;
  final String description;
  final String content;

  const DotFileTemplate({
    required this.name,
    required this.fileType,
    required this.description,
    required this.content,
  });

  factory DotFileTemplate.fromJson(Map<String, dynamic> json) =>
      DotFileTemplate(
        name: json['name'] as String,
        fileType: _parseFileType(json['fileType'] as String?),
        description: json['description'] as String? ?? '',
        content: json['content'] as String,
      );

  static DotFileType _parseFileType(String? type) {
    switch (type?.toLowerCase()) {
      case 'shell':
        return DotFileType.shell;
      case 'git':
        return DotFileType.git;
      case 'vim':
        return DotFileType.vim;
      case 'tmux':
        return DotFileType.tmux;
      case 'ssh':
        return DotFileType.ssh;
      default:
        return DotFileType.other;
    }
  }

  @override
  List<Object?> get props => [name, fileType, description, content];
}
