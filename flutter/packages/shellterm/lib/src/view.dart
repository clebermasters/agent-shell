/// [TerminalView] — the main Flutter widget for rendering a [Terminal].
///
/// Matches the exact API contract used by the app:
///   TerminalView(terminal, {scrollController, controller, readOnly,
///     cursorType, alwaysShowCursor, textStyle, onSecondaryTapDown, padding})
library;

import 'dart:math' show max;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'controller.dart';
import 'renderer.dart';
import 'terminal.dart';
import 'theme.dart';

class TerminalView extends StatefulWidget {
  final Terminal terminal;
  final ScrollController? scrollController;
  final TerminalController? controller;
  final bool readOnly;
  final TerminalCursorType cursorType;
  final bool alwaysShowCursor;
  final TerminalStyle? textStyle;
  final TerminalTheme theme;
  final bool autoResize;
  final void Function(TapDownDetails, Offset)? onSecondaryTapDown;
  final EdgeInsets padding;
  final FocusNode? focusNode;

  const TerminalView(
    this.terminal, {
    super.key,
    this.scrollController,
    this.controller,
    this.readOnly = false,
    this.cursorType = TerminalCursorType.block,
    this.alwaysShowCursor = true,
    this.textStyle,
    this.theme = TerminalThemes.defaultTheme,
    this.autoResize = true,
    this.onSecondaryTapDown,
    this.padding = EdgeInsets.zero,
    this.focusNode,
  });

  @override
  State<TerminalView> createState() => _TerminalViewState();
}

class _TerminalViewState extends State<TerminalView> {
  late TerminalController _controller;
  bool _ownsController = false;
  late ScrollController _scrollController;
  bool _ownsScrollController = false;
  late FocusNode _focusNode;
  bool _ownsFocusNode = false;

  RenderTerminal? _renderTerminal;

  @override
  void initState() {
    super.initState();
    _initController();
    _initScrollController();
    _initFocusNode();
    widget.terminal.addListener(_onTerminalChanged);
  }

  void _initController() {
    if (widget.controller != null) {
      _controller = widget.controller!;
      _ownsController = false;
    } else {
      _controller = TerminalController();
      _ownsController = true;
    }
  }

  void _initScrollController() {
    if (widget.scrollController != null) {
      _scrollController = widget.scrollController!;
      _ownsScrollController = false;
    } else {
      _scrollController = ScrollController();
      _ownsScrollController = true;
    }
  }

  void _initFocusNode() {
    if (widget.focusNode != null) {
      _focusNode = widget.focusNode!;
      _ownsFocusNode = false;
    } else {
      _focusNode = FocusNode();
      _ownsFocusNode = true;
    }
  }

  @override
  void didUpdateWidget(TerminalView oldWidget) {
    super.didUpdateWidget(oldWidget);

    if (widget.terminal != oldWidget.terminal) {
      oldWidget.terminal.removeListener(_onTerminalChanged);
      widget.terminal.addListener(_onTerminalChanged);
    }

    if (widget.controller != oldWidget.controller) {
      if (_ownsController) _controller.dispose();
      _initController();
    }

    if (widget.scrollController != oldWidget.scrollController) {
      if (_ownsScrollController) _scrollController.dispose();
      _initScrollController();
    }
  }

  @override
  void dispose() {
    widget.terminal.removeListener(_onTerminalChanged);
    if (_ownsController) _controller.dispose();
    if (_ownsScrollController) _scrollController.dispose();
    if (_ownsFocusNode) _focusNode.dispose();
    super.dispose();
  }

