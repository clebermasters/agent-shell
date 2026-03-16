import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/tmux_session.dart';
import '../../../data/models/acp_session.dart';
import '../../../data/models/cron_job.dart';
import '../../../data/models/dotfile.dart';

enum PaletteItemType { session, acpSession, cronJob, dotfile, nav }

class PaletteItem {
  final String title;
  final String subtitle;
  final PaletteItemType type;
  final dynamic data;

  const PaletteItem({
    required this.title,
    required this.subtitle,
    required this.type,
    this.data,
  });
}

class CommandPaletteState {
  final List<PaletteItem> allItems;
  final List<PaletteItem> filtered;
  final String query;

  const CommandPaletteState({
    this.allItems = const [],
    this.filtered = const [],
    this.query = '',
  });

  CommandPaletteState copyWith({
    List<PaletteItem>? allItems,
    List<PaletteItem>? filtered,
    String? query,
  }) {
    return CommandPaletteState(
      allItems: allItems ?? this.allItems,
      filtered: filtered ?? this.filtered,
      query: query ?? this.query,
    );
  }
}

class CommandPaletteNotifier extends StateNotifier<CommandPaletteState> {
  CommandPaletteNotifier() : super(const CommandPaletteState());

  // These indices must match HomeScreen's NavigationBar order
  static const _navItems = [
    PaletteItem(title: 'Sessions', subtitle: 'Go to Sessions tab', type: PaletteItemType.nav, data: 0),
    PaletteItem(title: 'Cron', subtitle: 'Go to Cron tab', type: PaletteItemType.nav, data: 1),
    PaletteItem(title: 'Dotfiles', subtitle: 'Go to Dotfiles tab', type: PaletteItemType.nav, data: 2),
    PaletteItem(title: 'System', subtitle: 'Go to System tab', type: PaletteItemType.nav, data: 3),
  ];

  void rebuild({
    required List<TmuxSession> sessions,
    required List<AcpSession> acpSessions,
    required List<CronJob> cronJobs,
    required List<DotFile> dotfiles,
  }) {
    final items = <PaletteItem>[
      ..._navItems,
      ...sessions.map((s) => PaletteItem(
            title: s.name,
            subtitle: 'Terminal session',
            type: PaletteItemType.session,
            data: s,
          )),
      ...acpSessions.map((s) => PaletteItem(
            title: s.title.isEmpty ? s.cwd.split('/').last : s.title,
            subtitle: 'AI session · ${s.cwd}',
            type: PaletteItemType.acpSession,
            data: s,
          )),
      ...cronJobs.map((j) => PaletteItem(
            title: j.name,
            subtitle: 'Cron · ${j.schedule}',
            type: PaletteItemType.cronJob,
            data: j,
          )),
      ...dotfiles.map((f) => PaletteItem(
            title: f.path.split('/').last,
            subtitle: f.path,
            type: PaletteItemType.dotfile,
            data: f,
          )),
    ];

    final q = state.query;
    state = state.copyWith(
      allItems: items,
      filtered: _filter(items, q),
    );
  }

  void search(String query) {
    state = state.copyWith(
      query: query,
      filtered: _filter(state.allItems, query),
    );
  }

  List<PaletteItem> _filter(List<PaletteItem> items, String query) {
    if (query.isEmpty) return items;
    final q = query.toLowerCase();
    return items
        .where((i) =>
            i.title.toLowerCase().contains(q) ||
            i.subtitle.toLowerCase().contains(q))
        .toList();
  }
}

final commandPaletteProvider =
    StateNotifierProvider<CommandPaletteNotifier, CommandPaletteState>(
  (ref) => CommandPaletteNotifier(),
);
