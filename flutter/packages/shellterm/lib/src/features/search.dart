import 'package:flutter/material.dart';
import 'package:shellterm/src/core/buffer/buffer.dart';
import 'package:shellterm/src/core/buffer/cell_offset.dart';
import 'package:shellterm/src/core/buffer/line.dart';
import 'package:shellterm/src/core/buffer/range_line.dart';
import 'package:shellterm/src/terminal.dart';
import 'package:shellterm/src/ui/controller.dart';

/// A single search match in the terminal buffer.
class TerminalSearchMatch {
  const TerminalSearchMatch({
    required this.range,
    required this.lineIndex,
  });

  /// The cell range of this match.
  final BufferRangeLine range;

  /// The buffer line index where this match was found.
  final int lineIndex;
}

/// Options for terminal search.
class TerminalSearchOptions {
  const TerminalSearchOptions({
    this.caseSensitive = false,
    this.regex = false,
    this.wholeWord = false,
  });

  /// Whether the search is case-sensitive.
  final bool caseSensitive;

  /// Whether the query is a regular expression.
  final bool regex;

  /// Whether to match whole words only.
  final bool wholeWord;
}

/// Controller for searching terminal scrollback buffer.
///
/// Usage:
/// ```dart
/// final search = TerminalSearchController(terminal: terminal, controller: controller);
/// search.find('error');
/// search.findNext();     // advance to next match
/// search.findPrevious();  // go back
/// search.clear();         // remove highlights
/// ```
class TerminalSearchController {
  TerminalSearchController({
    required this.terminal,
    required this.controller,
    Color? matchColor,
    Color? activeMatchColor,
  })  : _matchColor = matchColor ?? Colors.yellow.withValues(alpha: 0.3),
        _activeMatchColor =
            activeMatchColor ?? Colors.orange.withValues(alpha: 0.5);

  /// The terminal to search.
  final Terminal terminal;

  /// The controller to apply highlights to.
  final TerminalController controller;

  final Color _matchColor;
  final Color _activeMatchColor;

  final _highlights = <TerminalHighlight>[];
  final _matches = <TerminalSearchMatch>[];

  /// Index of the currently active match, or -1 if none.
  int _activeIndex = -1;

  /// The active highlight (distinguished by color).
  TerminalHighlight? _activeHighlight;

  /// The current search query, if any.
  String? _query;

  // Note: _options stored for potential future use (e.g. re-search on resize).

  /// All matches found by the last search.
  List<TerminalSearchMatch> get matches => List.unmodifiable(_matches);

  /// Number of matches.
  int get matchCount => _matches.length;

  /// Index of the currently active match (0-based), or -1.
  int get activeIndex => _activeIndex;

  /// Whether there is an active search with results.
  bool get hasMatches => _matches.isNotEmpty;

  /// The current query string.
  String? get query => _query;

  /// Dispose all resources. Must be called when this controller is no longer
  /// needed to prevent highlight and anchor leaks.
  void dispose() {
    clear();
  }

  /// Search the terminal buffer for [query].
  ///
  /// Returns the number of matches found.
  int find(
    String query, {
    TerminalSearchOptions options = const TerminalSearchOptions(),
  }) {
    clear();

    if (query.isEmpty) return 0;

    _query = query;

    final pattern = _buildPattern(query, options);
    if (pattern == null) return 0;

    final buffer = terminal.buffer;
    _scanBuffer(buffer, pattern);

    // Highlight all matches.
    for (final match in _matches) {
      final hl = controller.highlight(
        p1: buffer.createAnchorFromOffset(match.range.begin),
        p2: buffer.createAnchorFromOffset(match.range.end),
        color: _matchColor,
      );
      _highlights.add(hl);
    }

    // Activate the first match closest to the viewport bottom.
    if (_matches.isNotEmpty) {
      final viewBottom = buffer.lines.length - 1;
      _activeIndex = _findNearestMatch(viewBottom);
      _updateActiveHighlight();
    }

    return _matches.length;
  }

