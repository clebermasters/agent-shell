import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/file_entry.dart';
import '../../../data/services/websocket_service.dart';
import '../../sessions/providers/sessions_provider.dart';

class FileBrowserState {
  final String currentPath;
  final List<FileEntry> entries;
  final bool isLoading;
  final String? error;
  final String? resolvedCwd;

  const FileBrowserState({
    this.currentPath = '',
    this.entries = const [],
    this.isLoading = false,
    this.error,
    this.resolvedCwd,
  });

  FileBrowserState copyWith({
    String? currentPath,
    List<FileEntry>? entries,
    bool? isLoading,
    String? error,
    String? resolvedCwd,
  }) {
    return FileBrowserState(
      currentPath: currentPath ?? this.currentPath,
      entries: entries ?? this.entries,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      resolvedCwd: resolvedCwd ?? this.resolvedCwd,
    );
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
