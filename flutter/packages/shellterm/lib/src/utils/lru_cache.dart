import 'dart:collection';

/// A Least-Recently-Used cache backed by a [LinkedHashMap].
/// Replaces `quiver`'s LruMap with zero external dependencies.
class LruCache<K, V> {
  LruCache({required this.maximumSize}) : assert(maximumSize > 0);

  final int maximumSize;

  final _map = LinkedHashMap<K, V>();

  V? operator [](K key) {
    final value = _map.remove(key);
    if (value != null) {
      _map[key] = value; // move to MRU position
    }
    return value;
  }

  void operator []=(K key, V value) {
    _map.remove(key);
    _map[key] = value;
    if (_map.length > maximumSize) {
      _map.remove(_map.keys.first); // evict LRU
    }
  }

  bool containsKey(K key) => _map.containsKey(key);

  void clear() => _map.clear();

  int get length => _map.length;

  Iterable<K> get keys => _map.keys;

  Iterable<V> get values => _map.values;
}
