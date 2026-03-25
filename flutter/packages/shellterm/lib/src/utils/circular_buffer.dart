/// A circular buffer in which elements know their index in the buffer.
class IndexAwareCircularBuffer<T extends IndexedItem> {
  IndexAwareCircularBuffer(int maxLength)
      : _array = List<T?>.filled(maxLength, null);

  late List<T?> _array;

  var _length = 0;

  var _startIndex = 0;

  var _absoluteStartIndex = 0;

  @pragma('vm:prefer-inline')
  int _getCyclicIndex(int index) {
    return (_startIndex + index) % _array.length;
  }

  @pragma('vm:prefer-inline')
  void _dropChild(int index) {
    final cyclicIndex = _getCyclicIndex(index);
    _array[cyclicIndex]?._detach();
    _array[cyclicIndex] = null;
  }

  @pragma('vm:prefer-inline')
  void _adoptChild(int index, T child) {
    final cyclicIndex = _getCyclicIndex(index);
    _array[cyclicIndex]?._detach();
    _array[cyclicIndex] = child.._attach(this, index);
  }

  @pragma('vm:prefer-inline')
  void _moveChild(int fromIndex, int toIndex) {
    final fromCyclicIndex = _getCyclicIndex(fromIndex);
    final toCyclicIndex = _getCyclicIndex(toIndex);
    _array[toCyclicIndex]?._detach();
    _array[toCyclicIndex] = _array[fromCyclicIndex]?.._move(toIndex);
    _array[fromCyclicIndex] = null;
  }

  @pragma('vm:prefer-inline')
  T? _getChild(int index) {
    return _array[_getCyclicIndex(index)];
  }

  int get maxLength => _array.length;

  set maxLength(int value) {
    if (value <= 0) {
      throw ArgumentError.value(value, 'value', "maxLength can't be negative!");
    }
    if (value == _array.length) return;
    final newArray = List<T?>.generate(
      value,
      (index) => index < _length ? _getChild(index) : null,
    );
    _startIndex = 0;
    _array = newArray;
  }

  int get length => _length;

  void forEach(void Function(T item) callback) {
    final length = _length;
    for (int i = 0; i < length; i++) {
      callback(_getChild(i)!);
    }
  }

  T operator [](int index) {
    RangeError.checkValueInInterval(index, 0, length - 1, 'index');
    return _getChild(index)!;
  }

  operator []=(int index, T value) {
    RangeError.checkValueInInterval(index, 0, length - 1, 'index');
    _adoptChild(index, value);
  }

  void clear() {
    for (var i = 0; i < _length; i++) {
      _dropChild(i);
    }
    _startIndex = 0;
    _length = 0;
  }

  void pushAll(Iterable<T> items) {
    for (var element in items) {
      push(element);
    }
  }

  void push(T value) {
    _adoptChild(_length, value);
    if (_length == _array.length) {
      _startIndex++;
      _absoluteStartIndex++;
      if (_startIndex == _array.length) {
        _startIndex = 0;
      }
    } else {
      _length++;
    }
  }

  T pop() {
    assert(_length > 0, 'Cannot pop from an empty list');
    final result = _getChild(_length - 1);
    _dropChild(_length - 1);
    _length--;
    return result!;
  }

  void remove(int index, [int count = 1]) {
    if (count > 0) {
      if (index + count >= _length) {
        count = _length - index;
      }
      for (var i = index; i < _length - count; i++) {
        _moveChild(i + count, i);
      }
      for (var i = _length - count; i < _length; i++) {
        _dropChild(i);
      }
      _length -= count;
    }
  }

  void insert(int index, T item) {
    RangeError.checkValueInInterval(index, 0, _length, 'index');

    if (index == _length) {
      return push(item);
    }

    if (index == 0 && _length >= _array.length) {
      return;
    }

    for (var i = _length - 1; i >= index; i--) {
      _moveChild(i, i + 1);
    }

    _adoptChild(index, item);

    if (_length >= _array.length) {
      _startIndex += 1;
      _absoluteStartIndex += 1;
    } else {
      _length++;
    }
  }

  void insertAll(int index, List<T> items) {
    for (var i = items.length - 1; i >= 0; i--) {
      insert(index, items[i]);
      if (_length >= _array.length) {
        index--;
        if (index < 0) return;
      }
    }
  }

  void trimStart(int count) {
    if (count > _length) count = _length;
    _startIndex += count;
    _startIndex %= _array.length;
    _length -= count;
  }

  void replaceWith(List<T> replacement) {
    for (var i = 0; i < _length; i++) {
      _dropChild(i);
    }

    var copyStart = 0;
    if (replacement.length > maxLength) {
      copyStart = replacement.length - maxLength;
    }

    for (var i = 0; i < copyStart; i++) {
      _dropChild(i);
    }

    final copyLength = replacement.length - copyStart;
    for (var i = 0; i < copyLength; i++) {
      _adoptChild(i, replacement[copyStart + i]);
    }

    _startIndex = 0;
    _length = copyLength;
  }

  T swap(int index, T value) {
    final result = _getChild(index);
    _adoptChild(index, value);
    return result!;
  }

  bool get isFull => length == maxLength;

  List<T> toList() {
    return List<T>.generate(length, (index) => this[index]);
  }

  String debugDump() {
    final buffer = StringBuffer();
    buffer.writeln('CircularList:');
    for (var i = 0; i < _length; i++) {
      final child = _getChild(i);
      buffer.writeln('  $i: $child');
    }
    return buffer.toString();
  }
}

mixin IndexedItem {
  IndexAwareCircularBuffer? _owner;

  int? _absoluteIndex;

  int get index => _absoluteIndex! - _owner!._absoluteStartIndex;

  bool get attached => _owner != null;

  void _attach(IndexAwareCircularBuffer owner, int index) {
    _owner = owner;
    _absoluteIndex = owner._absoluteStartIndex + index;
  }

  void _detach() {
    _owner = null;
    _absoluteIndex = null;
  }

  void _move(int newIndex) {
    assert(attached);
    _absoluteIndex = _owner!._absoluteStartIndex + newIndex;
  }
}
