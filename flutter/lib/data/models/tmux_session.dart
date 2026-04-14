import 'package:equatable/equatable.dart';

class TmuxSession extends Equatable {
  final String name;
  final bool attached;
  final int windows;
  final DateTime? created;
  final String? tool;

  const TmuxSession({
    required this.name,
    required this.attached,
    required this.windows,
    this.tool,
    this.created,
  });

  TmuxSession copyWith({
    String? name,
    bool? attached,
    int? windows,
    DateTime? created,
    String? tool,
  }) {
    return TmuxSession(
      name: name ?? this.name,
      attached: attached ?? this.attached,
      windows: windows ?? this.windows,
      tool: tool ?? this.tool,
      created: created ?? this.created,
    );
  }

  Map<String, dynamic> toJson() => {
    'name': name,
    'attached': attached,
    'windows': windows,
    'tool': tool,
    'created': created?.toIso8601String(),
  };

  factory TmuxSession.fromJson(Map<String, dynamic> json) => TmuxSession(
    name: json['name'] as String,
    attached: json['attached'] as bool? ?? false,
    windows: json['windows'] as int? ?? 0,
    tool: json['tool'] as String?,
    created: json['created'] != null
        ? DateTime.parse(json['created'] as String)
        : null,
  );

  @override
  List<Object?> get props => [name, attached, windows, tool, created];
}

class TmuxWindow extends Equatable {
  final int id;
  final String name;
  final bool active;
  final int panes;

  const TmuxWindow({
    required this.id,
    required this.name,
    required this.active,
    required this.panes,
  });

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'active': active,
    'panes': panes,
  };

  factory TmuxWindow.fromJson(Map<String, dynamic> json) => TmuxWindow(
    id: json['id'] as int,
    name: json['name'] as String,
    active: json['active'] as bool? ?? false,
    panes: json['panes'] as int? ?? 1,
  );

  @override
  List<Object?> get props => [id, name, active, panes];
}
