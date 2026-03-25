import 'package:shellterm/src/core/buffer/buffer.dart';
import 'package:shellterm/src/core/buffer/cell_offset.dart';
import 'package:shellterm/src/core/buffer/line.dart';
import 'package:shellterm/src/core/buffer/range_line.dart';

/// A detected URL within a terminal buffer line.
class TerminalUrl {
  const TerminalUrl({
    required this.url,
    required this.range,
  });

  /// The detected URL string.
  final String url;

  /// The range within the buffer where the URL appears.
  final BufferRangeLine range;
}

/// Detects URLs in terminal buffer lines.
///
/// Scans text content for common URL patterns including:
/// - `http://` and `https://` URLs
/// - `ftp://` URLs
/// - `file://` paths
/// - Email addresses (mailto:)
class UrlDetector {
  UrlDetector({
    RegExp? pattern,
  }) : _pattern = pattern ?? _defaultPattern;

  final RegExp _pattern;

  /// Default URL pattern matching common schemes and bare domains.
  static final _defaultPattern = RegExp(
    r'(?:https?|ftp|file)://[^\s<>\[\]{}|\\^`"' "'" r']+' r'|'
    r'mailto:[^\s<>\[\]{}|\\^`"' "'" r']+',
    caseSensitive: false,
  );

  /// Scan a single [BufferLine] at [lineIndex] for URLs.
  List<TerminalUrl> scanLine(BufferLine line, int lineIndex) {
    final text = line.getText();
    if (text.isEmpty) return const [];

    final results = <TerminalUrl>[];

    for (final match in _pattern.allMatches(text)) {
      // Map string offsets back to cell columns.
      // getText() skips zero-codepoint cells and wide-char trailing cells,
      // so we walk the line to build the mapping.
      final urlText = match.group(0)!;

      // Trim common trailing punctuation that is unlikely part of the URL.
      final trimmed = _trimTrailing(urlText);
      if (trimmed.isEmpty) continue;

      final startCol = _textOffsetToCol(line, match.start);
      final endCol = _textOffsetToCol(line, match.start + trimmed.length);

      if (startCol == null || endCol == null) continue;

      results.add(TerminalUrl(
        url: trimmed,
        range: BufferRangeLine(
          CellOffset(startCol, lineIndex),
          CellOffset(endCol, lineIndex),
        ),
      ));
    }

    return results;
  }

  /// Scan the visible portion of a [Buffer] for URLs.
  List<TerminalUrl> scanBuffer(Buffer buffer, int firstLine, int lastLine) {
    final results = <TerminalUrl>[];
    final lines = buffer.lines;

    for (var i = firstLine; i <= lastLine && i < lines.length; i++) {
      results.addAll(scanLine(lines[i], i));
    }

    return results;
  }

  /// Check if a cell position falls within any detected URL on its line.
  TerminalUrl? hitTest(
    BufferLine line,
    int lineIndex,
    int col,
  ) {
    final urls = scanLine(line, lineIndex);
    for (final url in urls) {
      final range = url.range.normalized;
      if (col >= range.begin.x && col < range.end.x) {
        return url;
      }
    }
    return null;
  }

  /// Map a text offset (from getText()) back to a column index.
  /// Returns null if the offset is out of range.
  static int? _textOffsetToCol(BufferLine line, int textOffset) {
    var textPos = 0;
    for (var col = 0; col < line.length; col++) {
      final cp = line.getCodePoint(col);
      final width = line.getWidth(col);

      // Skip trailing cells of wide characters.
      if (width == 0 && cp == 0) continue;

      // Skip empty cells.
      if (cp == 0) continue;

      if (textPos == textOffset) return col;
      textPos++;
    }

    // If textOffset equals the total text length, return line.length.
    if (textPos == textOffset) return line.length;
    return null;
  }

  /// Remove trailing punctuation that commonly follows URLs but isn't part
  /// of them (e.g. periods, commas, parentheses at end of sentences).
  static String _trimTrailing(String url) {
    var end = url.length;
    while (end > 0) {
      final ch = url[end - 1];
      if (ch == '.' || ch == ',' || ch == ';' || ch == ':' || ch == '!' ||
          ch == '?' || ch == ')' || ch == ']' || ch == '}' || ch == "'" ||
          ch == '"') {
        // Don't trim closing parens if there's a matching opener.
        if (ch == ')' && url.contains('(')) break;
        if (ch == ']' && url.contains('[')) break;
        end--;
      } else {
        break;
      }
    }
    return url.substring(0, end);
  }
}
