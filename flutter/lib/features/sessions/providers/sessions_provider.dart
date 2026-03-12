import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/tmux_session.dart';
import '../../../data/models/acp_session.dart';
import '../../../data/services/websocket_service.dart';
import '../../hosts/providers/hosts_provider.dart';

final sharedWebSocketServiceProvider = Provider<WebSocketService>((ref) {
  final service = WebSocketService();

  // Listen to host changes and reconnect when needed
  ref.listen<HostsState>(hostsProvider, (previous, next) {
    if (next.selectedHost != null && !service.isConnected) {
      service.connect('${next.selectedHost!.wsUrl}/ws');
    }
  });

  // Initial connection if host already selected
  final hostsState = ref.watch(hostsProvider);
  if (hostsState.selectedHost != null) {
    service.connect('${hostsState.selectedHost!.wsUrl}/ws');
  }

  ref.onDispose(() => service.dispose());
  return service;
});

class SessionsState {
  final List<TmuxSession> sessions;
  final List<AcpSession> acpSessions;
  final bool isLoading;
  final String? error;

  const SessionsState({
    this.sessions = const [],
    this.acpSessions = const [],
    this.isLoading = false,
    this.error,
  });

  SessionsState copyWith({
    List<TmuxSession>? sessions,
    List<AcpSession>? acpSessions,
    bool? isLoading,
    String? error,
  }) {
    return SessionsState(
      sessions: sessions ?? this.sessions,
      acpSessions: acpSessions ?? this.acpSessions,
      isLoading: isLoading ?? this.isLoading,
      error: error,
    );
  }
}

class SessionsNotifier extends StateNotifier<SessionsState> {
  final WebSocketService _wsService;
  final _newAcpSessionController = StreamController<({String sessionId, String cwd})>.broadcast();
  Stream<({String sessionId, String cwd})> get newAcpSession => _newAcpSessionController.stream;

  SessionsNotifier(this._wsService) : super(const SessionsState()) {
    _init();
  }

  void _init() {
    _wsService.messages.listen((message) {
      // Handle both response formats: 'sessions-list' (Capacitor) and 'session_list' (legacy)
      final type = message['type'] as String?;
      if (type == 'sessions-list' || type == 'session_list') {
        final elapsed = _refreshStartedAt != null
            ? DateTime.now().difference(_refreshStartedAt!).inMilliseconds
            : -1;
        // ignore: avoid_print
        print('[TIMING] sessions-list received in ${elapsed}ms');
        final sessions =
            (message['sessions'] as List?)
                ?.map((s) => TmuxSession.fromJson(s as Map<String, dynamic>))
                .toList() ??
            [];
        state = state.copyWith(sessions: sessions, isLoading: false);
      } else if (type == 'acp-sessions-listed') {
        final elapsed = _refreshStartedAt != null
            ? DateTime.now().difference(_refreshStartedAt!).inMilliseconds
            : -1;
        // ignore: avoid_print
        print('[TIMING] acp-sessions-listed received in ${elapsed}ms');
        final sessions =
            (message['sessions'] as List?)
                ?.map((s) => AcpSession.fromJson(s as Map<String, dynamic>))
                .toList() ??
            [];
        state = state.copyWith(acpSessions: sessions, isLoading: false);
      } else if (type == 'acp-session-created') {
        final sessionId = message['sessionId'] as String?;
        final cwd = message['cwd'] as String? ?? '';
        refresh();
        if (sessionId != null) _newAcpSessionController.add((sessionId: sessionId, cwd: cwd));
      } else if (type == 'acp-session-deleted') {
        final sessionId = message['sessionId'] as String?;
        if (sessionId != null) {
          // Optimistically remove from list — the daemon still has the session,
          // so a refresh() would bring it back.
          state = state.copyWith(
            acpSessions: state.acpSessions
                .where((s) => s.sessionId != sessionId)
                .toList(),
          );
        }
      }
    });

    _wsService.connectionState.listen((connected) {
      if (connected) {
        refresh();
      }
    });
  }

  void refresh() {
    state = state.copyWith(isLoading: true);
    final t = DateTime.now();
    _wsService.requestSessions();
    _wsService.acpListSessions();
    // timing start stored for response handlers
    _refreshStartedAt = t;
  }

  DateTime? _refreshStartedAt;

  Future<void> createSession(String name) async {
    state = state.copyWith(isLoading: true);
    _wsService.createSession(name);
    await Future.delayed(const Duration(milliseconds: 500));
    refresh();
  }

  Future<void> killSession(String name) async {
    _wsService.killSession(name);
    await Future.delayed(const Duration(milliseconds: 500));
    refresh();
  }

  Future<void> deleteAcpSession(String sessionId) async {
    _wsService.selectBackend('acp');
    _wsService.deleteAcpSession(sessionId);
    await Future.delayed(const Duration(milliseconds: 500));
    refresh();
  }

  void attachSession(String name) {
    _wsService.attachSession(name);
  }
}

final sessionsProvider = StateNotifierProvider<SessionsNotifier, SessionsState>(
  (ref) {
    final wsService = ref.watch(sharedWebSocketServiceProvider);
    return SessionsNotifier(wsService);
  },
);