  void _onTerminalChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return Focus(
      focusNode: _focusNode,
      autofocus: !widget.readOnly,
      onKeyEvent: widget.readOnly ? null : _onKeyEvent,
      child: GestureDetector(
        onTapDown: (_) => _focusNode.requestFocus(),
        onSecondaryTapDown: widget.onSecondaryTapDown != null
            ? (details) {
                widget.onSecondaryTapDown!(details, details.localPosition);
              }
            : null,
        child: LayoutBuilder(
          builder: (context, constraints) {
            _handleResize(constraints);
            return _TerminalRenderWidget(
              terminal: widget.terminal,
              style: widget.textStyle ?? const TerminalStyle(),
              theme: widget.theme,
              controller: _controller,
              cursorType: widget.cursorType,
              alwaysShowCursor: widget.alwaysShowCursor,
              padding: widget.padding,
            );
          },
        ),
      ),
    );
  }

  void _handleResize(BoxConstraints constraints) {
    if (!widget.autoResize) return;

    // We need cell dimensions from the renderer.
    final cellWidth = _renderTerminal?.cellWidth ?? 0;
    final cellHeight = _renderTerminal?.cellHeight ?? 0;

    if (cellWidth <= 0 || cellHeight <= 0) {
      // Schedule resize after first paint when metrics are available
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _handleResize(constraints);
      });
      return;
    }

    final availableWidth =
        constraints.maxWidth - widget.padding.horizontal;
    final availableHeight =
        constraints.maxHeight - widget.padding.vertical;

    final cols = max(1, (availableWidth / cellWidth).floor());
    final rows = max(1, (availableHeight / cellHeight).floor());

    if (cols != widget.terminal.viewWidth ||
        rows != widget.terminal.viewHeight) {
      widget.terminal.resize(cols, rows,
          constraints.maxWidth, constraints.maxHeight);
    }
  }

  KeyEventResult _onKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;
    final ctrl = HardwareKeyboard.instance.isControlPressed;

    // Generate the appropriate escape sequence
    String? sequence;

    if (ctrl && key == LogicalKeyboardKey.keyC) {
      sequence = '\x03'; // ETX
    } else if (ctrl && key == LogicalKeyboardKey.keyD) {
      sequence = '\x04'; // EOT
    } else if (ctrl && key == LogicalKeyboardKey.keyZ) {
      sequence = '\x1A'; // SUB
    } else if (ctrl && key == LogicalKeyboardKey.keyL) {
      sequence = '\x0C'; // FF (clear screen)
    } else if (key == LogicalKeyboardKey.enter) {
      sequence = '\r';
    } else if (key == LogicalKeyboardKey.backspace) {
      sequence = '\x7F';
    } else if (key == LogicalKeyboardKey.tab) {
      sequence = '\t';
    } else if (key == LogicalKeyboardKey.escape) {
      sequence = '\x1B';
    } else if (key == LogicalKeyboardKey.arrowUp) {
      sequence = widget.terminal.appCursorKeys ? '\x1bOA' : '\x1b[A';
    } else if (key == LogicalKeyboardKey.arrowDown) {
      sequence = widget.terminal.appCursorKeys ? '\x1bOB' : '\x1b[B';
    } else if (key == LogicalKeyboardKey.arrowRight) {
      sequence = widget.terminal.appCursorKeys ? '\x1bOC' : '\x1b[C';
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      sequence = widget.terminal.appCursorKeys ? '\x1bOD' : '\x1b[D';
    } else if (key == LogicalKeyboardKey.home) {
      sequence = '\x1b[H';
    } else if (key == LogicalKeyboardKey.end) {
      sequence = '\x1b[F';
    } else if (key == LogicalKeyboardKey.pageUp) {
      sequence = '\x1b[5~';
    } else if (key == LogicalKeyboardKey.pageDown) {
      sequence = '\x1b[6~';
    } else if (key == LogicalKeyboardKey.insert) {
      sequence = '\x1b[2~';
    } else if (key == LogicalKeyboardKey.delete) {
      sequence = '\x1b[3~';
    } else if (key == LogicalKeyboardKey.f1) {
      sequence = '\x1bOP';
    } else if (key == LogicalKeyboardKey.f2) {
      sequence = '\x1bOQ';
    } else if (key == LogicalKeyboardKey.f3) {
      sequence = '\x1bOR';
    } else if (key == LogicalKeyboardKey.f4) {
      sequence = '\x1bOS';
    } else if (key == LogicalKeyboardKey.f5) {
      sequence = '\x1b[15~';
    } else if (key == LogicalKeyboardKey.f6) {
      sequence = '\x1b[17~';
    } else if (key == LogicalKeyboardKey.f7) {
      sequence = '\x1b[18~';
    } else if (key == LogicalKeyboardKey.f8) {
      sequence = '\x1b[19~';
    } else if (key == LogicalKeyboardKey.f9) {
      sequence = '\x1b[20~';
    } else if (key == LogicalKeyboardKey.f10) {
      sequence = '\x1b[21~';
    } else if (key == LogicalKeyboardKey.f11) {
      sequence = '\x1b[23~';
    } else if (key == LogicalKeyboardKey.f12) {
      sequence = '\x1b[24~';
    } else if (event.character != null && event.character!.isNotEmpty) {
      // Regular character input
      var char = event.character!;
      if (ctrl && char.length == 1) {
        final code = char.codeUnitAt(0);
        if (code >= 0x61 && code <= 0x7A) {
          // Ctrl+a-z → 0x01-0x1A
          sequence = String.fromCharCode(code - 0x60);
        }
      }
      sequence ??= char;
    }

    if (sequence != null) {
      widget.terminal.onOutput?.call(sequence);
      return KeyEventResult.handled;
    }

    return KeyEventResult.ignored;
  }
}

// ── Render object widget bridge ─────────────────────────────────────────────

class _TerminalRenderWidget extends LeafRenderObjectWidget {
  final Terminal terminal;
  final TerminalStyle style;
  final TerminalTheme theme;
  final TerminalController controller;
  final TerminalCursorType cursorType;
  final bool alwaysShowCursor;
  final EdgeInsets padding;

  const _TerminalRenderWidget({
    required this.terminal,
    required this.style,
    required this.theme,
    required this.controller,
    required this.cursorType,
    required this.alwaysShowCursor,
    required this.padding,
  });

  @override
  RenderTerminal createRenderObject(BuildContext context) {
    final render = RenderTerminal(
      terminal: terminal,
      style: style,
      theme: theme,
      controller: controller,
      cursorType: cursorType,
      alwaysShowCursor: alwaysShowCursor,
      padding: padding,
    );
    // Store reference for resize calculations
    final state = context.findAncestorStateOfType<_TerminalViewState>();
    state?._renderTerminal = render;
    return render;
  }

  @override
  void updateRenderObject(BuildContext context, RenderTerminal renderObject) {
    renderObject
      ..terminal = terminal
      ..style = style
      ..theme = theme
      ..controller = controller
      ..cursorType = cursorType
      ..alwaysShowCursor = alwaysShowCursor
      ..padding = padding;
  }
}
