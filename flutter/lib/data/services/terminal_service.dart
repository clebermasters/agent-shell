import 'dart:async';
import 'dart:convert';
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
  final Map<String, StreamSubscription> _subscriptions = {};
  final StreamController<String> _outputController =
      StreamController<String>.broadcast();

  // Custom input processor to handle modifiers globally
  Function(String session, String data)? _inputProcessor;

  // Per-session hydration state for history bootstrap AND resize guard
  final Map<String, bool> _hydrating = {};
  final Map<String, List<String>> _hydrationQueue = {};

  // Per-session resize guard timers: after sending a resize to the backend,
  // buffer incoming data until tmux processes SIGWINCH and redraws.
  final Map<String, Timer> _resizeGuardTimers = {};

  TerminalService(this._wsService);

  Stream<String> get outputStream => _outputController.stream;

  void setInputProcessor(Function(String session, String data) processor) {
    _inputProcessor = processor;
  }

  Terminal createTerminal(String sessionName, {int cols = 80, int rows = 24}) {
    // Cancel any previous listener for this session to prevent duplicates.
    _subscriptions[sessionName]?.cancel();

    // 50,000 lines so streamed tmux history is not trimmed.
    // Disable client-side reflow: tmux handles reflow server-side and redraws
    // the screen after resize.  Local reflow conflicts with the tmux redraw
    // and produces mixed/overlapping text.
    final terminal = Terminal(maxLines: 50000, reflowEnabled: false);

    _terminals[sessionName] = terminal;
    _hydrating[sessionName] = false;
    _hydrationQueue[sessionName] = [];

    // Set up terminal callbacks.
    // Filter out terminal query responses (Device Attributes, cursor position
    // reports, etc.) that xterm generates internally.  These escape sequences
    // must NOT be forwarded to the backend because the backend echoes them as
    // visible text (the ">0;0;0c" artefacts).
    terminal.onOutput = (data) {
      final filtered = _filterTerminalResponses(data);
      if (filtered.isEmpty) return;
      if (_inputProcessor != null) {
        _inputProcessor!(sessionName, filtered);
      } else {
        _wsService.sendTerminalData(sessionName, filtered);
      }
    };

    // Listen for incoming data from WebSocket.
    // Store the subscription so we can cancel it on re-create or close.
    _subscriptions[sessionName] = _wsService.messages.listen((message) {
      final type = message['type'] as String?;
      final msgSession = message['sessionName'] as String?
          ?? message['session'] as String?;

      // ── History bootstrap protocol ──────────────────────────────────────
      if (type == 'terminal-history-start') {
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
      // Filter by session: only write data intended for this terminal.
      if (type == 'output' || type == 'terminal_data') {
        if (msgSession != null && msgSession != sessionName) return;
        final data = message['data'] as String?;
        if (data != null) {
          if (_hydrating[sessionName] == true) {
            _hydrationQueue[sessionName]?.add(data);
          } else {
            terminal.write(data);
          }
        }
      }
    });

    return terminal;
  }

  /// Strip terminal query responses (DA1, DA2, DSR, etc.) so they are
  /// never forwarded to the backend.  Patterns:
  ///   ESC [ ? ... c     (DA1 response)
  ///   ESC [ > ... c     (DA2 response)
  ///   ESC [ ... R       (Cursor Position Report)
  ///   ESC [ ... n       (Device Status Report)
  static final _termResponseRe = RegExp(
    r'\x1b\[\??[>]?[\d;]*[cRn]',
  );
  static String _filterTerminalResponses(String data) {
    return data.replaceAll(_termResponseRe, '');
  }

  void resizeTerminal(String sessionName, int cols, int rows) {
    // Notify the backend — the Terminal object is already resized
    // by xterm's autoResize or the caller.
    _wsService.resizeTerminal(sessionName, cols, rows);

    // Buffer incoming data for 300ms after a resize to avoid rendering content
    // formatted for the old terminal dimensions.  When the viewport shrinks
    // (e.g. keyboard appears), tmux keeps sending output for the old (larger)
    // size for ~50-200ms.  Cursor positions that exceed the new viewport height
    // get clamped to the last row, stacking multiple lines on top of each
    // other.  Buffering gives tmux time to receive SIGWINCH and send a full
    // screen redraw for the new dimensions; that redraw is always the last item
    // in the queue and provides the correct content when flushed.
    _resizeGuardTimers[sessionName]?.cancel();

    // Reuse the existing hydration queue mechanism.
    _hydrating[sessionName] = true;
    _hydrationQueue[sessionName] = [];

    _resizeGuardTimers[sessionName] = Timer(
      const Duration(milliseconds: 300),
      () {
        final terminal = _terminals[sessionName];
        if (terminal == null) return;

        _hydrating[sessionName] = false;
        final queue = _hydrationQueue[sessionName] ?? [];
        // All writes happen synchronously; the UI paints only the final state.
        for (final data in queue) {
          terminal.write(data);
        }
        _hydrationQueue[sessionName] = [];
        _resizeGuardTimers.remove(sessionName);
      },
    );
  }

  void writeToTerminal(String sessionName, String data) {
    final terminal = _terminals[sessionName];
    terminal?.write(data);
  }

  void closeTerminal(String sessionName) {
    _resizeGuardTimers[sessionName]?.cancel();
    _resizeGuardTimers.remove(sessionName);
    _subscriptions[sessionName]?.cancel();
    _subscriptions.remove(sessionName);
    _terminals.remove(sessionName);
    _hydrating.remove(sessionName);
    _hydrationQueue.remove(sessionName);
    _ptys[sessionName]?.kill();
    _ptys.remove(sessionName);
  }

  void dispose() {
    for (final timer in _resizeGuardTimers.values) {
      timer.cancel();
    }
    _resizeGuardTimers.clear();
    for (final sub in _subscriptions.values) {
      sub.cancel();
    }
    _subscriptions.clear();
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
