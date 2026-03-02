import 'package:equatable/equatable.dart';

class CronJob extends Equatable {
  final String id;
  final String name;
  final String command;
  final String schedule;
  final bool enabled;
  final DateTime? lastRun;
  final DateTime? nextRun;
  final String? output;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final String? emailTo;
  final bool logOutput;
  final String? tmuxSession;
  final Map<String, String>? environment;

  const CronJob({
    required this.id,
    required this.name,
    required this.command,
    required this.schedule,
    required this.enabled,
    this.lastRun,
    this.nextRun,
    this.output,
    this.createdAt,
    this.updatedAt,
    this.emailTo,
    this.logOutput = false,
    this.tmuxSession,
    this.environment,
  });

  CronJob copyWith({
    String? id,
    String? name,
    String? command,
    String? schedule,
    bool? enabled,
    DateTime? lastRun,
    DateTime? nextRun,
    String? output,
    DateTime? createdAt,
    DateTime? updatedAt,
    String? emailTo,
    bool? logOutput,
    String? tmuxSession,
    Map<String, String>? environment,
  }) {
    return CronJob(
      id: id ?? this.id,
      name: name ?? this.name,
      command: command ?? this.command,
      schedule: schedule ?? this.schedule,
      enabled: enabled ?? this.enabled,
      lastRun: lastRun ?? this.lastRun,
      nextRun: nextRun ?? this.nextRun,
      output: output ?? this.output,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      emailTo: emailTo ?? this.emailTo,
      logOutput: logOutput ?? this.logOutput,
      tmuxSession: tmuxSession ?? this.tmuxSession,
      environment: environment ?? this.environment,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'command': command,
    'schedule': schedule,
    'enabled': enabled,
    'lastRun': lastRun?.toIso8601String(),
    'nextRun': nextRun?.toIso8601String(),
    'output': output,
    'createdAt': createdAt?.toIso8601String(),
    'updatedAt': updatedAt?.toIso8601String(),
    'emailTo': emailTo,
    'logOutput': logOutput,
    'tmuxSession': tmuxSession,
    'environment': environment,
  };

  factory CronJob.fromJson(Map<String, dynamic> json) => CronJob(
    id: json['id'] as String,
    name: json['name'] as String? ?? 'Unnamed Job',
    command: json['command'] as String,
    schedule: json['schedule'] as String,
    enabled: json['enabled'] as bool? ?? true,
    lastRun: json['lastRun'] != null
        ? DateTime.tryParse(json['lastRun'] as String)
        : null,
    nextRun: json['nextRun'] != null
        ? DateTime.tryParse(json['nextRun'] as String)
        : null,
    output: json['output'] as String?,
    createdAt: json['createdAt'] != null
        ? DateTime.tryParse(json['createdAt'] as String)
        : null,
    updatedAt: json['updatedAt'] != null
        ? DateTime.tryParse(json['updatedAt'] as String)
        : null,
    emailTo: json['emailTo'] as String?,
    logOutput: json['logOutput'] as bool? ?? false,
    tmuxSession: json['tmuxSession'] as String?,
    environment: json['environment'] != null
        ? Map<String, String>.from(json['environment'] as Map)
        : null,
  );

  @override
  List<Object?> get props => [
    id,
    name,
    command,
    schedule,
    enabled,
    lastRun,
    nextRun,
    output,
    createdAt,
    updatedAt,
    emailTo,
    logOutput,
    tmuxSession,
    environment,
  ];
}
