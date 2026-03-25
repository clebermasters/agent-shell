import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';

class AlertMarkdownScreen extends StatelessWidget {
  final Uint8List bytes;
  final String filename;

  const AlertMarkdownScreen({
    super.key,
    required this.bytes,
    required this.filename,
  });

  @override
  Widget build(BuildContext context) {
    final text = utf8.decode(bytes, allowMalformed: true);

    return Scaffold(
      appBar: AppBar(
        title: Text(filename, style: const TextStyle(fontSize: 14)),
      ),
      body: Markdown(
        data: text,
        padding: const EdgeInsets.all(16),
        selectable: true,
        onTapLink: (text, href, title) {
          if (href != null) launchUrl(Uri.parse(href));
        },
      ),
    );
  }
}
