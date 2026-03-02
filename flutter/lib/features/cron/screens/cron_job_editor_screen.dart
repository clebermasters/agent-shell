import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/cron_job.dart';
import '../providers/cron_provider.dart';

class CronJobEditorScreen extends ConsumerStatefulWidget {
  final CronJob? job;

  const CronJobEditorScreen({super.key, this.job});

  @override
  ConsumerState<CronJobEditorScreen> createState() =>
      _CronJobEditorScreenState();
}

class _CronJobEditorScreenState extends ConsumerState<CronJobEditorScreen> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _scheduleController;
  late TextEditingController _commandController;
  late TextEditingController _emailController;
  late TextEditingController _tmuxController;
  bool _logOutput = false;
  bool _showAdvanced = false;

  bool get isEditing => widget.job != null;

  static const schedulePresets = [
    {'label': 'Every hour', 'value': '0 * * * *'},
    {'label': 'Every day', 'value': '0 0 * * *'},
    {'label': 'Every week', 'value': '0 0 * * 0'},
    {'label': 'Every month', 'value': '0 0 1 * *'},
    {'label': 'Every 5 min', 'value': '*/5 * * * *'},
    {'label': 'Every 30 min', 'value': '*/30 * * * *'},
    {'label': 'Weekdays 9am', 'value': '0 9 * * 1-5'},
  ];

  static final scheduleDescriptions = {
    '* * * * *': 'Every minute',
    '0 * * * *': 'Every hour at minute 0',
    '*/5 * * * *': 'Every 5 minutes',
    '*/10 * * * *': 'Every 10 minutes',
    '*/15 * * * *': 'Every 15 minutes',
    '*/30 * * * *': 'Every 30 minutes',
    '0 0 * * *': 'Every day at midnight',
    '0 9 * * *': 'Every day at 9:00 AM',
    '0 0 * * 0': 'Every Sunday at midnight',
    '0 0 1 * *': 'Monthly on the 1st at midnight',
    '0 0 * * 1-5': 'Weekdays at midnight',
    '0 9 * * 1-5': 'Weekdays at 9:00 AM',
  };

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.job?.name ?? '');
    _scheduleController = TextEditingController(
      text: widget.job?.schedule ?? '0 * * * *',
    );
    _commandController = TextEditingController(text: widget.job?.command ?? '');
    _emailController = TextEditingController(text: widget.job?.emailTo ?? '');
    _tmuxController = TextEditingController(
      text: widget.job?.tmuxSession ?? '',
    );
    _logOutput = widget.job?.logOutput ?? false;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _scheduleController.dispose();
    _commandController.dispose();
    _emailController.dispose();
    _tmuxController.dispose();
    super.dispose();
  }

  String get scheduleDescription {
    return scheduleDescriptions[_scheduleController.text] ?? 'Custom schedule';
  }

  bool get isValid {
    return _nameController.text.trim().isNotEmpty &&
        _scheduleController.text.trim().isNotEmpty &&
        _commandController.text.trim().isNotEmpty;
  }

  void _save() {
    if (!isValid) return;

    final job = CronJob(
      id: widget.job?.id ?? DateTime.now().millisecondsSinceEpoch.toString(),
      name: _nameController.text.trim(),
      schedule: _scheduleController.text.trim(),
      command: _commandController.text.trim(),
      enabled: widget.job?.enabled ?? true,
      lastRun: widget.job?.lastRun,
      nextRun: widget.job?.nextRun,
      createdAt: widget.job?.createdAt ?? DateTime.now(),
      updatedAt: DateTime.now(),
      emailTo: _emailController.text.trim().isEmpty
          ? null
          : _emailController.text.trim(),
      logOutput: _logOutput,
      tmuxSession: _tmuxController.text.trim().isEmpty
          ? null
          : _tmuxController.text.trim(),
    );

    if (isEditing) {
      ref.read(cronProvider.notifier).updateCronJob(job);
    } else {
      ref.read(cronProvider.notifier).createCronJob(job);
    }

    Navigator.pop(context);
  }

  void _testCommand() {
    if (_commandController.text.trim().isEmpty) return;
    ref.read(cronProvider.notifier).testCommand(_commandController.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    final cronState = ref.watch(cronProvider);

    return Scaffold(
      appBar: AppBar(
        title: Text(isEditing ? 'Edit Cron Job' : 'Create Cron Job'),
        actions: [
          TextButton(
            onPressed: isValid ? _save : null,
            child: Text(isEditing ? 'Update' : 'Create'),
          ),
        ],
      ),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextField(
              controller: _nameController,
              decoration: const InputDecoration(
                labelText: 'Job Name',
                hintText: 'e.g., Backup Database',
                border: OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 16),
            Text('Schedule', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: schedulePresets.map((preset) {
                final isSelected = _scheduleController.text == preset['value'];
                return ChoiceChip(
                  label: Text(preset['label']!),
                  selected: isSelected,
                  onSelected: (_) {
                    setState(() {
                      _scheduleController.text = preset['value']!;
                    });
                  },
                );
              }).toList(),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _scheduleController,
              decoration: const InputDecoration(
                labelText: 'Cron Expression',
                hintText: '* * * * * (minute hour day month weekday)',
                border: OutlineInputBorder(),
              ),
              style: const TextStyle(fontFamily: 'monospace'),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 4),
            Text(
              scheduleDescription,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _commandController,
              decoration: const InputDecoration(
                labelText: 'Command',
                hintText: '/home/user/scripts/backup.sh',
                border: OutlineInputBorder(),
              ),
              style: const TextStyle(fontFamily: 'monospace'),
              maxLines: 3,
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                ElevatedButton.icon(
                  onPressed: _commandController.text.trim().isEmpty
                      ? null
                      : _testCommand,
                  icon: cronState.isTesting
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.play_arrow, size: 18),
                  label: Text(
                    cronState.isTesting ? 'Testing...' : 'Test Command',
                  ),
                ),
              ],
            ),
            if (cronState.testOutput != null) ...[
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey[900],
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  cronState.testOutput!,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: Colors.white,
                  ),
                ),
              ),
            ],
            const SizedBox(height: 16),
            InkWell(
              onTap: () => setState(() => _showAdvanced = !_showAdvanced),
              child: Row(
                children: [
                  Icon(
                    _showAdvanced
                        ? Icons.keyboard_arrow_down
                        : Icons.keyboard_arrow_right,
                    size: 20,
                  ),
                  const SizedBox(width: 4),
                  const Text('Advanced Options'),
                ],
              ),
            ),
            if (_showAdvanced) ...[
              const SizedBox(height: 16),
              TextField(
                controller: _emailController,
                decoration: const InputDecoration(
                  labelText: 'Email Output To',
                  hintText: 'user@example.com',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              SwitchListTile(
                title: const Text('Log command output to file'),
                value: _logOutput,
                onChanged: (value) => setState(() => _logOutput = value),
                contentPadding: EdgeInsets.zero,
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _tmuxController,
                decoration: const InputDecoration(
                  labelText: 'Run in TMUX Session',
                  hintText: 'session-name',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
