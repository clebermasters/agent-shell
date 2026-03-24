import 'dart:typed_data';
import 'package:flutter/material.dart' hide Notification;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/notification.dart';
import '../providers/alerts_provider.dart';
import '../widgets/alert_audio_sheet.dart';
import '../widgets/notification_card.dart';
import 'alert_image_viewer_screen.dart';
import 'alert_markdown_screen.dart';

class AlertsScreen extends ConsumerWidget {
  const AlertsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final alertsState = ref.watch(alertsProvider);
    final notifier = ref.read(alertsProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Alerts'),
        actions: [
          if (alertsState.unreadCount > 0)
            TextButton(
              onPressed: notifier.markAllRead,
              child: const Text('Mark all read'),
            ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: notifier.fetchNotifications,
        child: _buildBody(context, ref, alertsState, notifier),
      ),
    );
  }

  Widget _buildBody(
    BuildContext context,
    WidgetRef ref,
    AlertsState alertsState,
    AlertsNotifier notifier,
  ) {
    if (alertsState.isLoading && alertsState.notifications.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    if (alertsState.notifications.isEmpty) {
      return ListView(
        children: const [
          SizedBox(height: 120),
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.notifications_none, size: 56, color: Colors.grey),
                SizedBox(height: 12),
                Text(
                  'No notifications yet',
                  style: TextStyle(color: Colors.grey, fontSize: 16),
                ),
                SizedBox(height: 4),
                Text(
                  'AI agents will notify you here when tasks complete',
                  style: TextStyle(color: Colors.grey, fontSize: 13),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ],
      );
    }

    return ListView.builder(
      itemCount: alertsState.notifications.length,
      itemBuilder: (context, index) {
        final notification = alertsState.notifications[index];
        return NotificationCard(
          notification: notification,
          onMarkRead: () => notifier.markRead(notification.id),
          onFileTap: (file) => _openFile(context, ref, file),
        );
      },
    );
  }

  Future<void> _openFile(
    BuildContext context,
    WidgetRef ref,
    NotificationFile file,
  ) async {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const AlertDialog(
        content: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(width: 16),
            Text('Loading file...'),
          ],
        ),
      ),
    );

    final bytes =
        await ref.read(alertsProvider.notifier).downloadFile(file.id);

    if (!context.mounted) return;
    Navigator.of(context).pop(); // close loading dialog

    if (bytes == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to download ${file.filename}')),
      );
      return;
    }

    final mime = file.mimeType;

    if (mime.startsWith('image/')) {
      _openImageViewer(context, bytes, file.filename);
    } else if (mime.contains('markdown') || mime == 'text/plain') {
      _openMarkdownViewer(context, bytes, file.filename);
    } else if (mime.startsWith('audio/')) {
      _openAudioSheet(context, bytes, file.filename, mime);
    } else {
      _showFileInfo(context, file);
    }
  }

  void _openImageViewer(
      BuildContext context, Uint8List bytes, String filename) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            AlertImageViewerScreen(bytes: bytes, filename: filename),
      ),
    );
  }

  void _openMarkdownViewer(
      BuildContext context, Uint8List bytes, String filename) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            AlertMarkdownScreen(bytes: bytes, filename: filename),
      ),
    );
  }

  void _openAudioSheet(
      BuildContext context, Uint8List bytes, String filename, String mimeType) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => AlertAudioSheet(
        bytes: bytes,
        filename: filename,
        mimeType: mimeType,
      ),
    );
  }

  void _showFileInfo(BuildContext context, NotificationFile file) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(file.filename,
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text('Type: ${file.mimeType}',
                  style: Theme.of(context).textTheme.bodySmall),
              Text(
                'Size: ${_formatBytes(file.size)}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
