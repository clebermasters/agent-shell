import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

class AudioService {
  final AudioRecorder _recorder = AudioRecorder();
  String? _currentFilePath;
  DateTime? _recordingStartTime;

  Future<bool> isRecording() async {
    return await _recorder.isRecording();
  }

  Future<bool> hasPermission() async {
    return await _recorder.hasPermission();
  }

  Future<String?> startRecording() async {
    if (!await hasPermission()) {
      return null;
    }

    final directory = await getTemporaryDirectory();
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    _currentFilePath = '${directory.path}/whisper_$timestamp.m4a';

    await _recorder.start(
      const RecordConfig(
        encoder: AudioEncoder.aacLc,
        bitRate: 128000,
        sampleRate: 44100,
      ),
      path: _currentFilePath!,
    );

    _recordingStartTime = DateTime.now();
    return _currentFilePath;
  }

  Future<String?> stopRecording() async {
    if (!await _recorder.isRecording()) {
      return null;
    }

    final path = await _recorder.stop();
    _recordingStartTime = null;
    return path;
  }

  Duration get recordingDuration {
    if (_recordingStartTime == null) {
      return Duration.zero;
    }
    return DateTime.now().difference(_recordingStartTime!);
  }

  Future<void> cancelRecording() async {
    if (await _recorder.isRecording()) {
      await _recorder.stop();
    }
    if (_currentFilePath != null) {
      final file = File(_currentFilePath!);
      if (await file.exists()) {
        await file.delete();
      }
    }
    _currentFilePath = null;
    _recordingStartTime = null;
  }

  Future<void> dispose() async {
    await _recorder.dispose();
  }
}
