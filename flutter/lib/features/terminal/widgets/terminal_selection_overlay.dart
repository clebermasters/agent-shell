import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';

/// Full-screen overlay that extracts terminal buffer text and displays it
/// using Flutter's native [SelectableText] widget. This completely bypasses
/// xterm's internal coordinate/painting system for selection, providing
/// reliable text selection with native handles, magnifier, and copy toolbar.
class TerminalSelectionOverlay extends StatefulWidget {
  final Terminal terminal;
  final double fontSize;
  final VoidCallback onClose;

  const TerminalSelectionOverlay({
    super.key,
    required this.terminal,
    required this.fontSize,
    required this.onClose,
  });

  @override
  State<TerminalSelectionOverlay> createState() =>
      _TerminalSelectionOverlayState();
}

class _TerminalSelectionOverlayState extends State<TerminalSelectionOverlay> {
  late final ScrollController _scrollController;
  late String _text;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
    _text = _extractText();

    // Scroll to bottom (most recent output) after first frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
      }
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  String _extractText() {
    final buffer = widget.terminal.buffer;
    final lines = buffer.lines;
    final sb = StringBuffer();

    for (int i = 0; i < lines.length; i++) {
      final line = lines[i];
      final lineText = line.getText();

      // Only add newline if the line is not wrapped from the previous one.
      if (i > 0 && !line.isWrapped) {
        sb.write('\n');
      }
      sb.write(lineText);
    }

    return sb.toString();
  }

  void _refreshText() {
    setState(() {
      _text = _extractText();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFF1A1A1A),
      child: Column(
        children: [
          // Top bar with refresh and close
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: const BoxDecoration(
              color: Color(0xFF2D2D2D),
              border: Border(
                bottom: BorderSide(color: Color(0xFF444444), width: 0.5),
              ),
            ),
            child: Row(
              children: [
                const Icon(Icons.select_all, color: Colors.orange, size: 18),
                const SizedBox(width: 8),
                const Text(
                  'Select & Copy Text',
                  style: TextStyle(
                    color: Colors.orange,
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.refresh, color: Colors.white70, size: 20),
                  onPressed: _refreshText,
                  tooltip: 'Refresh',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
                ),
                const SizedBox(width: 4),
                IconButton(
                  icon: const Icon(Icons.close, color: Colors.white70, size: 20),
                  onPressed: widget.onClose,
                  tooltip: 'Close',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
                ),
              ],
            ),
          ),

          // Selectable terminal text
          Expanded(
            child: SingleChildScrollView(
              controller: _scrollController,
              padding: const EdgeInsets.all(8),
              child: SizedBox(
                width: double.infinity,
                child: SelectableText(
                  _text,
                  style: TextStyle(
                    fontFamily: 'JetBrains Mono',
                    fontSize: widget.fontSize,
                    color: const Color(0xFFCCCCCC),
                    height: 1.2,
                  ),
                  contextMenuBuilder: (context, editableTextState) {
                    return AdaptiveTextSelectionToolbar.buttonItems(
                      anchors: editableTextState.contextMenuAnchors,
                      buttonItems: [
                        ContextMenuButtonItem(
                          label: 'Copy',
                          onPressed: () {
                            editableTextState
                                .copySelection(SelectionChangedCause.toolbar);
                            _showCopiedSnackbar();
                          },
                        ),
                        ContextMenuButtonItem(
                          label: 'Select All',
                          onPressed: () {
                            editableTextState.selectAll(
                                SelectionChangedCause.toolbar);
                          },
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showCopiedSnackbar() {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Copied to clipboard'),
        duration: Duration(seconds: 1),
      ),
    );
  }
}
