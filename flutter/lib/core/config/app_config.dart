import 'build_config.dart';

class AppConfig {
  AppConfig._();

  // App Info
  static const String appName = 'AgentShell';
  static const String appVersion = '1.0.0';

  // API - uses build config defaults if available
  static String get apiBaseUrl {
    final servers = _parseServerList(BuildConfig.defaultServerList);
    if (servers.isNotEmpty) {
      return 'http://${servers.first['address']}:${servers.first['port']}';
    }
    return 'http://192.168.0.76:4010';
  }

  static String get wsBaseUrl {
    final servers = _parseServerList(BuildConfig.defaultServerList);
    if (servers.isNotEmpty) {
      return 'ws://${servers.first['address']}:${servers.first['port']}/ws';
    }
    return 'ws://192.168.0.76:4010/ws';
  }

  // Terminal
  static const int terminalCols = 80;
  static const int terminalRows = 24;
  static const double terminalFontSize = 14.0;

  // Timeouts
  static const Duration connectionTimeout = Duration(seconds: 30);
  static const Duration receiveTimeout = Duration(seconds: 30);

  // Storage Keys
  static const String keyHosts = 'hosts';
  static const String keySelectedHost = 'selected_host';
  static const String keyThemeMode = 'theme_mode';
  static const String keyTerminalFontSize = 'terminal_font_size';
  static const String keyOpenAiApiKey = 'openai_api_key';
  static const String keyVoiceAutoEnter = 'voice_auto_enter';
  static const String keyShowVoiceButton = 'show_voice_button';
  static const String keyShowThinking = 'show_thinking';
  static const String keyShowToolCalls = 'show_tool_calls';
  static const String keyWebAuthToken = 'web_auth_token';
  static const String chatDraftKeyPrefix = 'chat_draft_';
  static const String keyFileSortMode = 'file_sort_mode';
  static const String keyFileShowHidden = 'file_show_hidden';
  static const String keyFileEditorFontSize = 'file_editor_font_size';
  static const String keyRecentTerminalSessions = 'recent_terminal_sessions';
  static const String keyRecentChatSessions = 'recent_chat_sessions';

  // Build-time defaults
  static String get defaultServerList => BuildConfig.defaultServerList;
  static String get defaultApiKey => BuildConfig.defaultApiKey;
  static bool get defaultShowThinking => BuildConfig.defaultShowThinking;
  static bool get defaultShowToolCalls => BuildConfig.defaultShowToolCalls;

  // Helper to parse SERVER_LIST from env
  // Format: "address:port,name|address:port,name"
  static List<Map<String, dynamic>> _parseServerList(String serverList) {
    if (serverList.isEmpty) return [];

    final servers = <Map<String, dynamic>>[];
    final entries = serverList.split('|');

    for (final entry in entries) {
      final trimmed = entry.trim();
      if (trimmed.isEmpty) continue;

      final parts = trimmed.split(',');
      if (parts.length >= 2) {
        final addressPort = parts[0].split(':');
        if (addressPort.length >= 2) {
          servers.add({
            'address': addressPort[0],
            'port': int.tryParse(addressPort[1]) ?? 4010,
            'name': parts[1],
          });
        }
      }
    }

    return servers;
  }

  static List<Map<String, dynamic>> get parsedServerList =>
      _parseServerList(BuildConfig.defaultServerList);
}
