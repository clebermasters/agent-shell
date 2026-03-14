class FileEntry {
  final String name;
  final String path;
  final bool isDirectory;
  final int size;
  final DateTime? modified;

  const FileEntry({
    required this.name,
    required this.path,
    required this.isDirectory,
    required this.size,
    this.modified,
  });

  factory FileEntry.fromJson(Map<String, dynamic> json) {
    return FileEntry(
      name: json['name'] as String,
      path: json['path'] as String,
      isDirectory: json['isDirectory'] as bool? ?? false,
      size: (json['size'] as num?)?.toInt() ?? 0,
      modified: json['modified'] != null
          ? DateTime.tryParse(json['modified'] as String)
          : null,
    );
  }
}
