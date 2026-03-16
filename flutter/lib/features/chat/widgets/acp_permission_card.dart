import 'package:flutter/material.dart';
import '../providers/chat_provider.dart';

class AcpPermissionCard extends StatelessWidget {
  final PendingPermission permission;
  final void Function(String requestId, String optionId) onRespond;

  const AcpPermissionCard({
    super.key,
    required this.permission,
    required this.onRespond,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.amber.shade50,
        border: Border.all(color: Colors.amber.shade300, width: 1.5),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
            child: Row(
              children: [
                Icon(Icons.security, color: Colors.amber.shade700, size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Permission Request: ${permission.tool}',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: Colors.amber.shade900,
                      fontSize: 13,
                    ),
                  ),
                ),
              ],
            ),
          ),
          if (permission.command.isNotEmpty)
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 12),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.grey.shade900,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                permission.command,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                  color: Colors.white,
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: permission.options.map((option) {
                final id = option['id'] as String? ?? '';
                final label = option['label'] as String? ?? id;
                final isDeny = id.toLowerCase().contains('deny') ||
                    id.toLowerCase().contains('reject') ||
                    id.toLowerCase().contains('cancel');
                return Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: isDeny
                      ? OutlinedButton(
                          onPressed: () => onRespond(permission.requestId, id),
                          style: OutlinedButton.styleFrom(
                            foregroundColor: Colors.red,
                            side: const BorderSide(color: Colors.red),
                          ),
                          child: Text(label),
                        )
                      : ElevatedButton(
                          onPressed: () => onRespond(permission.requestId, id),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.green,
                            foregroundColor: Colors.white,
                          ),
                          child: Text(label),
                        ),
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }
}
