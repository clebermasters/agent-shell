import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/system_stats.dart';
import '../providers/system_provider.dart';

class SystemScreen extends ConsumerWidget {
  const SystemScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final systemState = ref.watch(systemProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('System'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(systemProvider.notifier).refresh(),
          ),
        ],
      ),
      body: systemState.isLoading && systemState.stats == null
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: () async {
                ref.read(systemProvider.notifier).refresh();
              },
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // CPU
                    _StatCard(
                      title: 'CPU',
                      icon: Icons.memory,
                      value: systemState.stats != null
                          ? systemState.stats!.cpuUsage.toStringAsFixed(2)
                          : '--',
                      subtitle: systemState.stats != null
                          ? _cpuSubtitle(systemState.stats!)
                          : null,
                      progress: systemState.stats != null
                          ? (systemState.stats!.cpuUsage /
                                  (systemState.stats!.cpuCores > 0
                                      ? systemState.stats!.cpuCores
                                      : 1))
                              .clamp(0.0, 100.0)
                          : null,
                      color: _getUsageColor(systemState.stats?.cpuUsage),
                      extra: systemState.stats != null
                          ? _LoadAvgRow(stats: systemState.stats!)
                          : null,
                    ),
                    const SizedBox(height: 16),

                    // Memory
                    _StatCard(
                      title: 'Memory',
                      icon: Icons.storage,
                      value: systemState.stats != null
                          ? '${systemState.stats!.memoryUsedFormatted} / ${systemState.stats!.memoryTotalFormatted}'
                          : '--',
                      subtitle: systemState.stats != null
                          ? '${systemState.stats!.memoryUsage.toStringAsFixed(1)}%  •  ${systemState.stats!.memoryFreeFormatted} free'
                          : null,
                      progress: systemState.stats?.memoryUsage,
                      color: _getUsageColor(systemState.stats?.memoryUsage),
                    ),
                    const SizedBox(height: 16),

                    // Disk
                    _StatCard(
                      title: 'Disk',
                      icon: Icons.disc_full,
                      value: systemState.stats != null
                          ? '${systemState.stats!.diskUsedFormatted} / ${systemState.stats!.diskTotalFormatted}'
                          : '--',
                      subtitle: systemState.stats != null
                          ? '${systemState.stats!.diskUsage.toStringAsFixed(1)}%  •  ${systemState.stats!.diskFreeFormatted} free'
                          : null,
                      progress: systemState.stats?.diskUsage,
                      color: _getUsageColor(systemState.stats?.diskUsage),
                    ),
                    const SizedBox(height: 16),

                    // Uptime
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.timer),
                        title: const Text('Uptime'),
                        subtitle: Text(
                          systemState.stats?.uptime ?? '--',
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 16,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),

                    // System info
                    if (systemState.stats != null &&
                        systemState.stats!.hostname.isNotEmpty)
                      _SystemInfoCard(stats: systemState.stats!),
                  ],
                ),
              ),
            ),
    );
  }

  String _cpuSubtitle(SystemStats stats) {
    final parts = <String>[];
    if (stats.cpuCores > 0) parts.add('${stats.cpuCores} cores');
    if (stats.cpuModel.isNotEmpty) parts.add(stats.cpuModel);
    return parts.join('  •  ');
  }

  Color _getUsageColor(double? usage) {
    if (usage == null) return Colors.grey;
    if (usage < 50) return Colors.green;
    if (usage < 80) return Colors.orange;
    return Colors.red;
  }
}

// ── Load average row shown below the CPU progress bar ────────────────────────

class _LoadAvgRow extends StatelessWidget {
  final SystemStats stats;

  const _LoadAvgRow({required this.stats});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _LoadAvgChip(label: '1m', value: stats.cpuUsage),
        const SizedBox(width: 8),
        _LoadAvgChip(label: '5m', value: stats.loadAvg5m),
        const SizedBox(width: 8),
        _LoadAvgChip(label: '15m', value: stats.loadAvg15m),
      ],
    );
  }
}

class _LoadAvgChip extends StatelessWidget {
  final String label;
  final double value;

  const _LoadAvgChip({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        '$label: ${value.toStringAsFixed(2)}',
        style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
      ),
    );
  }
}

// ── System info card (hostname / OS / arch) ───────────────────────────────────

class _SystemInfoCard extends StatelessWidget {
  final SystemStats stats;

  const _SystemInfoCard({required this.stats});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.info_outline,
                    color: Theme.of(context).colorScheme.primary),
                const SizedBox(width: 8),
                Text('System',
                    style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 12),
            _InfoRow(label: 'Hostname', value: stats.hostname),
            if (stats.platform.isNotEmpty)
              _InfoRow(
                label: 'OS',
                value: '${stats.platform}  ${stats.arch}',
              ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 80,
            child: Text(
              label,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 13,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Generic stat card ─────────────────────────────────────────────────────────

class _StatCard extends StatelessWidget {
  final String title;
  final IconData icon;
  final String value;
  final String? subtitle;
  final double? progress;
  final Color color;
  final Widget? extra;

  const _StatCard({
    required this.title,
    required this.icon,
    required this.value,
    this.subtitle,
    this.progress,
    required this.color,
    this.extra,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const Spacer(),
                Text(
                  value,
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                ),
              ],
            ),
            if (subtitle != null) ...[
              const SizedBox(height: 4),
              Text(
                subtitle!,
                style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    fontSize: 13),
              ),
            ],
            if (progress != null) ...[
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: (progress! / 100).clamp(0.0, 1.0),
                  backgroundColor: Colors.grey[300],
                  valueColor: AlwaysStoppedAnimation<Color>(color),
                  minHeight: 8,
                ),
              ),
            ],
            if (extra != null) ...[
              const SizedBox(height: 12),
              extra!,
            ],
          ],
        ),
      ),
    );
  }
}
