import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

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
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();
    final bgColor =
        widget.isDark ? const Color(0xFF1E1E1E) : Colors.white;

    _controller = WebViewController()
      ..setBackgroundColor(bgColor)
      ..setJavaScriptMode(JavaScriptMode.disabled)
      ..setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) {
            // Block all navigation — open links externally
            if (request.isMainFrame && request.url != 'about:blank') {
              final uri = Uri.tryParse(request.url);
              if (uri != null) {
                launchUrl(uri, mode: LaunchMode.externalApplication);
              }
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      )
      ..loadHtmlString(
        wrapHtmlContent(widget.content, isDark: widget.isDark),
      );
  }

  @override
  void didUpdateWidget(HtmlPreviewWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.content != widget.content ||
        oldWidget.isDark != widget.isDark) {
      final bgColor =
          widget.isDark ? const Color(0xFF1E1E1E) : Colors.white;
      _controller.setBackgroundColor(bgColor);
      _controller.loadHtmlString(
        wrapHtmlContent(widget.content, isDark: widget.isDark),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return WebViewWidget(controller: _controller);
  }
}
