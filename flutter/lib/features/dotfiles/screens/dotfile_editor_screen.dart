import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/dotfile.dart';
import '../providers/dotfiles_provider.dart';

class _HighlightingController extends TextEditingController {
  List<int> matches = [];
  int activeIndex = -1;
  int queryLength = 0;

  void updateHighlights({
    required List<int> matches,
    required int activeIndex,
    required int queryLength,
  }) {
    this.matches = matches;
    this.activeIndex = activeIndex;
    this.queryLength = queryLength;
    notifyListeners();
  }

  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    if (matches.isEmpty || queryLength == 0) {
      return TextSpan(text: text, style: style);
    }

    final spans = <TextSpan>[];
    int cursor = 0;

    for (int i = 0; i < matches.length; i++) {
      final start = matches[i];
      final end = start + queryLength;

      if (start > cursor) {
        spans.add(TextSpan(
          text: text.substring(cursor, start),
          style: style,
        ));
      }

      final isActive = i == activeIndex;
      spans.add(TextSpan(
        text: text.substring(start, end),
        style: style?.copyWith(
          backgroundColor: isActive
              ? Colors.orange[600]!.withValues(alpha: 0.9)
              : Colors.amber[300]!.withValues(alpha: 0.35),
          color: isActive ? Colors.white : null,
          fontWeight: isActive ? FontWeight.bold : null,
        ),
      ));

      cursor = end;
    }

    if (cursor < text.length) {
      spans.add(TextSpan(
        text: text.substring(cursor),
        style: style,
      ));
    }

    return TextSpan(children: spans);
  }
}

class DotfileEditorScreen extends ConsumerStatefulWidget {
  const DotfileEditorScreen({super.key});

  @override
  ConsumerState<DotfileEditorScreen> createState() =>
      _DotfileEditorScreenState();
}

class _DotfileEditorScreenState extends ConsumerState<DotfileEditorScreen> {
  final _HighlightingController _controller = _HighlightingController();
  final FocusNode _focusNode = FocusNode();
  final ScrollController _scrollController = ScrollController();
  final ScrollController _hScrollController = ScrollController();
  bool _hasChanges = false;
  String _originalContent = '';
  bool _showSearch = false;
  final TextEditingController _searchController = TextEditingController();
  int _searchIndex = 0;
  List<int> _searchMatches = [];
  bool _caseSensitive = false;

  double _fontSize = 14.0;
  double _scaleStartFontSize = 14.0;

  int get _currentLine {
    if (!_controller.selection.isValid) return 1;
    return _controller.text
        .substring(0, _controller.selection.baseOffset)
        .split('\n')
        .length;
  }

  int get _currentCol {
    if (!_controller.selection.isValid) return 1;
    final before =
        _controller.text.substring(0, _controller.selection.baseOffset);
    return before.length - (before.lastIndexOf('\n') + 1) + 1;
  }

  @override
  void initState() {
    super.initState();
    _focusNode.addListener(_handleKeyPress);
    _searchController.addListener(_onSearchChanged);
    _controller.addListener(() => setState(() {}));
  }

  void _handleKeyPress() {
    if (_focusNode.hasFocus) {
      _handleKeyboardShortcuts();
    }
  }

  void _handleKeyboardShortcuts() {
    // Handle Ctrl+S / Cmd+S for save
  }

  void _onSearchChanged() {
    _findMatches();
    setState(() {});
  }

  void _findMatches() {
    final rawQuery = _searchController.text;
    if (rawQuery.isEmpty) {
      _searchMatches = [];
      _controller.updateHighlights(matches: [], activeIndex: -1, queryLength: 0);
      return;
    }
    final query = _caseSensitive ? rawQuery : rawQuery.toLowerCase();
    final content = _caseSensitive ? _controller.text : _controller.text.toLowerCase();
    final matches = <int>[];
    int index = 0;
    while (true) {
      index = content.indexOf(query, index);
      if (index == -1) break;
      matches.add(index);
      index += 1;
    }
    _searchMatches = matches;
    _searchIndex = 0;
    _controller.updateHighlights(
      matches: _searchMatches,
      activeIndex: _searchMatches.isEmpty ? -1 : _searchIndex,
      queryLength: rawQuery.length,
    );
  }

  void _jumpToNextMatch() {
    if (_searchMatches.isEmpty) return;
    setState(() {
      _searchIndex = (_searchIndex + 1) % _searchMatches.length;
    });
    _controller.updateHighlights(
      matches: _searchMatches,
      activeIndex: _searchIndex,
      queryLength: _searchController.text.length,
    );
    _highlightMatch();
  }

