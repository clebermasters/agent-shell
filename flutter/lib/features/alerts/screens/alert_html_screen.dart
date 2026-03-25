import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../../dotfiles/widgets/html_preview.dart';

class AlertHtmlScreen extends StatelessWidget {
  final Uint8List bytes;
  final String filename;

  const AlertHtmlScreen({
    super.key,
    required this.bytes,
    required this.filename,
  });

  @override
  Widget build(BuildContext context) {
    final content = utf8.decode(bytes, allowMalformed: true);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: Text(filename, style: const TextStyle(fontSize: 14)),
      ),
      body: HtmlPreviewWidget(content: content, isDark: isDark),
    );
  }
}
