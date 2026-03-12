import 'dart:io' show Platform;
import 'package:gal/gal.dart';

Future<void> galPutImage(String filePath) async {
  if (Platform.isLinux || Platform.isWindows) {
    throw UnsupportedError('Gallery save not supported on ${Platform.operatingSystem}');
  }
  await Gal.putImage(filePath, album: 'AgentShell');
}
