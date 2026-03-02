import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/cron_job.dart';
import '../providers/cron_provider.dart';
import 'cron_job_editor_screen.dart';

class CronScreen extends ConsumerStatefulWidget {
  const CronScreen({super.key});

  @override
  ConsumerState<CronScreen> createState() => _CronScreenState();
}

class _CronScreenState extends ConsumerState<CronScreen> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      ref.read(cronProvider.notifier).refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final cronState = ref.watch(cronProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Cron Jobs'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(cronProvider.notifier).refresh(),
          ),
        ],
      ),
      body: cronState.isLoading
          ? const Center(child: CircularProgressIndicator())
          : cronState.jobs.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.schedule, size: 64, color: Colors.grey[400]),
                  const SizedBox(height: 16),
                  Text(
                    'No cron jobs',
                    style: Theme.of(
                      context,
                    ).textTheme.titleLarge?.copyWith(color: Colors.grey[400]),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Tap + to create a new job',
                    style: Theme.of(
                      context,
                    ).textTheme.bodyMedium?.copyWith(color: Colors.grey[500]),
                  ),
                ],
              ),
            )
          : ListView.builder(
              itemCount: cronState.jobs.length,
              itemBuilder: (context, index) {
                final job = cronState.jobs[index];
                return _CronJobTile(
                  job: job,
                  onEdit: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => CronJobEditorScreen(job: job),
                      ),
                    );
                  },
                  onToggle: () {
                    ref.read(cronProvider.notifier).toggleCronJob(job.id);
                  },
                  onDelete: () async {
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('Delete Cron Job'),
                        content: Text('Delete "${job.name}"?'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('Cancel'),
                          ),
                          TextButton(
                            onPressed: () => Navigator.pop(context, true),
                            style: TextButton.styleFrom(
                              foregroundColor: Colors.red,
                            ),
                            child: const Text('Delete'),
                          ),
                        ],
                      ),
                    );
                    if (confirmed == true) {
                      ref.read(cronProvider.notifier).deleteCronJob(job.id);
                    }
                  },
                );
              },
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => const CronJobEditorScreen(),
            ),
          );
        },
        child: const Icon(Icons.add),
      ),
    );
  }
}

String _formatSchedule(String schedule) {
  final patterns = {
    '0 * * * *': 'Every hour',
    '*/5 * * * *': 'Every 5 minutes',
    '*/10 * * * *': 'Every 10 minutes',
    '*/15 * * * *': 'Every 15 minutes',
    '*/30 * * * *': 'Every 30 minutes',
    '0 0 * * *': 'Daily at midnight',
    '0 9 * * *': 'Daily at 9:00 AM',
    '0 0 * * 0': 'Weekly on Sunday',
    '0 0 1 * *': 'Monthly on the 1st',
    '0 0 * * 1-5': 'Weekdays at midnight',
  };
  return patterns[schedule] ?? schedule;
}

String _formatNextRun(DateTime? nextRun) {
  if (nextRun == null) return '';
  final now = DateTime.now();
  final diff = nextRun.difference(now);

  if (diff.isNegative) return 'Overdue';

  final days = diff.inDays;
  final hours = diff.inHours % 24;
  final minutes = diff.inMinutes % 60;

  if (days > 0) return 'in $days day${days > 1 ? 's' : ''}';
  if (hours > 0) return 'in $hours hour${hours > 1 ? 's' : ''}';
  if (minutes > 0) return 'in $minutes minute${minutes > 1 ? 's' : ''}';
  return 'soon';
}

class _CronJobTile extends StatelessWidget {
  final CronJob job;
  final VoidCallback onEdit;
  final VoidCallback onToggle;
  final VoidCallback onDelete;

  const _CronJobTile({
    required this.job,
    required this.onEdit,
    required this.onToggle,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: job.enabled ? Colors.green : Colors.grey,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    job.name,
                    style: const TextStyle(
                      fontWeight: FontWeight.w500,
                      fontSize: 16,
                    ),
                  ),
                ),
                IconButton(
                  icon: Icon(
                    job.enabled ? Icons.pause : Icons.play_arrow,
                    size: 20,
                  ),
                  onPressed: onToggle,
                  tooltip: job.enabled ? 'Disable' : 'Enable',
                ),
                IconButton(
                  icon: const Icon(Icons.edit, size: 20),
                  onPressed: onEdit,
                  tooltip: 'Edit',
                ),
                IconButton(
                  icon: const Icon(Icons.delete, color: Colors.red, size: 20),
                  onPressed: onDelete,
                  tooltip: 'Delete',
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _formatSchedule(job.schedule),
              style: TextStyle(
                fontFamily: 'monospace',
                color: job.enabled ? Colors.green[700] : Colors.grey,
                fontSize: 13,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              job.command,
              style: TextStyle(
                fontFamily: 'monospace',
                color: Colors.grey[600],
                fontSize: 12,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            if (job.nextRun != null) ...[
              const SizedBox(height: 4),
              Text(
                'Next: ${_formatNextRun(job.nextRun)}',
                style: TextStyle(fontSize: 12, color: Colors.grey[500]),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
