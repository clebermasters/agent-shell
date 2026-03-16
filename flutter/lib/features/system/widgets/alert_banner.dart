import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/system_provider.dart';

/// Thresholds for proactive alerts
const _diskThreshold = 90.0;
const _memoryThreshold = 85.0;
const _cpuThreshold = 90.0;

class AlertBanner extends ConsumerWidget {
  final VoidCallback? onTap;

  const AlertBanner({super.key, this.onTap});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stats = ref.watch(systemProvider.select((s) => s.stats));
    if (stats == null) return const SizedBox.shrink();

    final alerts = <String>[];
    if (stats.diskUsage >= _diskThreshold) {
      alerts.add('Disk ${stats.diskUsage.toStringAsFixed(0)}%');
    }
    if (stats.memoryUsage >= _memoryThreshold) {
      alerts.add('Memory ${stats.memoryUsage.toStringAsFixed(0)}%');
    }
    if (stats.cpuUsage >= _cpuThreshold) {
      alerts.add('CPU ${stats.cpuUsage.toStringAsFixed(0)}%');
    }

    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 300),
      child: alerts.isEmpty
          ? const SizedBox.shrink()
          : GestureDetector(
              key: const ValueKey('alert-banner'),
              onTap: onTap,
              child: Container(
                width: double.infinity,
                height: 40,
                color: Colors.red.shade700,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'High usage: ${alerts.join(' · ')} — Tap to view',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const Icon(Icons.arrow_forward_ios, color: Colors.white, size: 12),
                  ],
                ),
              ),
            ),
    );
  }
}