  /// Advance to the next match. Wraps around.
  void findNext() {
    if (_matches.isEmpty) return;
    _activeIndex = (_activeIndex + 1) % _matches.length;
    _updateActiveHighlight();
  }

  /// Go to the previous match. Wraps around.
  void findPrevious() {
    if (_matches.isEmpty) return;
    _activeIndex = (_activeIndex - 1 + _matches.length) % _matches.length;
    _updateActiveHighlight();
  }

  /// Clear all search state and highlights.
  void clear() {
    _activeHighlight?.dispose();
    _activeHighlight = null;
    for (final hl in _highlights) {
      hl.dispose();
    }
    _highlights.clear();
    _matches.clear();
    _activeIndex = -1;
    _query = null;
  }

  /// Get the cell offset of the active match, useful for scrolling to it.
  CellOffset? get activeMatchOffset {
    if (_activeIndex < 0 || _activeIndex >= _matches.length) return null;
    return _matches[_activeIndex].range.begin;
  }

  /// Build a RegExp from the query and options. Returns null if the regex
  /// is invalid.
  RegExp? _buildPattern(String query, TerminalSearchOptions options) {
    String pattern;
    if (options.regex) {
      pattern = query;
    } else {
      pattern = RegExp.escape(query);
    }

    if (options.wholeWord) {
      pattern = '\\b$pattern\\b';
    }

    try {
      return RegExp(pattern, caseSensitive: options.caseSensitive);
    } catch (_) {
      return null;
    }
  }

  /// Scan the entire buffer for matches.
  void _scanBuffer(Buffer buffer, RegExp pattern) {
    final lines = buffer.lines;

    for (var i = 0; i < lines.length; i++) {
      final line = lines[i];
      _scanLine(line, i, pattern);
    }
  }

  /// Scan a single line for matches.
  void _scanLine(BufferLine line, int lineIndex, RegExp pattern) {
    final text = line.getText();
    if (text.isEmpty) return;

    for (final match in pattern.allMatches(text)) {
      final startCol = _textOffsetToCol(line, match.start);
      final endCol = _textOffsetToCol(line, match.end);

      if (startCol == null || endCol == null) continue;

      _matches.add(TerminalSearchMatch(
        range: BufferRangeLine(
          CellOffset(startCol, lineIndex),
          CellOffset(endCol, lineIndex),
        ),
        lineIndex: lineIndex,
      ));
    }
  }

  /// Map a text offset back to a column index.
  static int? _textOffsetToCol(BufferLine line, int textOffset) {
    var textPos = 0;
    for (var col = 0; col < line.length; col++) {
      final cp = line.getCodePoint(col);
      final width = line.getWidth(col);
      if (width == 0 && cp == 0) continue;
      if (cp == 0) continue;
      if (textPos == textOffset) return col;
      textPos++;
    }
    if (textPos == textOffset) return line.length;
    return null;
  }

  /// Find the match nearest to [lineIndex].
  int _findNearestMatch(int lineIndex) {
    var bestIndex = 0;
    var bestDist = (lineIndex - _matches[0].lineIndex).abs();

    for (var i = 1; i < _matches.length; i++) {
      final dist = (lineIndex - _matches[i].lineIndex).abs();
      if (dist < bestDist) {
        bestDist = dist;
        bestIndex = i;
      }
    }

    return bestIndex;
  }

  /// Update the active highlight to show a different color.
  void _updateActiveHighlight() {
    _activeHighlight?.dispose();
    _activeHighlight = null;

    if (_activeIndex < 0 || _activeIndex >= _matches.length) return;

    final match = _matches[_activeIndex];
    _activeHighlight = controller.highlight(
      p1: terminal.buffer.createAnchorFromOffset(match.range.begin),
      p2: terminal.buffer.createAnchorFromOffset(match.range.end),
      color: _activeMatchColor,
    );
  }
}
