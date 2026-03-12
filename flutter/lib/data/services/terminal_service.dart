import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:xterm/xterm.dart';
import 'websocket_service.dart';

// flutter_pty uses dart:ffi which is not available on web
// ignore: uri_does_not_exist
import 'package:flutter_pty/flutter_pty.dart'
    if (dart.library.html) 'pty_stub.dart';

class TerminalService {
  final WebSocketService _wsService;
  final Map<String, Terminal> _terminals = {};
  final Map<String, Pty> _ptys = {};
  final StreamController<String> _outputController =
      StreamController<String>.broadcast();
  
  // Custom input processor to handle modifiers globally
  Function(String session, String data)? _inputProcessor;

  // Per-session hydration state for history bootstrap
  final Map<String, bool> _hydrating = {};
  final Map<String, List<String>> _hydrationQueue = {};

  TerminalService(this._wsService);

  Stream<String> get outputStream => _outputController.stream;

  void setInputProcessor(Function(String session, String data) processor) {
    _inputProcessor = processor;
  }

  Terminal createTerminal(String sessionName, {int cols = 80, int rows = 24}) {
    // 50,000 lines so streamed tmux history is not trimmed
    final terminal = Terminal(maxLines: 50000);

    _terminals[sessionName] = terminal;
    _hydrating[sessionName] = false;
    _hydrationQueue[sessionName] = [];

    // Set up terminal callbacks
    terminal.onOutput = (data) {
      if (_inputProcessor != null) {
        _inputProcessor!(sessionName, data);
      } else {
        _wsService.sendTerminalData(sessionName, data);
      }
    };

    // Listen for incoming data from WebSocket
    _wsService.messages.listen((message) {
      final type = message['type'] as String?;
      final msgSession = message['sessionName'] as String?;

      // ── History bootstrap protocol ──────────────────────────────────────
      if (type == 'terminal-history-start') {
        // Only handle if it's for this terminal's session
        if (msgSession == null || msgSession == sessionName) {
          _hydrating[sessionName] = true;
          _hydrationQueue[sessionName] = [];
        }
        return;
      }

      if (type == 'terminal-history-chunk') {
        if (msgSession == null || msgSession == sessionName) {
          final data = message['data'] as String?;
          if (data != null) {
            // Suppress onOutput during history replay to prevent
            // escape-sequence responses from looping back to the backend.
            final savedOutput = terminal.onOutput;
            terminal.onOutput = null;
            terminal.write(data);
            terminal.onOutput = savedOutput;
          }
        }
        return;
      }

      if (type == 'terminal-history-end') {
        if (msgSession == null || msgSession == sessionName) {
          _hydrating[sessionName] = false;
          // Flush any live output that arrived during history streaming
          final queue = _hydrationQueue[sessionName] ?? [];
          for (final data in queue) {
            terminal.write(data);
          }
          _hydrationQueue[sessionName] = [];
        }
        return;
      }

      // ── Live output ──────────────────────────────────────────────────────
      if (type == 'output') {
        final data = message['data'] as String?;
        if (data != null) {
          if (_hydrating[sessionName] == true) {
            // Queue until history streaming is complete
            _hydrationQueue[sessionName]?.add(data);
          } else {
            terminal.write(data);
          }
        }
      }
      
      if (type == 'terminal_data') {
        final tSession = message['session'] as String? ?? message['sessionName'] as String?;
        if (tSession == sessionName) {
          final data = message['data'] as String?;
          if (data != null) {
            terminal.write(data);
          }
        }
      }
    });

    return terminal;
  }

  void resizeTerminal(String sessionName, int cols, int rows) {
    final terminal = _terminals[sessionName];
    if (terminal != null) {
      terminal.resize(cols, rows);
      _wsService.resizeTerminal(sessionName, cols, rows);
    }
  }

  void writeToTerminal(String sessionName, String data) {
    final terminal = _terminals[sessionName];
    terminal?.write(data);
  }

  void closeTerminal(String sessionName) {
    _terminals.remove(sessionName);
    _hydrating.remove(sessionName);
    _hydrationQueue.remove(sessionName);
    _ptys[sessionName]?.kill();
    _ptys.remove(sessionName);
  }

  void dispose() {
    for (final pty in _ptys.values) {
      pty.kill();
    }
    _terminals.clear();
    _ptys.clear();
    _hydrating.clear();
    _hydrationQueue.clear();
    _outputController.close();
  }
}

class NativeTerminalService {
  final Map<String, Pty> _ptys = {};
  final Map<String, Terminal> _terminals = {};

  Pty createPty(String sessionName, {int cols = 80, int rows = 24}) {
    final pty = Pty.start('/bin/bash', columns: cols, rows: rows);

    _ptys[sessionName] = pty;

    final terminal = Terminal(maxLines: 10000);

    _terminals[sessionName] = terminal;

    pty.output.listen((data) {
      terminal.write(utf8.decode(data));
    });

    terminal.onOutput = (data) {
      pty.write(utf8.encode(data));
    };

    return pty;
  }

  void resize(String sessionName, int cols, int rows) {
    _ptys[sessionName]?.resize(cols, rows);
  }

  void kill(String sessionName) {
    _ptys[sessionName]?.kill();
    _ptys.remove(sessionName);
    _terminals.remove(sessionName);
  }

  void dispose() {
    for (final pty in _ptys.values) {
      pty.kill();
    }
    _ptys.clear();
    _terminals.clear();
  }
}
