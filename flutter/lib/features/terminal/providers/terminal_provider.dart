import 'dart:async';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:xterm/xterm.dart';
import 'package:flutter_background_service/flutter_background_service.dart'
    if (dart.library.html) '../../../core/utils/background_service_stub.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../../data/services/websocket_service.dart';
import '../../../data/services/terminal_service.dart';
import '../../../data/services/audio_service.dart';
import '../../../data/services/whisper_service.dart';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../sessions/providers/sessions_provider.dart';

class TerminalState {
  final bool isConnected;
  final bool isLoading;
  final bool isHydrating;
  final String? error;
  final Terminal? terminal;
  final TerminalController? controller;
  final bool isRecording;
  final Duration recordingDuration;
  final bool isTranscribing;

  const TerminalState({
    this.isConnected = false,
    this.isLoading = false,
    this.isHydrating = false,
    this.error,
    this.terminal,
    this.controller,
    this.isRecording = false,
    this.recordingDuration = Duration.zero,
    this.isTranscribing = false,
  });

  TerminalState copyWith({
    bool? isConnected,
    bool? isLoading,
    bool? isHydrating,
    String? error,
    Terminal? terminal,
    TerminalController? controller,
    bool? isRecording,
    Duration? recordingDuration,
    bool? isTranscribing,
  }) {
    return TerminalState(
      isConnected: isConnected ?? this.isConnected,
      isLoading: isLoading ?? this.isLoading,
      isHydrating: isHydrating ?? this.isHydrating,
      error: error,
      terminal: terminal ?? this.terminal,
      controller: controller ?? this.controller,
      isRecording: isRecording ?? this.isRecording,
      recordingDuration: recordingDuration ?? this.recordingDuration,
      isTranscribing: isTranscribing ?? this.isTranscribing,
    );
  }
}

class TerminalNotifier extends StateNotifier<TerminalState> {
  final TerminalService terminalService;
  final WebSocketService _wsService;
  final Map<String, TerminalController> _controllers = {};
  final AudioService _audioService = AudioService();
  final WhisperService _whisperService = WhisperService();
  Timer? _recordingTimer;
  SharedPreferences? _prefs;
  String? _activeSessionName;

  TerminalNotifier(this.terminalService, this._wsService)
    : super(TerminalState(isConnected: _wsService.isConnected)) {
    _init();
  }

  void setPrefs(SharedPreferences prefs) {
    _prefs = prefs;
  }

  void _init() {
    _wsService.connectionState.listen((connected) {
      if (connected && _activeSessionName != null && !state.isConnected) {
        // We just reconnected, so we need to re-attach to the terminal session
        // to resume receiving terminal data.  Use actual terminal dimensions
        // if available to avoid size mismatch with tmux.
        final terminal = state.terminal;
        final cols = (terminal?.viewWidth ?? 0) > 0 ? terminal!.viewWidth : 80;
        final rows = (terminal?.viewHeight ?? 0) > 0 ? terminal!.viewHeight : 24;
        _wsService.attachSession(_activeSessionName!, cols: cols, rows: rows);
      }
      state = state.copyWith(isConnected: connected);
    });

    _wsService.messages.listen((message) {
      final type = message['type'] as String?;
      final msgSession = message['sessionName'] as String?
          ?? message['session'] as String?;
      if (msgSession != null && msgSession != _activeSessionName) return;
      if (type == 'terminal-history-start') {
        state = state.copyWith(isHydrating: true);
      } else if (type == 'terminal-history-end') {
        state = state.copyWith(isHydrating: false);
      }
    });
  }

  void connect(String sessionName, {int windowIndex = 0}) async {
    _activeSessionName = sessionName;
    state = state.copyWith(isLoading: true, error: null);

    final terminal = terminalService.createTerminal(sessionName);

    // Create or get existing controller for this session.
    // Evict the oldest entry when the map exceeds 20 entries to prevent leaks.
    final controllerKey = '${sessionName}_$windowIndex';
    if (!_controllers.containsKey(controllerKey)) {
      if (_controllers.length >= 20) {
        final oldest = _controllers.keys.first;
        _controllers[oldest]?.dispose();
        _controllers.remove(oldest);
      }
      _controllers[controllerKey] = TerminalController();
    }

    // Send attach-session message
    _wsService.attachSession(
      sessionName,
      cols: 80,
      rows: 24,
      windowIndex: windowIndex,
    );

    state = state.copyWith(
      isLoading: false,
      isConnected: _wsService.isConnected,
      terminal: terminal,
      controller: _controllers[controllerKey],
    );

    // Start background service to keep socket alive (Android/iOS only)
    if (!kIsWeb) {
      final service = FlutterBackgroundService();
      var isRunning = await service.isRunning();
      if (!isRunning) {
        service.startService();
      }
    }

    // Force a resize after the initial attach settles.  The backend's
    // attach_to_session uses max(tmux_window_size, requested) which can
    // create a PTY larger than our terminal.  During the attach, multiple
    // resize events fire as xterm's layout stabilizes, but tmux may still
    // hold a stale size.  Sending the definitive resize after everything
    // settles ensures tmux has the exact dimensions of our terminal.
    Future.delayed(const Duration(seconds: 2), () {
      if (_activeSessionName == sessionName) {
        final cols = terminal.viewWidth;
        final rows = terminal.viewHeight;
        if (cols > 0 && rows > 0) {
          terminalService.resizeTerminal(sessionName, cols, rows);
        }
      }
    });
  }

