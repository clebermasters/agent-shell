import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:ui';
import 'package:shellterm/shellterm.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../../core/config/app_config.dart';
import 'package:volume_key_board/volume_key_board.dart'
    if (dart.library.html) '../../../core/utils/volume_key_board_stub.dart';

class TerminalViewWidget extends StatefulWidget {
  final Terminal terminal;
  final TerminalController? controller;
  final Function(int cols, int rows) onResize;
  final Function(String data) onInput;
  final FocusNode focusNode;
  final bool ctrlActive;
  final bool altActive;
  final bool shiftActive;
  final bool isSelectionMode;
  final VoidCallback onModifiersReset;
  final VoidCallback? onTap;
  final SharedPreferences prefs;

  const TerminalViewWidget({
    super.key,
    required this.terminal,
    this.controller,
    required this.onResize,
    required this.onInput,
    required this.focusNode,
    this.ctrlActive = false,
    this.altActive = false,
    this.shiftActive = false,
    this.isSelectionMode = false,
    required this.onModifiersReset,
    this.onTap,
    required this.prefs,
  });

  @override
  State<TerminalViewWidget> createState() => _TerminalViewWidgetState();
}

class _TerminalViewWidgetState extends State<TerminalViewWidget>
    with WidgetsBindingObserver {
  late double _fontSize;
  int _lastCols = 0;
  int _lastRows = 0;
  double _lastWidth = 0;
  double _lastHeight = 0;
  Timer? _resizeDebounce;
  Timer? _zoomResizeDebounce;
  Timer? _zoomInfoTimer;
  bool _showZoomInfo = false;
  int _lastEnterMs = 0; // dedup Enter from onKey + onChanged

  late FocusNode _wrapperFocusNode;
  late TextEditingController _inputController;

  final Map<String, String> _shiftMap = {
    '1': '!',
    '2': '@',
    '3': '#',
    '4': '\$',
    '5': '%',
    '6': '^',
    '7': '&',
    '8': '*',
    '9': '(',
    '0': ')',
    '-': '_',
    '=': '+',
    '[': '{',
    ']': '}',
    '\\': '|',
    ';': ':',
    '\'': '"',
    ',': '<',
    '.': '>',
    '/': '?',
    '`': '~',
  };

  final ScrollController _scrollController = ScrollController();
  Offset? _pointerDownPos;
  bool _isDragging = false;
  double _dragStartPixels = 0;

  /// Current font size, readable by parent widgets (e.g. selection overlay).
  double get fontSize => _fontSize;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _wrapperFocusNode = FocusNode(debugLabel: 'TerminalWrapper');
    _inputController = TextEditingController();
    if (!kIsWeb) {
      VolumeKeyBoard.instance.addListener(_handleVolumeKey);
    }
    // Use shellterm's own resize callback (based on actual font metrics)
    // to notify the backend of the correct terminal dimensions.
    widget.terminal.onResize = (cols, rows, pixelWidth, pixelHeight) {
      if (cols != _lastCols || rows != _lastRows) {
        _lastCols = cols;
        _lastRows = rows;
        // During zoom the debounce timer handles sending; only send here
        // when the resize was NOT triggered by a zoom (e.g. keyboard show/hide,
        // orientation change).
        if (_zoomResizeDebounce == null || !_zoomResizeDebounce!.isActive) {
          widget.onResize(cols, rows);
        }
      }
    };
    _fontSize =
        widget.prefs.getDouble(AppConfig.keyTerminalFontSize) ??
        AppConfig.terminalFontSize;
  }

  @override
  void dispose() {
    _resizeDebounce?.cancel();
    _zoomResizeDebounce?.cancel();
    _zoomInfoTimer?.cancel();
    _scrollController.dispose();
    WidgetsBinding.instance.removeObserver(this);
    if (!kIsWeb) {
      VolumeKeyBoard.instance.removeListener();
    }
    _wrapperFocusNode.dispose();
    _inputController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Force text input connection to rebuild when returning to app
      if (widget.focusNode.hasFocus) {
        widget.focusNode.unfocus();
      }
      Future.delayed(const Duration(milliseconds: 100), () {
        if (mounted) {
          widget.focusNode.requestFocus();
          SystemChannels.textInput.invokeMethod('TextInput.show');
        }
      });
    } else if (state == AppLifecycleState.paused) {
      widget.focusNode.unfocus();
    }
  }

  @override
  void didChangeMetrics() {
    if (!mounted) return;
    // Debounce to avoid rapid-fire resizes during browser zoom animation.
    // Resetting _lastCols/_lastRows ensures shellterm's onResize callback always
    // propagates to the backend even if the computed integer cols/rows are the
    // same as before (can happen due to rounding at certain zoom levels).
    _resizeDebounce?.cancel();
    _resizeDebounce = Timer(const Duration(milliseconds: 150), () {
      if (!mounted) return;
      _lastCols = 0;
      _lastRows = 0;
      setState(() {});
    });
  }

  void _handleVolumeKey(VolumeKey event) {
    if (event == VolumeKey.up) {
      _zoomIn();
    } else if (event == VolumeKey.down) {
      _zoomOut();
    }
  }

  void _handleTextFieldInput(String value) {
    if (value.isEmpty) return;

    for (int i = 0; i < value.length; i++) {
      String char = value[i];
      if (char == '\n') {
        // Deduplicate: _onKey may have already sent '\r' for Enter.
        final now = DateTime.now().millisecondsSinceEpoch;
        if (now - _lastEnterMs < 100) continue;
        _lastEnterMs = now;
        _processInputChar('\r');
      } else {
        _processInputChar(char);
      }
    }

    _inputController.value = TextEditingValue.empty;
  }

  void _processInputChar(String char) {
    String finalData = char;
    bool wasModified = false;

    if (widget.ctrlActive || widget.altActive || widget.shiftActive) {
      wasModified = true;

      if (widget.shiftActive) {
        if (_shiftMap.containsKey(char)) {
          finalData = _shiftMap[char]!;
        } else {
          finalData = char.toUpperCase();
        }
      }

      if (widget.ctrlActive) {
        int code = finalData.toUpperCase().codeUnitAt(0);
        if (code >= 64 && code <= 95) {
          finalData = String.fromCharCode(code - 64);
        } else if (finalData == ' ') {
          finalData = '\x00';
        }
      }

      if (widget.altActive) {
        finalData = '\x1b$finalData';
      }
    }

    widget.onInput(finalData);

    if (wasModified) {
      widget.onModifiersReset();
    }
  }

  /// Returns true if the event was handled (consumed) so the Focus widget can
  /// report KeyEventResult.handled and prevent Flutter's focus traversal from
  /// stealing focus away from the terminal on Tab / Shift+Tab / arrow keys.
  bool _onKey(RawKeyEvent event) {
    if (event is! RawKeyDownEvent) return false;

    if (widget.isSelectionMode) return false;

    final key = event.logicalKey;
    String? sequence;
    String finalData = '';
    bool wasModified = false;

    // Handle CTRL + letter combinations (e.g., CTRL+C = \x03)
    if (event.isControlPressed) {
      // Get the key label
      final keyLabel = key.keyLabel;
      if (keyLabel.isNotEmpty && keyLabel.length == 1) {
        final char = keyLabel.toUpperCase();
        final code = char.codeUnitAt(0);
        // A-Z = 65-90, convert to Ctrl+A = 1, Ctrl+B = 2, etc.
        if (code >= 65 && code <= 90) {
          finalData = String.fromCharCode(code - 64);
          widget.onInput(finalData);
          return true;
        }
        // CTRL+Space = \x00
        if (key == LogicalKeyboardKey.space) {
          widget.onInput('\x00');
          return true;
        }
        // CTRL+\ = \x1c (FS)
        // CTRL+] = \x1d (GS)
        // CTRL+^ = \x1e (RS)
        // CTRL+_ = \x1f (US)
        if (key == LogicalKeyboardKey.backslash) {
          widget.onInput('\x1c');
          return true;
        }
        if (key == LogicalKeyboardKey.bracketRight) {
          if (event.isShiftPressed) {
            widget.onInput('\x1e'); // CTRL+^
          } else {
            widget.onInput('\x1d'); // CTRL+]
          }
          return true;
        }
        if (key == LogicalKeyboardKey.minus && event.isShiftPressed) {
          widget.onInput('\x1f'); // CTRL+_
          return true;
        }
        // For other CTRL+key combinations, try to extract the character
        if (code >= 97 && code <= 122) {
          // a-z
          finalData = String.fromCharCode(code - 96);
          widget.onInput(finalData);
          return true;
        }
      }
      // Handle other special keys with CTRL
      if (key == LogicalKeyboardKey.backspace) {
        widget.onInput('\x7f'); // CTRL+?
        return true;
      }
      if (key == LogicalKeyboardKey.escape) {
        widget.onInput('\x1b'); // CTRL+[
        return true;
      }
      if (key == LogicalKeyboardKey.enter) {
        widget.onInput('\x0d'); // CTRL+M
        return true;
      }
      if (key == LogicalKeyboardKey.tab) {
        widget.onInput('\x09'); // CTRL+I
        return true;
      }
      if (key == LogicalKeyboardKey.delete) {
        widget.onInput('\x1b[3~'); // CTRL+Delete - send as escape sequence
        return true;
      }
    }

    // Handle ALT + key combinations
    if (event.isAltPressed) {
      // First, get the base sequence
      if (key == LogicalKeyboardKey.backspace)
        sequence = '\x7f';
      else if (key == LogicalKeyboardKey.tab)
        sequence = '\t';
      else if (key == LogicalKeyboardKey.escape)
        sequence = '\x1b';
      else if (key == LogicalKeyboardKey.arrowUp)
        sequence = '\x1b[A';
      else if (key == LogicalKeyboardKey.arrowDown)
        sequence = '\x1b[B';
      else if (key == LogicalKeyboardKey.arrowLeft)
        sequence = '\x1b[D';
      else if (key == LogicalKeyboardKey.arrowRight)
        sequence = '\x1b[C';
      else if (key == LogicalKeyboardKey.home)
        sequence = '\x1b[H';
      else if (key == LogicalKeyboardKey.end)
        sequence = '\x1b[F';
      else if (key == LogicalKeyboardKey.pageUp)
        sequence = '\x1b[5~';
      else if (key == LogicalKeyboardKey.pageDown)
        sequence = '\x1b[6~';
      else if (key == LogicalKeyboardKey.delete)
        sequence = '\x1b[3~';
      else {
        // ALT + printable character
        final keyLabel = key.keyLabel;
        if (keyLabel.isNotEmpty && keyLabel.length == 1) {
          finalData = '\x1b${keyLabel}';
          widget.onInput(finalData);
          return true;
        }
      }

      if (sequence != null) {
        finalData = '\x1b$sequence';
        widget.onInput(finalData);
        return true;
      }
    }

    // Handle special keys without modifiers
    if (key == LogicalKeyboardKey.backspace)
      sequence = '\x7f';
    else if (key == LogicalKeyboardKey.tab)
      sequence = '\t';
    else if (key == LogicalKeyboardKey.escape)
      sequence = '\x1b';
    else if (key == LogicalKeyboardKey.arrowUp)
      sequence = '\x1b[A';
    else if (key == LogicalKeyboardKey.arrowDown)
      sequence = '\x1b[B';
    else if (key == LogicalKeyboardKey.arrowLeft)
      sequence = '\x1b[D';
    else if (key == LogicalKeyboardKey.arrowRight)
      sequence = '\x1b[C';
    else if (key == LogicalKeyboardKey.home)
      sequence = '\x1b[H';
    else if (key == LogicalKeyboardKey.end)
      sequence = '\x1b[F';
    else if (key == LogicalKeyboardKey.pageUp)
      sequence = '\x1b[5~';
    else if (key == LogicalKeyboardKey.pageDown)
      sequence = '\x1b[6~';
    else if (key == LogicalKeyboardKey.delete)
      sequence = '\x1b[3~';
    else if (key == LogicalKeyboardKey.enter) {
      // Deduplicate: on Android the soft keyboard fires both a RawKeyEvent
      // AND an onChanged('\n').  Only send the first within 100ms.
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastEnterMs < 100) return false;
      _lastEnterMs = now;
      sequence = '\r';
    }
    else if (key == LogicalKeyboardKey.f1)
      sequence = '\x1bOP';
    else if (key == LogicalKeyboardKey.f2)
      sequence = '\x1bOQ';
    else if (key == LogicalKeyboardKey.f3)
      sequence = '\x1bOR';
    else if (key == LogicalKeyboardKey.f4)
      sequence = '\x1bOS';
    else if (key == LogicalKeyboardKey.f5)
      sequence = '\x1b[15~';
    else if (key == LogicalKeyboardKey.f6)
      sequence = '\x1b[17~';
    else if (key == LogicalKeyboardKey.f7)
      sequence = '\x1b[18~';
    else if (key == LogicalKeyboardKey.f8)
      sequence = '\x1b[19~';
    else if (key == LogicalKeyboardKey.f9)
      sequence = '\x1b[20~';
    else if (key == LogicalKeyboardKey.f10)
      sequence = '\x1b[21~';
    else if (key == LogicalKeyboardKey.f11)
      sequence = '\x1b[23~';
    else if (key == LogicalKeyboardKey.f12)
      sequence = '\x1b[24~';
    else if (key == LogicalKeyboardKey.insert)
      sequence = '\x1b[2~';

    // Handle SHIFT + special keys
    if (event.isShiftPressed && sequence != null) {
      if (key == LogicalKeyboardKey.tab) sequence = '\x1b[Z'; // Shift+Tab
      wasModified = true;
    }

    // Also handle soft ALT modifier from accessory bar
    if (widget.altActive && sequence != null) {
      sequence = '\x1b$sequence';
      wasModified = true;
    }

    if (sequence != null) {
      widget.onInput(sequence);
      if (wasModified) widget.onModifiersReset();
      return true;
    }

    return false;
  }

  void _zoomIn() {
    _lastCols = 0;
    _lastRows = 0;
    setState(() {
      _fontSize = (_fontSize * 1.2).clamp(8.0, 32.0);
      _showZoomInfo = true;
    });
    widget.prefs.setDouble(AppConfig.keyTerminalFontSize, _fontSize);
    _scheduleZoomResize();
    _scheduleZoomInfoHide();
  }

  void _zoomOut() {
    _lastCols = 0;
    _lastRows = 0;
    setState(() {
      _fontSize = (_fontSize / 1.2).clamp(8.0, 32.0);
      _showZoomInfo = true;
    });
    widget.prefs.setDouble(AppConfig.keyTerminalFontSize, _fontSize);
    _scheduleZoomResize();
    _scheduleZoomInfoHide();
  }

  void _scheduleZoomInfoHide() {
    _zoomInfoTimer?.cancel();
    _zoomInfoTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) setState(() => _showZoomInfo = false);
    });
  }

  /// Debounce zoom resize: wait until the user stops pressing volume keys
  /// (500ms of inactivity) before sending ONE resize to the backend.
  void _scheduleZoomResize() {
    _zoomResizeDebounce?.cancel();
    _zoomResizeDebounce = Timer(const Duration(milliseconds: 500), () {
      if (!mounted) return;
      final cols = widget.terminal.viewWidth;
      final rows = widget.terminal.viewHeight;
      if (cols > 0 && rows > 0) {
        _lastCols = cols;
        _lastRows = rows;
        widget.onResize(cols, rows);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        // When the available space changes (browser zoom, window resize, keyboard),
        // reset the dimension guard so shellterm's onResize always reaches the backend.
        final w = constraints.maxWidth;
        final h = constraints.maxHeight;
        if (w != _lastWidth || h != _lastHeight) {
          _lastWidth = w;
          _lastHeight = h;
          _lastCols = 0;
          _lastRows = 0;
        }

        return Focus(
          focusNode: _wrapperFocusNode,
          onKey: (node, event) {
            final handled = _onKey(event);
            return handled ? KeyEventResult.handled : KeyEventResult.ignored;
          },
          child: SizedBox(
            width: w,
            height: h,
            child: Stack(
              fit: StackFit.expand,
              children: [
                // Layer 1: GHOST INPUT — off-screen, captures keyboard only
                Positioned(
                  left: -100,
                  top: 0,
                  width: 1,
                  height: 1,
                  child: Opacity(
                    opacity: 0,
                    child: TextField(
                      controller: _inputController,
                      focusNode: widget.focusNode,
                      autofocus: true,
                      keyboardType: TextInputType.multiline,
                      textInputAction: TextInputAction.newline,
                      maxLines: null,
                      autocorrect: false,
                      enableSuggestions: false,
                      onChanged: _handleTextFieldInput,
                    ),
                  ),
                ),

                // Layer 2: TERMINAL VIEW
                Positioned.fill(
                  child: Listener(
                    onPointerDown: (e) {
                      _pointerDownPos = e.position;
                      _isDragging = false;
                      _dragStartPixels = 0.0;
                    },
                    onPointerMove: (e) {
                      if (_pointerDownPos == null) return;

                      final dy = e.position.dy - _pointerDownPos!.dy;

                      if (!_isDragging && dy.abs() > 18) {
                        _isDragging = true;
                        _dragStartPixels = e.position.dy;
                      }

                      if (_isDragging) {
                        final dragDelta = e.position.dy - _dragStartPixels;

                        if (dragDelta.abs() > 20) {
                          _dragStartPixels = e.position.dy;

                          final button = dragDelta > 0 ? 64 : 65;
                          const x = 1;
                          const y = 1;

                          final sequence = '\x1b[<$button;$x;${y}M';
                          widget.terminal.onOutput?.call(sequence);
                        }
                      }
                    },
                    onPointerUp: (e) {
                      if (_pointerDownPos != null && !_isDragging) {
                        final distance =
                            (e.position - _pointerDownPos!).distance;
                        if (distance < 10) {
                          if (widget.onTap != null) widget.onTap!();
                        }
                      }
                      _pointerDownPos = null;
                      _isDragging = false;
                    },
                    behavior: HitTestBehavior.translucent,
                    child: ScrollConfiguration(
                      behavior: ScrollConfiguration.of(context).copyWith(
                        physics: const ClampingScrollPhysics(),
                        dragDevices: {
                          PointerDeviceKind.touch,
                          PointerDeviceKind.mouse,
                          PointerDeviceKind.trackpad,
                        },
                      ),
                      child: TerminalView(
                        widget.terminal,
                        scrollController: _scrollController,
                        controller: widget.controller,
                        readOnly: true,
                        cursorType: TerminalCursorType.block,
                        alwaysShowCursor: true,
                        textStyle: TerminalStyle(
                          fontSize: _fontSize,
                          fontFamily: 'JetBrains Mono',
                        ),
                        onSecondaryTapDown: (details, offset) {},
                        padding: EdgeInsets.zero,
                      ),
                    ),
                  ),
                ),

                // Layer 3: Visual Indicators (Overlay)
                Positioned.fill(
                  child: IgnorePointer(
                    child: Stack(
                      children: [
                        if (widget.ctrlActive ||
                            widget.altActive ||
                            widget.shiftActive)
                          Positioned(
                            top: 8,
                            right: 8,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 4,
                              ),
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
                        // Zoom info overlay
                        if (_showZoomInfo)
                          Positioned(
                            bottom: 12,
                            left: 0,
                            right: 0,
                            child: Center(
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 14,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.black.withOpacity(0.85),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: Colors.white24,
                                    width: 0.5,
                                  ),
                                ),
                                child: Text(
                                  '${_fontSize.toStringAsFixed(1)}pt  '
                                  '${widget.terminal.viewWidth}x${widget.terminal.viewHeight}  '
                                  '${w.toInt()}x${h.toInt()}px',
                                  style: const TextStyle(
                                    color: Colors.white70,
                                    fontSize: 11,
                                    fontFamily: 'JetBrains Mono',
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
