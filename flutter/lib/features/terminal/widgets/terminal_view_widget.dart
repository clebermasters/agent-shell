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

  late TextEditingController _inputController;

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
    _inputController = TextEditingController();
    VolumeKeyBoard.instance.addListener(_handleVolumeKey);
  }

  @override
  void dispose() {
    _inputController.dispose();
    VolumeKeyBoard.instance.removeListener();
    super.dispose();
  }

  void _handleTextFieldInput(String value) {
    if (value.isEmpty) return;

    // We only process characters that were ADDED
    // This is more robust against keyboards that send full strings
    for (int i = 0; i < value.length; i++) {
      _processInputChar(value[i]);
    }

    // Always keep it empty to catch the next character
    _inputController.value = TextEditingValue.empty;
  }

  void _processInputChar(String char) {
    String finalData = char;
    bool wasModified = false;

    // Apply soft modifiers from our accessory bar
    if (widget.ctrlActive || widget.altActive || widget.shiftActive) {
      wasModified = true;

      // 1. Apply Shift (only if it's a character that can be shifted)
      if (widget.shiftActive) {
        if (_shiftMap.containsKey(char)) {
          finalData = _shiftMap[char]!;
        } else {
          finalData = char.toUpperCase();
        }
      }

      // 2. Apply Ctrl
      if (widget.ctrlActive) {
        int code = finalData.toUpperCase().codeUnitAt(0);
        if (code >= 64 && code <= 95) {
          finalData = String.fromCharCode(code - 64);
        } else if (finalData == ' ') {
          finalData = '\x00';
        }
        // If not a standard letter, finalData remains as is (or shifted)
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

  // RawKeyboardListener handles special keys like Backspace, Enter, Tab
  // which might not trigger onChanged in TextField on some Android keyboards
  void _onKey(RawKeyEvent event) {
    if (event is! RawKeyDownEvent) return;

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

    // Handle hardware combinations (Zoom)
    if (_localHardwareCtrlPressed) {
      if (key == LogicalKeyboardKey.equal || key == LogicalKeyboardKey.add) {
        _zoomIn();
        return;
      } else if (key == LogicalKeyboardKey.minus) {
        _zoomOut();
        return;
      }
    }

    // Handle special keys that TextField might miss
    if (key == LogicalKeyboardKey.backspace) {
      sequence = '\x7f';
    } else if (key == LogicalKeyboardKey.enter) {
      sequence = '\r';
    } else if (key == LogicalKeyboardKey.tab) {
      sequence = '\t';
    } else if (key == LogicalKeyboardKey.escape) {
      sequence = '\x1b';
    } else if (key == LogicalKeyboardKey.arrowUp) {
      sequence = '\x1b[A';
    } else if (key == LogicalKeyboardKey.arrowDown) {
      sequence = '\x1b[B';
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      sequence = '\x1b[D';
    } else if (key == LogicalKeyboardKey.arrowRight) {
      sequence = '\x1b[C';
    }

    if (sequence != null) {
      // Apply soft modifiers even to these special keys if applicable
      // (Though usually you don't Ctrl+Backspace, but Ctrl+Tab is a thing)
      String finalData = sequence;
      bool wasModified = false;

      if (widget.altActive) {
        finalData = '\x1b$finalData';
        wasModified = true;
      }
      
      // Ctrl+Special is rarer but handled by terminal sequences usually
      // For now, just send the sequence

      widget.onInput(finalData);
      if (wasModified) widget.onModifiersReset();
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
    return RawKeyboardListener(
      focusNode: FocusNode(), // Local node for raw events
      onKey: _onKey,
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
                  // Hidden TextField to capture native keyboard input
                  Positioned(
                    left: -100,
                    top: 0,
                    child: SizedBox(
                      width: 10,
                      height: 10,
                      child: TextField(
                        controller: _inputController,
                        focusNode: widget.focusNode,
                        autofocus: true,
                        keyboardType: TextInputType.multiline,
                        textInputAction: TextInputAction.none,
                        maxLines: null,
                        autocorrect: false,
                        enableSuggestions: false,
                        onChanged: _handleTextFieldInput,
                        onSubmitted: (val) {
                          _processInputChar('\r');
                        },
                      ),
                    ),
                  ),

                  // The terminal view
                  IgnorePointer(
                    child: TerminalView(
                      widget.terminal,
                      readOnly: true,
                      textStyle: TerminalStyle(
                        fontSize: _fontSize,
                        fontFamily: 'JetBrains Mono',
                      ),
                      padding: EdgeInsets.zero,
                    ),
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
