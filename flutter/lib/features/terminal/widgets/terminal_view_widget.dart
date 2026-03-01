import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';
import 'package:volume_key_board/volume_key_board.dart';

class TerminalViewWidget extends StatefulWidget {
  final Terminal terminal;
  final Function(int cols, int rows) onResize;
  final Function(String data) onInput;
  final FocusNode focusNode;
  final bool ctrlActive;
  final bool altActive;
  final bool shiftActive;
  final VoidCallback onModifiersReset;

  const TerminalViewWidget({
    super.key,
    required this.terminal,
    required this.onResize,
    required this.onInput,
    required this.focusNode,
    this.ctrlActive = false,
    this.altActive = false,
    this.shiftActive = false,
    required this.onModifiersReset,
  });

  @override
  State<TerminalViewWidget> createState() => _TerminalViewWidgetState();
}

class _TerminalViewWidgetState extends State<TerminalViewWidget> {
  double _fontSize = 14.0;
  bool _localHardwareCtrlPressed = false;
  bool _localHardwareAltPressed = false;
  bool _localHardwareShiftPressed = false;

  int _lastCols = 0;
  int _lastRows = 0;
  bool _initialized = false;

  final Map<String, String> _shiftMap = {
    '1': '!', '2': '@', '3': '#', '4': '\$', '5': '%',
    '6': '^', '7': '&', '8': '*', '9': '(', '0': ')',
    '-': '_', '=': '+', '[': '{', ']': '}', '\\': '|',
    ';': ':', '\'': '"', ',': '<', '.': '>', '/': '?',
    '`': '~',
  };

  @override
  void initState() {
    super.initState();
    // Intercept terminal input via Terminal.onOutput
    // This catches everything sent to the terminal (native keyboard, etc.)
    widget.terminal.onOutput = _handleTerminalOutput;
    VolumeKeyBoard.instance.addListener(_handleVolumeKey);
  }

  @override
  void didUpdateWidget(TerminalViewWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    widget.terminal.onOutput = _handleTerminalOutput;
  }

  @override
  void dispose() {
    VolumeKeyBoard.instance.removeListener();
    super.dispose();
  }

  // This is the CRITICAL part: it intercepts input from the TerminalView's internal text input
  void _handleTerminalOutput(String data) {
    String finalData = data;
    bool wasModified = false;

    // Apply soft modifiers from our accessory bar
    if (data.length == 1 && (widget.ctrlActive || widget.altActive || widget.shiftActive)) {
      String char = data;
      wasModified = true;

      // 1. Apply Shift
      if (widget.shiftActive) {
        if (_shiftMap.containsKey(char)) {
          char = _shiftMap[char]!;
        } else {
          char = char.toUpperCase();
        }
      }

      // 2. Apply Ctrl
      if (widget.ctrlActive) {
        int code = char.toUpperCase().codeUnitAt(0);
        if (code >= 64 && code <= 95) {
          finalData = String.fromCharCode(code - 64);
        } else if (char == ' ') {
          finalData = '\x00';
        } else {
          finalData = char;
        }
      } else {
        finalData = char;
      }

      // 3. Apply Alt (Meta)
      if (widget.altActive) {
        finalData = '\x1b$finalData';
      }
    }

    // Send to backend
    widget.onInput(finalData);

    // Reset soft modifiers if used
    if (wasModified) {
      widget.onModifiersReset();
    }
  }

  void _handleVolumeKey(VolumeKey event) {
    if (event == VolumeKey.up) {
      _zoomIn();
    } else if (event == VolumeKey.down) {
      _zoomOut();
    }
  }

