/// Terminal view controller — manages selection state and highlights.
library;

import 'package:flutter/foundation.dart';

/// Controller for [TerminalView]. Manages selection and highlight state.
///
/// The app's usage is minimal: `TerminalController()` + `.dispose()`.
/// Selection and highlight APIs are available for future features.
class TerminalController extends ChangeNotifier {
  /// Whether text selection is active.
  bool get hasSelection => _selStart != null && _selEnd != null;

  (int, int)? _selStart;
  (int, int)? _selEnd;

  /// Selection start as (col, absoluteRow), or null.
  (int, int)? get selectionStart => _selStart;

  /// Selection end as (col, absoluteRow), or null.
  (int, int)? get selectionEnd => _selEnd;

  /// Set the text selection range.
  void setSelection((int, int) start, (int, int) end) {
    _selStart = start;
    _selEnd = end;
    notifyListeners();
  }

  /// Clear the current selection.
  void clearSelection() {
    if (_selStart == null && _selEnd == null) return;
    _selStart = null;
    _selEnd = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _selStart = null;
    _selEnd = null;
    super.dispose();
  }
}
