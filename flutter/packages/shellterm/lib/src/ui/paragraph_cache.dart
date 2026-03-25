import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:shellterm/src/utils/lru_cache.dart';

/// A cache of laid-out [Paragraph]s. Replaces the quiver-based LruMap
/// with our zero-dependency [LruCache].
class ParagraphCache {
  ParagraphCache(int maximumSize)
      : _cache = LruCache<int, Paragraph>(maximumSize: maximumSize);

  final LruCache<int, Paragraph> _cache;

  Paragraph? getLayoutFromCache(int key) => _cache[key];

  Paragraph performAndCacheLayout(
    String text,
    TextStyle style,
    TextScaler textScaler,
    int key,
  ) {
    final builder = ParagraphBuilder(style.getParagraphStyle());
    builder.pushStyle(style.getTextStyle(textScaler: textScaler));
    builder.addText(text);
    final paragraph = builder.build();
    paragraph.layout(const ParagraphConstraints(width: double.infinity));
    _cache[key] = paragraph;
    return paragraph;
  }

  void clear() => _cache.clear();

  int get length => _cache.length;
}
