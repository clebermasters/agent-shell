import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/system_provider.dart';

class SystemMiniBar extends ConsumerWidget {
  const SystemMiniBar({super.key});

  Color _colorForPercent(double percent) {
    if (percent >= 90) return Colors.red;
    if (percent >= 75) return Colors.orange;
    return Colors.green;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stats = ref.watch(systemProvider.select((s) => s.stats));
    if (stats == null) return const SizedBox.shrink();

    final cpu = stats.cpuUsage.clamp(0.0, 100.0);
    final mem = stats.memoryUsage.clamp(0.0, 100.0);
    final disk = stats.diskUsage.clamp(0.0, 100.0);

    return Container(
      height: 40,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.5),
        border: Border(
          bottom: BorderSide(
            color: Theme.of(context).dividerColor,
            width: 0.5,
          ),
        ),
      ),
      child: Row(
        children: [
          _StatChip(label: 'CPU', value: cpu, color: _colorForPercent(cpu)),
          const SizedBox(width: 8),
          _StatChip(label: 'MEM', value: mem, color: _colorForPercent(mem)),
          const SizedBox(width: 8),
          _StatChip(label: 'DSK', value: disk, color: _colorForPercent(disk)),
          const Spacer(),
          Text(
            stats.hostname,
            style: TextStyle(fontSize: 11, color: Colors.grey[500]),
          ),
        ],
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  final String label;
  final double value;
  final Color color;

  const _StatChip({
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withOpacity(0.4), width: 0.8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: TextStyle(fontSize: 10, color: color, fontWeight: FontWeight.w600),
          ),
          const SizedBox(width: 4),
          Text(
            '${value.toStringAsFixed(0)}%',
            style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.bold),
          ),
        ],
      ),
    );
  }
}