  void _jumpToPreviousMatch() {
    if (_searchMatches.isEmpty) return;
    setState(() {
      _searchIndex =
          (_searchIndex - 1 + _searchMatches.length) % _searchMatches.length;
    });
    _controller.updateHighlights(
      matches: _searchMatches,
      activeIndex: _searchIndex,
      queryLength: _searchController.text.length,
    );
    _highlightMatch();
  }

  void _highlightMatch() {
    if (_searchMatches.isEmpty) return;
    final position = _searchMatches[_searchIndex];
    final query = _searchController.text;
    _controller.selection = TextSelection(
      baseOffset: position,
      extentOffset: position + query.length,
    );
    final totalLength = _controller.text.length;
    final progress = position / totalLength;
    if (_scrollController.hasClients) {
      final targetOffset =
          progress * _scrollController.position.maxScrollExtent;
      _scrollController.animateTo(
        targetOffset,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    }
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    _scrollController.dispose();
    _hScrollController.dispose();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dotfilesState = ref.watch(dotfilesProvider);
    final selectedFile = dotfilesState.selectedFile;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    if (dotfilesState.fileContent != null && _originalContent.isEmpty) {
      _originalContent = dotfilesState.fileContent!;
      _controller.text = dotfilesState.fileContent!;
    }

    return PopScope(
      canPop: !_hasChanges,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _hasChanges) {
          _showUnsavedChangesDialog(context, isDark);
        }
      },
      child: CallbackShortcuts(
        bindings: {
          const SingleActivator(LogicalKeyboardKey.keyS, control: true):
              _saveFile,
          const SingleActivator(LogicalKeyboardKey.keyS, meta: true): _saveFile,
        },
        child: Focus(
          autofocus: true,
          child: Scaffold(
            backgroundColor: isDark
                ? const Color(0xFF0F172A)
                : const Color(0xFFF8FAFC),
            appBar: AppBar(
              backgroundColor: isDark ? const Color(0xFF1E293B) : Colors.white,
              leading: IconButton(
                icon: Icon(
                  Icons.arrow_back,
                  color: isDark ? Colors.white : Colors.black87,
                ),
                onPressed: () {
                  if (_hasChanges) {
                    _showUnsavedChangesDialog(context, isDark);
                  } else {
                    Navigator.pop(context);
                  }
                },
              ),
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    selectedFile?.name ?? 'Editor',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: isDark ? Colors.white : Colors.black87,
                    ),
                  ),
                  if (selectedFile != null)
                    Text(
                      selectedFile.path,
                      style: TextStyle(
                        fontSize: 11,
                        color: isDark ? Colors.grey[400] : Colors.grey[500],
                      ),
                    ),
                ],
              ),
              actions: [
                if (_hasChanges)
                  TextButton.icon(
                    onPressed: _saveFile,
                    icon: const Icon(Icons.save, size: 18),
                    label: const Text('Save'),
                    style: TextButton.styleFrom(
                      foregroundColor: const Color(0xFF6366F1),
                    ),
                  ),
                IconButton(
                  icon: const Icon(Icons.zoom_in, size: 20),
                  onPressed:
                      _fontSize < 26 ? () => setState(() => _fontSize += 1) : null,
                  tooltip: 'Zoom in',
                ),
                IconButton(
                  icon: const Icon(Icons.zoom_out, size: 20),
                  onPressed:
                      _fontSize > 10 ? () => setState(() => _fontSize -= 1) : null,
                  tooltip: 'Zoom out',
                ),
                IconButton(
                  icon: Icon(
                    Icons.history,
                    color: isDark ? Colors.grey[300] : Colors.grey[700],
                  ),
                  onPressed: () {
                    if (selectedFile != null) {
                      ref
                          .read(dotfilesProvider.notifier)
                          .loadHistory(selectedFile.path);
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) =>
                              DotfileHistoryScreen(file: selectedFile),
                        ),
                      );
                    }
                  },
                  tooltip: 'History',
                ),
                IconButton(
                  icon: Icon(
                    _showSearch ? Icons.close : Icons.search,
                    color: isDark ? Colors.grey[300] : Colors.grey[700],
                  ),
                  onPressed: () {
                    setState(() {
                      _showSearch = !_showSearch;
                      if (!_showSearch) {
                        _searchController.clear();
                        _searchMatches = [];
                        _controller.updateHighlights(
                          matches: [],
                          activeIndex: -1,
                          queryLength: 0,
                        );
                      }
                    });
                  },
                  tooltip: 'Search',
                ),
                IconButton(
                  icon: Icon(
                    Icons.vertical_align_bottom,
                    color: isDark ? Colors.grey[300] : Colors.grey[700],
                  ),
                  onPressed: _scrollToEnd,
                  tooltip: 'Scroll to end',
                ),
                IconButton(
                  icon: Icon(
                    Icons.copy,
                    color: isDark ? Colors.grey[300] : Colors.grey[700],
                  ),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: _controller.text));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('Copied to clipboard'),
                        duration: Duration(seconds: 1),
                      ),
                    );
                  },
                  tooltip: 'Copy',
                ),
              ],
            ),
            body: dotfilesState.isLoading
                ? const Center(child: CircularProgressIndicator())
                : Column(
                    children: [
                      if (_showSearch) _buildSearchBar(isDark),
                      Expanded(
                        child: Container(
                          color: isDark
                              ? const Color(0xFF1E1E1E)
                              : Colors.white,
                          child: _buildEditorWithLineNumbers(isDark),
                        ),
                      ),
                      _buildStatusBar(isDark),
                    ],
                  ),
          ),
        ),
      ),
    );
  }

  Widget _buildEditorWithLineNumbers(bool isDark) {
    final lines = _controller.text.split('\n');
    final lineHeight = _fontSize * 1.5;
    final charWidth = _fontSize * 0.6;
    final lineNumWidth =
        (lines.length.toString().length * charWidth + 20).clamp(40.0, 80.0);

    return GestureDetector(
      onScaleStart: (_) => _scaleStartFontSize = _fontSize,
      onScaleUpdate: (d) => setState(
          () => _fontSize = (_scaleStartFontSize * d.scale).clamp(10.0, 26.0)),
      child: SingleChildScrollView(
        controller: _scrollController,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Line numbers
            Container(
              width: lineNumWidth,
              padding: const EdgeInsets.only(top: 12, right: 8, bottom: 100),
              color: isDark ? const Color(0xFF1E1E1E) : Colors.grey[100],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: List.generate(lines.length, (i) {
                  final isCurrentLine = i == _currentLine - 1;
                  return Container(
                    height: lineHeight,
                    color: isCurrentLine
                        ? (isDark
                            ? Colors.white.withValues(alpha: 0.06)
                            : Colors.indigo.withValues(alpha: 0.06))
                        : Colors.transparent,
                    child: Text(
                      '${i + 1}',
                      style: TextStyle(
                        fontFamily: 'monospace',
                        fontSize: _fontSize * 0.85,
                        color: isCurrentLine
                            ? (isDark ? Colors.grey[300] : Colors.indigo[400])
                            : (isDark ? Colors.grey[600] : Colors.grey[400]),
                        height: lineHeight / (_fontSize * 0.85),
                      ),
                    ),
                  );
                }),
              ),
            ),
            // Divider
            Container(
              width: 1,
              color: isDark ? Colors.grey[800] : Colors.grey[300],
              margin: const EdgeInsets.only(top: 12, bottom: 100),
            ),
            // Editor — horizontal scroll prevents line wrapping
            Expanded(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                controller: _hScrollController,
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minWidth: MediaQuery.of(context).size.width -
                        lineNumWidth -
                        1,
                  ),
                  child: TextField(
                    controller: _controller,
                    focusNode: _focusNode,
                    maxLines: null,
                    decoration: InputDecoration(
                      border: InputBorder.none,
                      contentPadding: EdgeInsets.only(
                          top: 12, left: 12, right: 12, bottom: 100),
                    ),
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: _fontSize,
                      color: isDark ? Colors.grey[200] : Colors.grey[800],
                      height: lineHeight / _fontSize,
                    ),
                    onChanged: (v) {
                      if (v != _originalContent && !_hasChanges) {
                        setState(() => _hasChanges = true);
                      }
                      setState(() {});
                    },
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBar(bool isDark) {
    final lines = _controller.text.split('\n').length;
    final chars = _controller.text.length;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      color: isDark ? const Color(0xFF1E293B) : Colors.grey[100],
      child: Row(
        children: [
          Text(
            'Ln $_currentLine, Col $_currentCol',
            style: TextStyle(
              fontSize: 12,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
            ),
          ),
          const SizedBox(width: 16),
          Text(
            'Lines: $lines',
            style: TextStyle(
              fontSize: 12,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
            ),
          ),
          const SizedBox(width: 16),
          Text(
            '$chars chars',
            style: TextStyle(
              fontSize: 12,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
            ),
          ),
          const Spacer(),
          if (_hasChanges)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.orange.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Text(
                'Modified',
                style: TextStyle(
                  fontSize: 11,
                  color: Colors.orange,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildSearchBar(bool isDark) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      color: isDark ? const Color(0xFF1E293B) : Colors.grey[100],
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _searchController,
              autofocus: true,
              decoration: InputDecoration(
                hintText: 'Search...',
                hintStyle: TextStyle(
                  color: isDark ? Colors.grey[500] : Colors.grey[400],
                ),
                prefixIcon: Icon(
                  Icons.search,
                  size: 20,
                  color: isDark ? Colors.grey[400] : Colors.grey[600],
                ),
                filled: true,
                fillColor: isDark ? const Color(0xFF0F172A) : Colors.white,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                isDense: true,
              ),
              style: TextStyle(
                fontSize: 14,
                color: isDark ? Colors.white : Colors.black87,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            _searchMatches.isEmpty
                ? '0/0'
                : '${_searchIndex + 1}/${_searchMatches.length}',
            style: TextStyle(
              fontSize: 12,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
            ),
          ),
          const SizedBox(width: 4),
          Tooltip(
            message: 'Case sensitive',
            child: GestureDetector(
              onTap: () {
                setState(() => _caseSensitive = !_caseSensitive);
                _findMatches();
              },
              child: Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: _caseSensitive
                      ? const Color(0xFF6366F1).withValues(alpha: 0.25)
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(
                    color: _caseSensitive
                        ? const Color(0xFF6366F1)
                        : Colors.transparent,
                  ),
                ),
                child: Center(
                  child: Text(
                    'Aa',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: _caseSensitive
                          ? const Color(0xFF6366F1)
                          : (isDark ? Colors.grey[400] : Colors.grey[600]),
                    ),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 4),
          IconButton(
            icon: Icon(
              Icons.keyboard_arrow_up,
              size: 20,
              color: isDark ? Colors.grey[300] : Colors.grey[700],
            ),
            onPressed: _searchMatches.isEmpty ? null : _jumpToPreviousMatch,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
          ),
          IconButton(
            icon: Icon(
              Icons.keyboard_arrow_down,
              size: 20,
              color: isDark ? Colors.grey[300] : Colors.grey[700],
            ),
            onPressed: _searchMatches.isEmpty ? null : _jumpToNextMatch,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
          ),
        ],
      ),
    );
  }

  void _saveFile() {
    if (!_hasChanges) return;

    final selectedFile = ref.read(dotfilesProvider).selectedFile;
    if (selectedFile != null) {
      ref
          .read(dotfilesProvider.notifier)
          .saveFile(selectedFile.path, _controller.text);
      setState(() {
        _hasChanges = false;
        _originalContent = _controller.text;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('File saved successfully'),
          backgroundColor: Color(0xFF10B981),
          duration: Duration(seconds: 2),
        ),
      );
    }
  }

  void _showUnsavedChangesDialog(BuildContext context, bool isDark) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1E293B) : Colors.white,
        title: Text(
          'Unsaved Changes',
          style: TextStyle(color: isDark ? Colors.white : Colors.black87),
        ),
        content: Text(
          'You have unsaved changes. Do you want to save before leaving?',
          style: TextStyle(color: isDark ? Colors.grey[300] : Colors.grey[700]),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              Navigator.pop(this.context);
            },
            child: const Text('Discard'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              _saveFile();
              Navigator.pop(this.context);
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF6366F1),
            ),
            child: const Text('Save', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }
}

class DotfileHistoryScreen extends ConsumerWidget {
  final DotFile file;

  const DotfileHistoryScreen({super.key, required this.file});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final versions = ref.watch(dotfilesProvider).versions;

    return Scaffold(
      backgroundColor: isDark
          ? const Color(0xFF0F172A)
          : const Color(0xFFF8FAFC),
      appBar: AppBar(
        backgroundColor: isDark ? const Color(0xFF1E293B) : Colors.white,
        title: Text(
          'Version History',
          style: TextStyle(color: isDark ? Colors.white : Colors.black87),
        ),
      ),
      body: versions.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.history,
                    size: 64,
                    color: isDark ? Colors.grey[600] : Colors.grey[400],
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'No version history',
                    style: TextStyle(
                      fontSize: 16,
                      color: isDark ? Colors.grey[400] : Colors.grey[600],
                    ),
                  ),
                ],
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: versions.length,
              itemBuilder: (context, index) {
                final version = versions[index];
                return Card(
                  color: isDark ? const Color(0xFF1E293B) : Colors.white,
                  margin: const EdgeInsets.only(bottom: 8),
                  child: ListTile(
                    title: Text(
                      _formatDate(version.timestamp),
                      style: TextStyle(
                        color: isDark ? Colors.white : Colors.black87,
                      ),
                    ),
                    subtitle: Text(
                      version.id,
                      style: TextStyle(
                        color: isDark ? Colors.grey[400] : Colors.grey[600],
                        fontSize: 12,
                      ),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          icon: const Icon(Icons.restore, size: 20),
                          onPressed: () {
                            ref
                                .read(dotfilesProvider.notifier)
                                .restoreVersion(file.path, version.timestamp);
                            Navigator.pop(context);
                          },
                          tooltip: 'Restore',
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
    );
  }

  String _formatDate(DateTime date) {
    return '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
}
