import 'package:flutter/foundation.dart';

Future<void> initializeBackgroundService() async {
  if (kIsWeb) return;
  await _initNative();
}

Future<void> _initNative() async {
  // Imported only on non-web via conditional import below
}