  // Handle hardware keys (and some native keyboard events)
  void _onKeyEvent(KeyEvent event) {
    if (event is KeyDownEvent || event is KeyRepeatEvent) {
      final key = event.logicalKey;
      String? sequence;

      // Track hardware modifier states
      if (key == LogicalKeyboardKey.controlLeft || key == LogicalKeyboardKey.controlRight) {
        _localHardwareCtrlPressed = true;
      } else if (key == LogicalKeyboardKey.altLeft || key == LogicalKeyboardKey.altRight) {
        _localHardwareAltPressed = true;
      } else if (key == LogicalKeyboardKey.shiftLeft || key == LogicalKeyboardKey.shiftRight) {
        _localHardwareShiftPressed = true;
      }

      // Hardware combinations (Zoom)
      if (_localHardwareCtrlPressed) {
        if (key == LogicalKeyboardKey.equal || key == LogicalKeyboardKey.add) {
          _zoomIn();
          return;
        } else if (key == LogicalKeyboardKey.minus) {
          _zoomOut();
          return;
        }
      }

      // Hardware control keys that don't produce characters
      if (key == LogicalKeyboardKey.f1) sequence = '\x1bOP';
      else if (key == LogicalKeyboardKey.f2) sequence = '\x1bOQ';
      else if (key == LogicalKeyboardKey.f3) sequence = '\x1bOR';
      else if (key == LogicalKeyboardKey.f4) sequence = '\x1bOS';
      else if (key == LogicalKeyboardKey.f5) sequence = '\x1b[15~';
      else if (key == LogicalKeyboardKey.arrowUp) sequence = '\x1b[A';
      else if (key == LogicalKeyboardKey.arrowDown) sequence = '\x1b[B';
      else if (key == LogicalKeyboardKey.arrowRight) sequence = '\x1b[C';
      else if (key == LogicalKeyboardKey.arrowLeft) sequence = '\x1b[D';
      else if (key == LogicalKeyboardKey.enter) sequence = '\r';
      else if (key == LogicalKeyboardKey.backspace) sequence = '\x7f';
      else if (key == LogicalKeyboardKey.tab) sequence = '\t';
      else if (key == LogicalKeyboardKey.escape) sequence = '\x1b';
      else if (key == LogicalKeyboardKey.home) sequence = '\x1b[H';
      else if (key == LogicalKeyboardKey.end) sequence = '\x1b[F';
      else if (key == LogicalKeyboardKey.pageUp) sequence = '\x1b[5~';
      else if (key == LogicalKeyboardKey.pageDown) sequence = '\x1b[6~';
      else if (key == LogicalKeyboardKey.delete) sequence = '\x1b[3~';

      // If we identified a hardware special key sequence, send it and reset soft modifiers
      if (sequence != null) {
        widget.onInput(sequence);
        if (widget.ctrlActive || widget.altActive || widget.shiftActive) {
          widget.onModifiersReset();
        }
      }
    } else if (event is KeyUpEvent) {
      final key = event.logicalKey;
      if (key == LogicalKeyboardKey.controlLeft || key == LogicalKeyboardKey.controlRight) {
        _localHardwareCtrlPressed = false;
      } else if (key == LogicalKeyboardKey.altLeft || key == LogicalKeyboardKey.altRight) {
        _localHardwareAltPressed = false;
      } else if (key == LogicalKeyboardKey.shiftLeft || key == LogicalKeyboardKey.shiftRight) {
        _localHardwareShiftPressed = false;
      }
    }
  }

  void _zoomIn() {
    setState(() {
      _fontSize = (_fontSize * 1.2).clamp(8.0, 32.0);
    });
    _sendResize();
  }

  void _zoomOut() {
    setState(() {
      _fontSize = (_fontSize / 1.2).clamp(8.0, 32.0);
    });
    _sendResize();
  }

  void _sendResize() {
    if (_lastCols > 0 && _lastRows > 0) {
      widget.terminal.resize(_lastCols, _lastRows);
      widget.onResize(_lastCols, _lastRows);
    }
  }

  void _updateTerminalSize(Size size) {
    final charWidth = _fontSize * 0.6;
    final charHeight = _fontSize * 1.2;

    final cols = (size.width / charWidth).floor().clamp(10, 200);
    final rows = (size.height / charHeight).floor().clamp(5, 100);

    if (cols != _lastCols || rows != _lastRows) {
      _lastCols = cols;
      _lastRows = rows;
      widget.terminal.resize(cols, rows);
      widget.onResize(cols, rows);
    }
  }

  @override
  Widget build(BuildContext context) {
    return KeyboardListener(
      focusNode: FocusNode(),
      onKeyEvent: _onKeyEvent,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final size = Size(constraints.maxWidth, constraints.maxHeight);

          if (!_initialized) {
            _initialized = true;
            WidgetsBinding.instance.addPostFrameCallback((_) {
              _updateTerminalSize(size);
            });
          }

          return GestureDetector(
            onTap: () {
              widget.focusNode.requestFocus();
            },
            onDoubleTap: _zoomIn,
            onLongPress: _zoomOut,
            child: Container(
              color: Colors.black,
              width: constraints.maxWidth,
              height: constraints.maxHeight,
              child: Stack(
                children: [
                  TerminalView(
                    widget.terminal,
                    focusNode: widget.focusNode,
                    textStyle: TerminalStyle(
                      fontSize: _fontSize,
                      fontFamily: 'JetBrains Mono',
                    ),
                    padding: EdgeInsets.zero,
                  ),
                  
                  // Visual indicator for active soft modifiers
                  if (widget.ctrlActive || widget.altActive || widget.shiftActive)
                    Positioned(
                      top: 8,
                      right: 8,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Colors.red.withOpacity(0.8),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '${widget.ctrlActive ? "CTRL " : ""}${widget.altActive ? "ALT " : ""}${widget.shiftActive ? "SHIFT" : ""}',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
