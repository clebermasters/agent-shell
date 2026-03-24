import 'package:flutter/material.dart' hide Notification;
import '../../../data/models/notification.dart';

class NotificationCard extends StatefulWidget {
  final Notification notification;
  final VoidCallback? onMarkRead;
  final void Function(NotificationFile file)? onFileTap;

  const NotificationCard({
    super.key,
    required this.notification,
    this.onMarkRead,
    this.onFileTap,
  });

  @override
  State<NotificationCard> createState() => _NotificationCardState();
}

class _NotificationCardState extends State<NotificationCard> {
  bool _isExpanded = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final n = widget.notification;

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: InkWell(
        onTap: () => setState(() => _isExpanded = !_isExpanded),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          decoration: BoxDecoration(
            border: Border(
              left: BorderSide(
                color: n.read ? Colors.transparent : colorScheme.primary,
                width: 4,
              ),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: AnimatedSize(
              duration: const Duration(milliseconds: 250),
              curve: Curves.easeInOut,
              alignment: Alignment.topCenter,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Title
                  Text(
                    n.title,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: n.read ? FontWeight.normal : FontWeight.bold,
                    ),
                    maxLines: _isExpanded ? null : 1,
                    overflow:
                        _isExpanded ? TextOverflow.visible : TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  // Body
                  Text(
                    n.body,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                    maxLines: _isExpanded ? null : 2,
                    overflow:
                        _isExpanded ? TextOverflow.visible : TextOverflow.ellipsis,
                  ),
                  // File chips (always visible if present, but more prominent when expanded)
                  if (n.files != null && n.files!.isNotEmpty) ...[
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: n.files!
                          .map((f) => _buildFileChip(context, f))
                          .toList(),
                    ),
                  ],
                  const SizedBox(height: 8),
                  // Source chip + timestamp row
                  Row(
                    children: [
                      _buildSourceChip(context),
                      const Spacer(),
                      Text(
                        _formatTimestamp(n.timestamp),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colorScheme.outline,
                        ),
                      ),
                    ],
                  ),
                  // Expanded actions
                  if (_isExpanded && !n.read) ...[
                    const SizedBox(height: 12),
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        onPressed: widget.onMarkRead,
                        icon: const Icon(Icons.done, size: 18),
                        label: const Text('Mark as read'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFileChip(BuildContext context, NotificationFile file) {
    final colorScheme = Theme.of(context).colorScheme;

    IconData icon;
    Color color;
    final mime = file.mimeType;

    if (mime.startsWith('image/')) {
      icon = Icons.image_outlined;
      color = Colors.teal;
    } else if (mime.startsWith('audio/')) {
      icon = Icons.audiotrack_outlined;
      color = Colors.deepOrange;
    } else if (mime.contains('markdown') || mime == 'text/plain') {
      icon = Icons.article_outlined;
      color = Colors.indigo;
    } else if (mime == 'application/pdf') {
      icon = Icons.picture_as_pdf_outlined;
      color = Colors.red;
    } else {
      icon = Icons.insert_drive_file_outlined;
      color = colorScheme.secondary;
    }

    return GestureDetector(
      onTap: () => widget.onFileTap?.call(file),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.3)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 14, color: color),
            const SizedBox(width: 5),
            Text(
              file.filename,
              style: TextStyle(
                fontSize: 12,
                color: color,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(width: 4),
            Text(
              _formatBytes(file.size),
              style: TextStyle(
                fontSize: 11,
                color: color.withValues(alpha: 0.7),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '${bytes}B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)}KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  Widget _buildSourceChip(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final source = widget.notification.source;

    Color chipColor;
    IconData icon;

    switch (source) {
      case 'cron':
        chipColor = Colors.orange;
        icon = Icons.schedule;
        break;
      case 'agent':
        chipColor = Colors.purple;
        icon = Icons.smart_toy;
        break;
      case 'webhook':
        chipColor = Colors.blue;
        icon = Icons.webhook;
        break;
      default:
        chipColor = colorScheme.secondary;
        icon = Icons.notifications;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: chipColor.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: chipColor),
          const SizedBox(width: 4),
          Text(
            source,
            style: TextStyle(
              fontSize: 11,
              color: chipColor,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }

  String _formatTimestamp(DateTime timestamp) {
    final now = DateTime.now();
    final diff = now.difference(timestamp);

    if (diff.inMinutes < 1) {
      return 'Just now';
    } else if (diff.inMinutes < 60) {
      return '${diff.inMinutes}m ago';
    } else if (diff.inHours < 24) {
      return '${diff.inHours}h ago';
    } else if (diff.inDays < 7) {
      return '${diff.inDays}d ago';
    } else {
      return '${timestamp.month}/${timestamp.day}/${timestamp.year}';
    }
  }
}
