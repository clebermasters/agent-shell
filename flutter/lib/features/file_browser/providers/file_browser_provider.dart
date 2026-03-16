import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/file_entry.dart';
import '../../../data/services/websocket_service.dart';
import '../../sessions/providers/sessions_provider.dart';

enum SortMode { nameAsc, nameDesc, sizeAsc, sizeDesc, modifiedDesc }

class FileBrowserState {
  final String currentPath;
  final List<FileEntry> entries;
  final bool isLoading;
  final String? error;
  final String? resolvedCwd;
  final SortMode sortMode;
  final bool showHidden;

  const FileBrowserState({
    this.currentPath = '',
    this.entries = const [],
    this.isLoading = false,
    this.error,
    this.resolvedCwd,
    this.sortMode = SortMode.nameAsc,
    this.showHidden = false,
  });

  FileBrowserState copyWith({
    String? currentPath,
    List<FileEntry>? entries,
    bool? isLoading,
    String? error,
    String? resolvedCwd,
    SortMode? sortMode,
    bool? showHidden,
  }) {
    return FileBrowserState(
      currentPath: currentPath ?? this.currentPath,
      entries: entries ?? this.entries,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      resolvedCwd: resolvedCwd ?? this.resolvedCwd,
      sortMode: sortMode ?? this.sortMode,
      showHidden: showHidden ?? this.showHidden,
    );
  }

  List<FileEntry> get sortedEntries {
    final visible = showHidden
        ? List<FileEntry>.from(entries)
        : entries.where((e) => !e.name.startsWith('.')).toList();

    // Directories always first
    final dirs = visible.where((e) => e.isDirectory).toList();
    final files = visible.where((e) => !e.isDirectory).toList();

    int Function(FileEntry, FileEntry) comparator;
    switch (sortMode) {
      case SortMode.nameAsc:
        comparator = (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase());
        break;
      case SortMode.nameDesc:
        comparator = (a, b) => b.name.toLowerCase().compareTo(a.name.toLowerCase());
        break;
      case SortMode.sizeAsc:
        comparator = (a, b) => a.size.compareTo(b.size);
        break;
      case SortMode.sizeDesc:
        comparator = (a, b) => b.size.compareTo(a.size);
        break;
      case SortMode.modifiedDesc:
        comparator = (a, b) {
          if (a.modified == null && b.modified == null) return 0;
          if (a.modified == null) return 1;
          if (b.modified == null) return -1;
          return b.modified!.compareTo(a.modified!);
        };
        break;
    }

    dirs.sort(comparator);
    files.sort(comparator);
    return [...dirs, ...files];
  }
}

class FileBrowserNotifier extends StateNotifier<FileBrowserState> {
  final WebSocketService _wsService;
  StreamSubscription? _sub;

  FileBrowserNotifier(this._wsService) : super(const FileBrowserState()) {
    _sub = _wsService.messages.listen(_onMessage);
  }

  void _onMessage(Map<String, dynamic> message) {
    final type = message['type'] as String?;
    switch (type) {
      case 'files-list':
        final path = message['path'] as String? ?? state.currentPath;
        final entries = (message['entries'] as List?)
                ?.map((e) => FileEntry.fromJson(e as Map<String, dynamic>))
                .toList() ??
            [];
        state = state.copyWith(
          currentPath: path,
          entries: entries,
          isLoading: false,
          error: null,
        );
        break;
      case 'session-cwd':
        final cwd = message['cwd'] as String? ?? '';
        state = state.copyWith(resolvedCwd: cwd);
        if (cwd.isNotEmpty && state.isLoading) {
          listFiles(cwd);
        }
        break;
    }
  }

  void listFiles(String path) {
    state = state.copyWith(
      currentPath: path,
      isLoading: true,
      error: null,
    );
    _wsService.listFiles(path);
  }

  void navigateTo(String path) {
    listFiles(path);
  }

  void requestSessionCwd(String sessionName) {
    state = state.copyWith(isLoading: true, error: null);
    _wsService.getSessionCwd(sessionName);
  }

  void setSortMode(SortMode mode) {
    state = state.copyWith(sortMode: mode);
  }

  void toggleHidden() {
    state = state.copyWith(showHidden: !state.showHidden);
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }
}

final fileBrowserProvider =
    StateNotifierProvider.autoDispose<FileBrowserNotifier, FileBrowserState>(
  (ref) {
    final ws = ref.watch(sharedWebSocketServiceProvider);
    return FileBrowserNotifier(ws);
  },
);
