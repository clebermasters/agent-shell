import 'dart:html' as html;
import 'dart:ui_web' as ui_web;

import 'package:flutter/material.dart';

import 'html_document_wrapper.dart';

class HtmlPreviewWidget extends StatefulWidget {
  final String content;
  final bool isDark;

  const HtmlPreviewWidget({
    super.key,
    required this.content,
    required this.isDark,
  });

  @override
  State<HtmlPreviewWidget> createState() => _HtmlPreviewWidgetState();
}

class _HtmlPreviewWidgetState extends State<HtmlPreviewWidget> {
  late final String _viewType;
  late html.IFrameElement _iframe;

  @override
  void initState() {
    super.initState();
    _viewType = 'html-preview-${hashCode}';
    _iframe = _createIframe();

    ui_web.platformViewRegistry.registerViewFactory(
      _viewType,
      (int viewId) => _iframe,
    );
  }

  html.IFrameElement _createIframe() {
    final iframe = html.IFrameElement()
      ..style.border = 'none'
      ..style.width = '100%'
      ..style.height = '100%'
      ..setAttribute('sandbox', 'allow-same-origin')
      ..srcdoc = wrapHtmlContent(widget.content, isDark: widget.isDark);
    return iframe;
  }

  @override
  void didUpdateWidget(HtmlPreviewWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.content != widget.content ||
        oldWidget.isDark != widget.isDark) {
      _iframe.srcdoc =
          wrapHtmlContent(widget.content, isDark: widget.isDark);
    }
  }

  @override
  Widget build(BuildContext context) {
    return HtmlElementView(viewType: _viewType);
  }
}
