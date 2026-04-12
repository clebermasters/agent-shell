import 'dart:io';
import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../core/config/app_config.dart';

class WhisperService {
  final Dio _dio = Dio();
  static const String _whisperUrl =
      'https://api.openai.com/v1/audio/transcriptions';

  Future<String?> transcribe(String audioFilePath, String apiKey) async {
    try {
      final file = File(audioFilePath);
      if (!await file.exists()) {
        return null;
      }

      final formData = FormData.fromMap({
        'file': await MultipartFile.fromFile(
          audioFilePath,
          filename: 'audio.m4a',
        ),
        'model': 'gpt-4o-mini-transcribe',
        'language': 'en',
      });

      final response = await _dio.post(
        _whisperUrl,
        data: formData,
        options: Options(headers: {'Authorization': 'Bearer $apiKey'}),
      );

      if (response.statusCode == 200) {
        return response.data['text'] as String?;
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  static Future<String?> getApiKey(SharedPreferences prefs) async {
    return prefs.getString(AppConfig.keyOpenAiApiKey);
  }
}
