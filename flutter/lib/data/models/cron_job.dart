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
  final String? workdir;
  final String? prompt;
  final String? llmProvider;
  final String? llmModel;

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
    this.workdir,
    this.prompt,
    this.llmProvider,
    this.llmModel,
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
    String? workdir,
    String? prompt,
    String? llmProvider,
    String? llmModel,
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
      workdir: workdir ?? this.workdir,
      prompt: prompt ?? this.prompt,
      llmProvider: llmProvider ?? this.llmProvider,
      llmModel: llmModel ?? this.llmModel,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'command': command,
    'schedule': schedule,
    'enabled': enabled,
    'lastRun': lastRun?.toUtc().toIso8601String(),
    'nextRun': nextRun?.toUtc().toIso8601String(),
    'output': output,
    'createdAt': createdAt?.toUtc().toIso8601String(),
    'updatedAt': updatedAt?.toUtc().toIso8601String(),
    'emailTo': emailTo,
    'logOutput': logOutput,
    'tmuxSession': tmuxSession,
    'environment': environment,
    'workdir': workdir,
    'prompt': prompt,
    'llmProvider': llmProvider,
    'llmModel': llmModel,
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
    workdir: json['workdir'] as String?,
    prompt: json['prompt'] as String?,
    llmProvider: json['llmProvider'] as String?,
    llmModel: json['llmModel'] as String?,
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
    workdir,
    prompt,
    llmProvider,
    llmModel,
  ];
}
