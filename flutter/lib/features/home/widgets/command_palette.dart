import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/tmux_session.dart';
import '../../../data/models/acp_session.dart';
import '../providers/command_palette_provider.dart';
import '../../sessions/providers/sessions_provider.dart';
import '../../cron/providers/cron_provider.dart';
import '../../dotfiles/providers/dotfiles_provider.dart';
import '../../terminal/screens/terminal_screen.dart';
import '../../chat/screens/chat_screen.dart';

class CommandPalette extends ConsumerStatefulWidget {
  final void Function(int tabIndex) onNavigate;

  const CommandPalette({super.key, required this.onNavigate});

  @override
  ConsumerState<CommandPalette> createState() => _CommandPaletteState();
}

class _CommandPaletteState extends ConsumerState<CommandPalette> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _rebuildIndex();
      _focusNode.requestFocus();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _rebuildIndex() {
    final sessions = ref.read(sessionsProvider).sessions;
    final acpSessions = ref.read(sessionsProvider).acpSessions;
    final cronJobs = ref.read(cronProvider).jobs;
    final dotfiles = ref.read(dotfilesProvider).files;
    ref.read(commandPaletteProvider.notifier).rebuild(
          sessions: sessions,
          acpSessions: acpSessions,
          cronJobs: cronJobs,
          dotfiles: dotfiles,
        );
  }

  void _handleSelect(PaletteItem item) {
    Navigator.of(context).pop();
    switch (item.type) {
      case PaletteItemType.nav:
        widget.onNavigate(item.data as int);
      case PaletteItemType.session:
        final session = item.data as TmuxSession;
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => TerminalScreen(sessionName: session.name),
        ));
      case PaletteItemType.acpSession:
        final acp = item.data as AcpSession;
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => ChatScreen(
            sessionName: acp.sessionId,
            isAcp: true,
            cwd: acp.cwd,
          ),
        ));
      case PaletteItemType.cronJob:
        widget.onNavigate(1); // Cron tab
      case PaletteItemType.dotfile:
        widget.onNavigate(2); // Dotfiles tab
    }
  }

  IconData _iconFor(PaletteItemType type) {
    switch (type) {
      case PaletteItemType.nav:
        return Icons.tab;
      case PaletteItemType.session:
        return Icons.terminal;
      case PaletteItemType.acpSession:
        return Icons.smart_toy;
      case PaletteItemType.cronJob:
        return Icons.schedule;
      case PaletteItemType.dotfile:
        return Icons.folder;
    }
  }

  @override
  Widget build(BuildContext context) {
    final paletteState = ref.watch(commandPaletteProvider);
    final items = paletteState.filtered;

    return Material(
      color: Colors.transparent,
      child: GestureDetector(
        onTap: () => Navigator.of(context).pop(),
        child: Container(
          color: Colors.black54,
          alignment: Alignment.topCenter,
          padding: EdgeInsets.only(
            top: MediaQuery.of(context).padding.top + 16,
            left: 16,
            right: 16,
          ),
          child: GestureDetector(
            onTap: () {}, // prevent dismiss when tapping inside
            child: Container(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.of(context).size.height * 0.7,
              ),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surface,
                borderRadius: BorderRadius.circular(16),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.3),
                    blurRadius: 20,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: TextField(
                      controller: _controller,
                      focusNode: _focusNode,
                      decoration: InputDecoration(
                        hintText: 'Search sessions, cron jobs, dotfiles…',
                        prefixIcon: const Icon(Icons.search),
                        suffixIcon: _controller.text.isNotEmpty
                            ? IconButton(
                                icon: const Icon(Icons.clear),
                                onPressed: () {
                                  _controller.clear();
                                  ref.read(commandPaletteProvider.notifier).search('');
                                },
                              )
                            : null,
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide.none,
                        ),
                        filled: true,
                      ),
                      onChanged: (q) {
                        ref.read(commandPaletteProvider.notifier).search(q);
                      },
                    ),
                  ),
                  const Divider(height: 1),
                  Flexible(
                    child: ListView.builder(
                      shrinkWrap: true,
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      itemCount: items.length,
                      itemBuilder: (context, index) {
                        final item = items[index];
                        return ListTile(
                          leading: Icon(_iconFor(item.type), size: 20),
                          title: Text(item.title),
                          subtitle: Text(
                            item.subtitle,
                            style: const TextStyle(fontSize: 12),
                          ),
                          onTap: () => _handleSelect(item),
                          dense: true,
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

void showCommandPalette(BuildContext context, void Function(int) onNavigate) {
  showGeneralDialog(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'Dismiss',
    barrierColor: Colors.transparent,
    transitionDuration: const Duration(milliseconds: 200),
    pageBuilder: (context, animation, secondaryAnimation) {
      return FadeTransition(
        opacity: animation,
        child: CommandPalette(onNavigate: onNavigate),
      );
    },
  );
}
