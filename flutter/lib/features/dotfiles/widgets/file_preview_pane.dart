import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';

import 'html_preview.dart';

class FilePreviewPane extends StatelessWidget {
  final String content;
  final String extension;

  const FilePreviewPane({
    super.key,
    required this.content,
    required this.extension,
  });

  bool get _isMarkdown => {'md', 'markdown'}.contains(extension);
  bool get _isHtml => {'html', 'htm'}.contains(extension);

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final textColor = isDark ? Colors.grey.shade200 : Colors.grey.shade800;
    final bgColor = isDark ? const Color(0xFF1E1E1E) : Colors.white;
    final codeBg = isDark ? const Color(0xFF2D2D2D) : Colors.grey.shade100;

    if (_isHtml) {
      return Container(
        color: bgColor,
        child: HtmlPreviewWidget(content: content, isDark: isDark),
      );
    }

    return Container(
      color: bgColor,
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: _isMarkdown
            ? _buildMarkdownPreview(textColor, codeBg, isDark)
            : Text(content, style: TextStyle(color: textColor)),
      ),
    );
  }

  Widget _buildMarkdownPreview(Color textColor, Color codeBg, bool isDark) {
    return MarkdownBody(
      data: content,
      selectable: true,
      onTapLink: (text, href, title) async {
        if (href != null) {
          final uri = Uri.tryParse(href);
          if (uri != null) {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
          }
        }
      },
      styleSheet: MarkdownStyleSheet(
        p: TextStyle(color: textColor, fontSize: 14, height: 1.6),
        h1: TextStyle(
          color: textColor,
          fontSize: 24,
          fontWeight: FontWeight.bold,
          height: 1.4,
        ),
        h2: TextStyle(
          color: textColor,
          fontSize: 20,
          fontWeight: FontWeight.bold,
          height: 1.4,
        ),
        h3: TextStyle(
          color: textColor,
          fontSize: 17,
          fontWeight: FontWeight.w600,
          height: 1.4,
        ),
        h4: TextStyle(
          color: textColor,
          fontSize: 15,
          fontWeight: FontWeight.w600,
          height: 1.4,
        ),
        code: TextStyle(
          fontFamily: 'monospace',
          fontSize: 13,
          color: isDark ? Colors.orange.shade200 : Colors.deepOrange,
          backgroundColor: codeBg,
        ),
        codeblockDecoration: BoxDecoration(
          color: codeBg,
          borderRadius: BorderRadius.circular(8),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        blockquoteDecoration: BoxDecoration(
          border: Border(
            left: BorderSide(
              color: const Color(0xFF6366F1).withValues(alpha: 0.5),
              width: 3,
            ),
          ),
        ),
        blockquotePadding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
        listBullet: TextStyle(color: textColor, fontSize: 14),
        tableHead: TextStyle(
          color: textColor,
          fontWeight: FontWeight.bold,
          fontSize: 13,
        ),
        tableBody: TextStyle(color: textColor, fontSize: 13),
        tableBorder: TableBorder.all(
          color: isDark ? Colors.grey.shade700 : Colors.grey.shade300,
        ),
        horizontalRuleDecoration: BoxDecoration(
          border: Border(
            top: BorderSide(
              color: isDark ? Colors.grey.shade700 : Colors.grey.shade300,
            ),
          ),
        ),
      ),
    );
  }
}
