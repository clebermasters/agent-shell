import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/tmux_session.dart';
import '../../../data/models/acp_session.dart';
import '../providers/sessions_provider.dart';
import '../../terminal/screens/terminal_screen.dart';
import '../../chat/screens/chat_screen.dart';
import '../../hosts/screens/host_selection_screen.dart';
import '../../hosts/providers/hosts_provider.dart';
import '../../system/widgets/system_mini_bar.dart';
import '../../alerts/widgets/alerts_bell_button.dart';

final selectedBackendProvider = StateProvider<String>((ref) => 'tmux');

class SessionsScreen extends ConsumerStatefulWidget {
  const SessionsScreen({super.key});

  @override
  ConsumerState<SessionsScreen> createState() => _SessionsScreenState();
}

class _SessionsScreenState extends ConsumerState<SessionsScreen> {
  final Set<String> _selectedIds = {};
  bool _isSelecting = false;
  StreamSubscription<({String sessionId, String cwd})>? _newSessionSub;

  void _enterSelectionMode(String sessionId) {
    setState(() {
      _isSelecting = true;
      _selectedIds.add(sessionId);
    });
  }

  void _exitSelectionMode() {
    setState(() {
      _isSelecting = false;
      _selectedIds.clear();
    });
  }

  void _toggleSelection(String sessionId) {
    setState(() {
      if (_selectedIds.contains(sessionId)) {
        _selectedIds.remove(sessionId);
        if (_selectedIds.isEmpty) _isSelecting = false;
      } else {
        _selectedIds.add(sessionId);
      }
    });
  }

