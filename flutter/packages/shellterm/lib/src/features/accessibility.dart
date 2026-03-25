import 'package:flutter/semantics.dart';
import 'package:flutter/widgets.dart';
import 'package:shellterm/src/core/buffer/buffer.dart';
import 'package:shellterm/src/terminal.dart';

/// Wraps a terminal widget with accessibility semantics.
///
/// Provides screen reader support by:
/// - Exposing visible terminal content as semantic text
/// - Announcing new output via [SemanticsService]
/// - Labeling cursor position and selection state
///
/// Usage:
/// ```dart
/// TerminalAccessibility(
///   terminal: terminal,
///   child: TerminalView(terminal),
/// )
/// ```
class TerminalAccessibility extends StatefulWidget {
  const TerminalAccessibility({
    super.key,
    required this.terminal,
    required this.child,
    this.announceNewOutput = true,
    this.maxAnnounceLength = 200,
  });

  /// The terminal to expose semantics for.
  final Terminal terminal;

  /// The child widget (typically a [TerminalView]).
  final Widget child;

  /// Whether to announce new terminal output via [SemanticsService].
  final bool announceNewOutput;

  /// Maximum characters to announce at once, to avoid overwhelming the reader.
  final int maxAnnounceLength;

  @override
  State<TerminalAccessibility> createState() => _TerminalAccessibilityState();
}

class _TerminalAccessibilityState extends State<TerminalAccessibility> {
  int _lastLineCount = 0;

  @override
  void initState() {
    super.initState();
    widget.terminal.addListener(_onTerminalUpdate);
  }

  @override
  void didUpdateWidget(TerminalAccessibility oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.terminal != widget.terminal) {
      oldWidget.terminal.removeListener(_onTerminalUpdate);
      widget.terminal.addListener(_onTerminalUpdate);
    }
  }

  @override
  void dispose() {
    widget.terminal.removeListener(_onTerminalUpdate);
    super.dispose();
  }

  void _onTerminalUpdate() {
    if (!widget.announceNewOutput) return;

    final buffer = widget.terminal.buffer;
    final currentLineCount = buffer.lines.length;

    // Only announce if new lines were added (output, not just cursor movement).
    if (currentLineCount > _lastLineCount) {
      final newLines = _getNewContent(buffer, _lastLineCount, currentLineCount);
      if (newLines.isNotEmpty) {
        _announce(newLines);
      }
    }

    _lastLineCount = currentLineCount;
  }

  String _getNewContent(Buffer buffer, int fromLine, int toLine) {
    final sb = StringBuffer();
    final lines = buffer.lines;

    for (var i = fromLine; i < toLine && i < lines.length; i++) {
      final text = lines[i].getText().trimRight();
      if (text.isNotEmpty) {
        if (sb.isNotEmpty) sb.write('\n');
        sb.write(text);
      }
    }

    final result = sb.toString();
    if (result.length > widget.maxAnnounceLength) {
      return result.substring(result.length - widget.maxAnnounceLength);
    }
    return result;
  }

  void _announce(String text) {
    // ignore: deprecated_member_use
    SemanticsService.announce(text, TextDirection.ltr);
  }

  /// Build a semantic description of the current terminal state.
  String _buildSemanticLabel() {
    final terminal = widget.terminal;
    final buffer = terminal.buffer;
    final parts = <String>[];

    parts.add('Terminal');
    parts.add('${terminal.viewWidth} columns by ${terminal.viewHeight} rows');
    parts.add(
        'Cursor at column ${buffer.cursorX + 1}, row ${buffer.cursorY + 1}');

    if (buffer.scrollBack > 0) {
      parts.add('${buffer.scrollBack} lines in scrollback');
    }

    return parts.join('. ');
  }

  /// Get the visible text content for semantic reading.
  String _getVisibleText() {
    final buffer = widget.terminal.buffer;
    final lines = buffer.lines;
    final sb = StringBuffer();
    final scrollBack = buffer.scrollBack;

    for (var i = scrollBack;
        i < lines.length && i < scrollBack + buffer.viewHeight;
        i++) {
      if (sb.isNotEmpty) sb.write('\n');
      sb.write(lines[i].getText().trimRight());
    }

    return sb.toString();
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: _buildSemanticLabel(),
      value: _getVisibleText(),
      textField: true,
      multiline: true,
      readOnly: true,
      child: widget.child,
    );
  }
}