  void checkConnection() {
    if (!_wsService.isConnected) {
      _wsService.forceReconnect();
    } else {
      // Send a ping to verify connection is still alive.
      // If the socket is actually dead, this will trigger an error in the channel
      // and force a reconnection cycle.
      _wsService.send({'type': 'ping'});
    }
  }

  void disconnect() {
    _activeSessionName = null;
    if (!kIsWeb) {
      FlutterBackgroundService().invoke('stopService');
    }
  }

  void sendData(String sessionName, String data) {
    _wsService.sendTerminalData(sessionName, data);
  }

  void resize(String sessionName, int cols, int rows) {
    terminalService.resizeTerminal(sessionName, cols, rows);
  }

  Future<bool> checkMicrophonePermission() async {
    return await _audioService.hasPermission();
  }

  Future<void> startVoiceRecording() async {
    final hasPermission = await _audioService.hasPermission();
    if (!hasPermission) {
      state = state.copyWith(error: 'Microphone permission denied');
      return;
    }

    final path = await _audioService.startRecording();
    if (path != null) {
      state = state.copyWith(
        isRecording: true,
        recordingDuration: Duration.zero,
        error: null,
      );
      _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        state = state.copyWith(
          recordingDuration: _audioService.recordingDuration,
        );
      });
    }
  }

  Future<String?> stopVoiceRecording() async {
    _recordingTimer?.cancel();
    _recordingTimer = null;

    final path = await _audioService.stopRecording();
    if (path != null) {
      state = state.copyWith(isRecording: false);
      return path;
    }
    state = state.copyWith(isRecording: false);
    return null;
  }

  Future<String?> transcribeAudio(String audioPath) async {
    if (_prefs == null) {
      state = state.copyWith(
        error:
            'API key not configured. Please add your OpenAI API key in Settings.',
        isTranscribing: false,
      );
      return null;
    }

    final apiKey = _prefs!.getString(AppConfig.keyOpenAiApiKey);
    if (apiKey == null || apiKey.isEmpty) {
      state = state.copyWith(
        error:
            'API key not configured. Please add your OpenAI API key in Settings.',
        isTranscribing: false,
      );
      return null;
    }

    state = state.copyWith(isTranscribing: true, error: null);

    final text = await _whisperService.transcribe(audioPath, apiKey);

    state = state.copyWith(isTranscribing: false);
    return text;
  }

  void injectText(String text) {
    final sessionName = _activeSessionName;
    if (sessionName == null || text.isEmpty) {
      return;
    }

    // Send transcription as real terminal input so tmux receives it.
    final shouldAutoEnter =
        _prefs?.getBool(AppConfig.keyVoiceAutoEnter) ?? false;
    final payload = shouldAutoEnter && !text.endsWith('\n') ? '$text\n' : text;
    _wsService.sendTerminalData(sessionName, payload);
  }

  @override
  void dispose() {
    _recordingTimer?.cancel();
    _audioService.dispose();
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    _controllers.clear();
    super.dispose();
  }
}

final terminalServiceProvider = Provider<TerminalService>((ref) {
  final wsService = ref.watch(sharedWebSocketServiceProvider);
  return TerminalService(wsService);
});

final terminalProvider = StateNotifierProvider<TerminalNotifier, TerminalState>(
  (ref) {
    final terminalService = ref.watch(terminalServiceProvider);
    final wsService = ref.watch(sharedWebSocketServiceProvider);
    final notifier = TerminalNotifier(terminalService, wsService);

    // Set SharedPreferences
    final prefs = ref.read(sharedPreferencesProvider);
    notifier.setPrefs(prefs);

    return notifier;
  },
);