  Future<void> _deleteSelected() async {
    final ids = List<String>.from(_selectedIds);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete Sessions'),
        content: Text('Delete ${ids.length} session${ids.length == 1 ? '' : 's'}? This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      for (final id in ids) {
        ref.read(sessionsProvider.notifier).deleteAcpSession(id);
      }
      _exitSelectionMode();
    }
  }

  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      ref.read(sessionsProvider.notifier).refresh();
      _newSessionSub = ref.read(sessionsProvider.notifier).newAcpSession.listen((event) {
        if (!mounted) return;
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => ChatScreen(
            sessionName: event.sessionId,
            windowIndex: 0,
            isAcp: true,
            cwd: event.cwd,
          ),
        )).then((_) {
          if (mounted) ref.read(sessionsProvider.notifier).refresh();
        });
      });
    });
  }

  @override
  void dispose() {
    _newSessionSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final sessionsState = ref.watch(sessionsProvider);
    final hostsState = ref.watch(hostsProvider);

    return Scaffold(
      appBar: _isSelecting
          ? AppBar(
              leading: IconButton(
                icon: const Icon(Icons.close),
                onPressed: _exitSelectionMode,
              ),
              title: Text('${_selectedIds.length} selected'),
              actions: [
                IconButton(
                  icon: const Icon(Icons.delete, color: Colors.red),
                  onPressed: _selectedIds.isEmpty ? null : _deleteSelected,
                  tooltip: 'Delete selected',
                ),
              ],
            )
          : AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Sessions'),
            if (hostsState.selectedHost != null)
              Text(
                hostsState.selectedHost!.name,
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
          ],
        ),
        actions: [
          const AlertsBellButton(),
          IconButton(
            icon: const Icon(Icons.dns),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const HostSelectionScreen()),
              );
            },
            tooltip: 'Servers',
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              ref.read(sessionsProvider.notifier).refresh();
            },
          ),
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () => _showCreateSessionDialog(context, ref),
            tooltip: 'New Session',
          ),
        ],
      ),
      body: Column(
        children: [
          const SystemMiniBar(),
          Expanded(child: sessionsState.isLoading
          ? const Center(child: CircularProgressIndicator())
          : sessionsState.sessions.isEmpty && sessionsState.acpSessions.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.terminal, size: 64, color: Colors.grey[400]),
                  const SizedBox(height: 16),
                  Text(
                    'No sessions',
                    style: Theme.of(
                      context,
                    ).textTheme.titleLarge?.copyWith(color: Colors.grey[400]),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Create a new session to get started',
                    style: Theme.of(
                      context,
                    ).textTheme.bodyMedium?.copyWith(color: Colors.grey[500]),
                  ),
                ],
              ),
            )
          : ListView(
              children: [
                if (sessionsState.sessions.isNotEmpty) ...[
                  const Padding(
                    padding: EdgeInsets.all(16.0),
                    child: Text(
                      'Terminal Sessions',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  ...sessionsState.sessions.map(
                    (session) => _SessionTile(
                      session: session,
                      onAttach: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) =>
                                TerminalScreen(sessionName: session.name),
                          ),
                        );
                      },
                      onChat: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => ChatScreen(
                              sessionName: session.name,
                              windowIndex: 0,
                            ),
                          ),
                        );
                      },
                      onRename: () => _showRenameSessionDialog(context, ref, session.name),
                      onKill: () async {
                        final confirmed = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('Kill Session'),
                            content: Text(
                              'Are you sure you want to kill "${session.name}"?',
                            ),
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
                                child: const Text('Kill'),
                              ),
                            ],
                          ),
                        );
                        if (confirmed == true) {
                          ref
                              .read(sessionsProvider.notifier)
                              .killSession(session.name);
                        }
                      },
                    ),
                  ),
                ],
                if (sessionsState.acpSessions.isNotEmpty) ...[
                  const Padding(
                    padding: EdgeInsets.all(16.0),
                    child: Text(
                      'Direct Sessions (ACP)',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  ...sessionsState.acpSessions.map(
                    (session) => _AcpSessionTile(
                      session: session,
                      isSelecting: _isSelecting,
                      isSelected: _selectedIds.contains(session.sessionId),
                      onTap: () {
                        if (_isSelecting) {
                          _toggleSelection(session.sessionId);
                        } else {
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => ChatScreen(
                                sessionName: session.sessionId,
                                windowIndex: 0,
                                isAcp: true,
                                cwd: session.cwd,
                              ),
                            ),
                          );
                        }
                      },
                      onLongPress: () => _enterSelectionMode(session.sessionId),
                      onDelete: () async {
                        final confirmed = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('Delete ACP Session'),
                            content: Text(
                              'Delete session in "${session.cwd.split('/').last}"? This cannot be undone.',
                            ),
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
                          ref
                              .read(sessionsProvider.notifier)
                              .deleteAcpSession(session.sessionId);
                        }
                      },
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _showCreateSessionDialog(context, ref),
        child: const Icon(Icons.add),
      ),
    );
  }

  void _showRenameSessionDialog(BuildContext context, WidgetRef ref, String currentName) {
    final controller = TextEditingController(text: currentName);

    void doRename(String newName, NavigatorState nav) {
      if (newName.isEmpty || newName == currentName) return;
      ref.read(sharedWebSocketServiceProvider).renameSession(currentName, newName);
      Future.delayed(const Duration(milliseconds: 500), () {
        if (mounted) ref.read(sessionsProvider.notifier).refresh();
      });
    }

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rename Session'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'New Name'),
          onSubmitted: (value) {
            final nav = Navigator.of(ctx);
            doRename(value.trim(), nav);
            nav.pop();
          },
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('Cancel')),
          ElevatedButton(
            onPressed: () {
              final nav = Navigator.of(ctx);
              doRename(controller.text.trim(), nav);
              nav.pop();
            },
            child: const Text('Rename'),
          ),
        ],
      ),
    ).then((_) => controller.dispose());
  }

  void _showCreateSessionDialog(BuildContext context, WidgetRef ref) {
    final controller = TextEditingController();

    showDialog(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) {
          // Read current backend from provider each time the widget rebuilds
          final currentBackend = ref.watch(selectedBackendProvider);

          return AlertDialog(
            title: const Text('New Session'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: controller,
                  autofocus: true,
                  decoration: InputDecoration(
                    labelText: currentBackend == 'tmux'
                        ? 'Session Name'
                        : 'Working Directory',
                    hintText: currentBackend == 'tmux'
                        ? 'e.g., my-session'
                        : 'e.g., /home/user/project',
                  ),
                  onSubmitted: (value) {
                    if (value.isNotEmpty) {
                      ref.read(sessionsProvider.notifier).createSession(value);
                      Navigator.pop(context);
                    }
                  },
                ),
                const SizedBox(height: 16),
                const Text(
                  'Backend',
                  style: TextStyle(fontSize: 12, color: Colors.grey),
                ),
                const SizedBox(height: 8),
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(
                      value: 'tmux',
                      label: Text('Terminal'),
                      icon: Icon(Icons.terminal),
                    ),
                    ButtonSegment(
                      value: 'acp',
                      label: Text('Direct'),
                      icon: Icon(Icons.smart_toy),
                    ),
                  ],
                  selected: {currentBackend},
                  onSelectionChanged: (Set<String> selection) {
                    ref.read(selectedBackendProvider.notifier).state =
                        selection.first;
                    setDialogState(() {});
                  },
                ),
                const SizedBox(height: 8),
                Text(
                  currentBackend == 'tmux'
                      ? 'Terminal mode: Full terminal with tmux'
                      : 'Direct mode: Chat-focused (ACP)',
                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              ElevatedButton(
                onPressed: () {
                  if (controller.text.isNotEmpty) {
                    final ws = ref.read(sharedWebSocketServiceProvider);
                    final backend = ref.read(selectedBackendProvider);
                    if (backend == 'acp') {
                      ws.selectBackend('acp');
                      ws.acpCreateSession(controller.text);
                    } else {
                      ref
                          .read(sessionsProvider.notifier)
                          .createSession(controller.text);
                    }
                    Navigator.pop(context);
                  }
                },
                child: const Text('Create'),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _SessionTile extends StatelessWidget {
  final TmuxSession session;
  final VoidCallback onAttach;
  final VoidCallback onChat;
  final VoidCallback onKill;
  final VoidCallback onRename;

  const _SessionTile({
    required this.session,
    required this.onAttach,
    required this.onChat,
    required this.onKill,
    required this.onRename,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: ListTile(
        leading: Icon(
          Icons.terminal,
          color: session.attached ? Colors.green : Colors.grey,
        ),
        title: Text(session.name),
        subtitle: Text(
          '${session.windows} window${session.windows != 1 ? 's' : ''}',
          style: TextStyle(color: Colors.grey[500]),
        ),
        trailing: PopupMenuButton(
          itemBuilder: (context) => [
            const PopupMenuItem(
              value: 'attach',
              child: ListTile(
                leading: Icon(Icons.terminal),
                title: Text('Terminal'),
                contentPadding: EdgeInsets.zero,
              ),
            ),
            const PopupMenuItem(
              value: 'chat',
              child: ListTile(
                leading: Icon(Icons.chat_bubble),
                title: Text('Chat'),
                contentPadding: EdgeInsets.zero,
              ),
            ),
            const PopupMenuItem(
              value: 'rename',
              child: ListTile(
                leading: Icon(Icons.edit),
                title: Text('Rename'),
                contentPadding: EdgeInsets.zero,
              ),
            ),
            const PopupMenuItem(
              value: 'kill',
              child: ListTile(
                leading: Icon(Icons.delete, color: Colors.red),
                title: Text('Kill', style: TextStyle(color: Colors.red)),
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ],
          onSelected: (value) {
            if (value == 'attach') {
              onAttach();
            } else if (value == 'chat') {
              onChat();
            } else if (value == 'rename') {
              onRename();
            } else if (value == 'kill') {
              onKill();
            }
          },
        ),
        onTap: onAttach,
      ),
    );
  }
}

class _AcpSessionTile extends StatelessWidget {
  final AcpSession session;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  final VoidCallback onDelete;
  final bool isSelecting;
  final bool isSelected;

  const _AcpSessionTile({
    required this.session,
    required this.onTap,
    required this.onLongPress,
    required this.onDelete,
    required this.isSelecting,
    required this.isSelected,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: isSelecting
          ? Checkbox(
              value: isSelected,
              onChanged: (_) => onTap(),
            )
          : const Icon(Icons.smart_toy),
      title: Text(
        session.title.isEmpty ? session.cwd.split('/').last : session.title,
      ),
      subtitle: Text(session.cwd),
      trailing: isSelecting
          ? null
          : PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert),
              onSelected: (value) {
                if (value == 'delete') onDelete();
              },
              itemBuilder: (_) => [
                const PopupMenuItem(
                  value: 'delete',
                  child: Row(
                    children: [
                Icon(Icons.delete, color: Colors.red),
                SizedBox(width: 8),
                Text('Delete', style: TextStyle(color: Colors.red)),
              ],
            ),
          ),
        ],
      ),
      onTap: onTap,
      onLongPress: onLongPress,
    );
  }
}
