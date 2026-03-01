import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';

class TerminalViewWidget extends StatefulWidget {
  final Terminal terminal;
  final Function(int cols, int rows) onResize;
  final Function(String data) onInput;
  final FocusNode focusNode;

  const TerminalViewWidget({
    super.key,
    required this.terminal,
    required this.onResize,
    required this.onInput,
    required this.focusNode,
  });

  @override
  State<TerminalViewWidget> createState() => _TerminalViewWidgetState();
}

class _TerminalViewWidgetState extends State<TerminalViewWidget> {
  final TransformationController _transformController =
      TransformationController();
  double _scale = 1.0;
  bool _ctrlPressed = false;

  @override
  void initState() {
    super.initState();
    widget.terminal.onOutput = widget.onInput;
  }

  @override
  void dispose() {
    _transformController.dispose();
    super.dispose();
  }

  void _handleKeyEvent(KeyEvent event) {
    if (event is KeyDownEvent || event is KeyRepeatEvent) {
      final key = event.logicalKey;

      String? sequence;

      if (_ctrlPressed) {
        if (key == LogicalKeyboardKey.equal || key == LogicalKeyboardKey.add) {
          _zoomIn();
          return;
        } else if (key == LogicalKeyboardKey.minus) {
          _zoomOut();
          return;
        }
      }

      if (key == LogicalKeyboardKey.f1)
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
      else if (key == LogicalKeyboardKey.arrowUp)
        sequence = '\x1b[A';
      else if (key == LogicalKeyboardKey.arrowDown)
        sequence = '\x1b[B';
      else if (key == LogicalKeyboardKey.arrowRight)
        sequence = '\x1b[C';
      else if (key == LogicalKeyboardKey.arrowLeft)
        sequence = '\x1b[D';
      else if (key == LogicalKeyboardKey.enter)
        sequence = '\r';
      else if (key == LogicalKeyboardKey.backspace)
        sequence = '\x7f';
      else if (key == LogicalKeyboardKey.tab)
        sequence = '\t';
      else if (key == LogicalKeyboardKey.escape)
        sequence = '\x1b';
      else if (key == LogicalKeyboardKey.home)
        sequence = '\x1b[H';
      else if (key == LogicalKeyboardKey.end)
        sequence = '\x1b[F';
      else if (key == LogicalKeyboardKey.pageUp)
        sequence = '\x1b[5~';
      else if (key == LogicalKeyboardKey.pageDown)
        sequence = '\x1b[6~';
      else if (key == LogicalKeyboardKey.insert)
        sequence = '\x1b[2~';
      else if (key == LogicalKeyboardKey.delete)
        sequence = '\x1b[3~';
      else if (HardwareKeyboard.instance.isControlPressed) {
        if (key == LogicalKeyboardKey.keyC)
          sequence = '\x03';
        else if (key == LogicalKeyboardKey.keyD)
          sequence = '\x04';
        else if (key == LogicalKeyboardKey.keyZ)
          sequence = '\x1a';
        else if (key == LogicalKeyboardKey.keyL)
          sequence = '\x0c';
      } else if (key == LogicalKeyboardKey.altLeft ||
          key == LogicalKeyboardKey.altRight) {
        setState(() {
          _ctrlPressed = !_ctrlPressed;
        });
        return;
      }

      if (sequence != null) {
        widget.onInput(sequence);
      }
    } else if (event is KeyUpEvent) {
      final key = event.logicalKey;
      if (key == LogicalKeyboardKey.altLeft ||
          key == LogicalKeyboardKey.altRight) {
        setState(() {
          _ctrlPressed = false;
        });
      }
    }
  }

  void _zoomIn() {
    setState(() {
      _scale = (_scale * 1.2).clamp(0.5, 3.0);
    });
  }

  void _zoomOut() {
    setState(() {
      _scale = (_scale / 1.2).clamp(0.5, 3.0);
    });
  }

  @override
  Widget build(BuildContext context) {
    return KeyboardListener(
      focusNode: FocusNode(),
      onKeyEvent: _handleKeyEvent,
      child: GestureDetector(
        onDoubleTap: _zoomIn,
        onLongPress: _zoomOut,
        child: InteractiveViewer(
          transformationController: _transformController,
          minScale: 0.5,
          maxScale: 3.0,
          child: TerminalView(
            widget.terminal,
            textStyle: TerminalStyle(
              fontSize: 14 * _scale,
              fontFamily: 'JetBrains Mono',
            ),
            padding: EdgeInsets.zero,
          ),
        ),
      ),
    );
  }
}
