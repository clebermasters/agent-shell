/// Wraps raw HTML content in a complete document with theme-aware CSS.
String wrapHtmlContent(String rawHtml, {required bool isDark}) {
  final bg = isDark ? '#1E1E1E' : '#FFFFFF';
  final text = isDark ? '#E2E8F0' : '#1E293B';
  final link = isDark ? '#60A5FA' : '#2563EB';
  final codeBg = isDark ? '#2D2D2D' : '#F1F5F9';
  final codeBorder = isDark ? '#404040' : '#E2E8F0';
  final blockquoteBorder = isDark ? '#6366F1' : '#818CF8';
  final tableBorder = isDark ? '#404040' : '#D1D5DB';
  final hrColor = isDark ? '#404040' : '#D1D5DB';

  final themeCSS = '''
    <style>
      * { box-sizing: border-box; }
      html, body {
        margin: 0;
        padding: 16px;
        background: $bg;
        color: $text;
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        font-size: 14px;
        line-height: 1.6;
        word-wrap: break-word;
        overflow-wrap: break-word;
      }
      a { color: $link; }
      img { max-width: 100%; height: auto; }
      code {
        font-family: 'JetBrains Mono', 'Fira Code', monospace;
        font-size: 13px;
        background: $codeBg;
        padding: 2px 6px;
        border-radius: 4px;
        border: 1px solid $codeBorder;
      }
      pre {
        background: $codeBg;
        padding: 12px;
        border-radius: 8px;
        border: 1px solid $codeBorder;
        overflow-x: auto;
      }
      pre code {
        background: none;
        padding: 0;
        border: none;
      }
      blockquote {
        margin: 8px 0;
        padding: 4px 12px;
        border-left: 3px solid $blockquoteBorder;
      }
      table {
        border-collapse: collapse;
        width: 100%;
        margin: 8px 0;
      }
      th, td {
        border: 1px solid $tableBorder;
        padding: 8px 12px;
        text-align: left;
      }
      th { font-weight: bold; }
      hr {
        border: none;
        border-top: 1px solid $hrColor;
        margin: 16px 0;
      }
      h1, h2, h3, h4, h5, h6 { margin-top: 1em; margin-bottom: 0.5em; }
    </style>
  ''';

  final hasDoctype = rawHtml.trimLeft().toLowerCase().startsWith('<!doctype');
  final hasHtmlTag =
      RegExp(r'<html[\s>]', caseSensitive: false).hasMatch(rawHtml);

  if (hasDoctype || hasHtmlTag) {
    // Inject theme CSS into existing document
    final headClose = RegExp(r'</head>', caseSensitive: false);
    if (headClose.hasMatch(rawHtml)) {
      return rawHtml.replaceFirst(headClose, '$themeCSS</head>');
    }
    // No </head> — inject after <html> or at the start
    final htmlOpen = RegExp(r'<html[^>]*>', caseSensitive: false);
    if (htmlOpen.hasMatch(rawHtml)) {
      return rawHtml.replaceFirst(
          htmlOpen, '${htmlOpen.firstMatch(rawHtml)!.group(0)}<head>$themeCSS</head>');
    }
    return '$themeCSS$rawHtml';
  }

  return '''<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  $themeCSS
</head>
<body>
$rawHtml
</body>
</html>''';
}
