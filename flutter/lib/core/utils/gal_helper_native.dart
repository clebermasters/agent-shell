import 'package:gal/gal.dart';

Future<void> galPutImage(String filePath) async {
  await Gal.putImage(filePath, album: 'AgentShell');
}
