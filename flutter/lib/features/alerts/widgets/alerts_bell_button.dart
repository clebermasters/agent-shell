import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/alerts_provider.dart';
import '../screens/alerts_screen.dart';

class AlertsBellButton extends ConsumerWidget {
  const AlertsBellButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unread = ref.watch(alertsProvider).unreadCount;

    return Badge(
      isLabelVisible: unread > 0,
      label: Text('$unread'),
      child: IconButton(
        icon: const Icon(Icons.notifications_outlined),
        tooltip: 'Alerts',
        onPressed: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const AlertsScreen()),
        ),
      ),
    );
  }
}
