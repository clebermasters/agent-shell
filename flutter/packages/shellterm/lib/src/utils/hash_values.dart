class _HashEnd {
  const _HashEnd();
}

const _HashEnd _hashEnd = _HashEnd();

class _Jenkins {
  static int combine(int hash, Object? o) {
    assert(o is! Iterable);
    hash = 0x1fffffff & (hash + o.hashCode);
    hash = 0x1fffffff & (hash + ((0x0007ffff & hash) << 10));
    return hash ^ (hash >> 6);
  }

  static int finish(int hash) {
    hash = 0x1fffffff & (hash + ((0x03ffffff & hash) << 3));
    hash = hash ^ (hash >> 11);
    return 0x1fffffff & (hash + ((0x00003fff & hash) << 15));
  }
}

int hashValues(
  Object? arg01,
  Object? arg02, [
  Object? arg03 = _hashEnd,
  Object? arg04 = _hashEnd,
  Object? arg05 = _hashEnd,
  Object? arg06 = _hashEnd,
  Object? arg07 = _hashEnd,
  Object? arg08 = _hashEnd,
  Object? arg09 = _hashEnd,
  Object? arg10 = _hashEnd,
]) {
  int result = 0;
  result = _Jenkins.combine(result, arg01);
  result = _Jenkins.combine(result, arg02);
  if (!identical(arg03, _hashEnd)) {
    result = _Jenkins.combine(result, arg03);
    if (!identical(arg04, _hashEnd)) {
      result = _Jenkins.combine(result, arg04);
      if (!identical(arg05, _hashEnd)) {
        result = _Jenkins.combine(result, arg05);
        if (!identical(arg06, _hashEnd)) {
          result = _Jenkins.combine(result, arg06);
          if (!identical(arg07, _hashEnd)) {
            result = _Jenkins.combine(result, arg07);
            if (!identical(arg08, _hashEnd)) {
              result = _Jenkins.combine(result, arg08);
              if (!identical(arg09, _hashEnd)) {
                result = _Jenkins.combine(result, arg09);
                if (!identical(arg10, _hashEnd)) {
                  result = _Jenkins.combine(result, arg10);
                }
              }
            }
          }
        }
      }
    }
  }
  return _Jenkins.finish(result);
}

int hashList(Iterable<Object> arguments) {
  int result = 0;
  for (Object argument in arguments) {
    result = _Jenkins.combine(result, argument);
  }
  return _Jenkins.finish(result);
}
