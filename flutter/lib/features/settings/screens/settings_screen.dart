import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../../core/config/build_config.dart';
import '../../chat/providers/chat_provider.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final TextEditingController _apiKeyController = TextEditingController();
  bool _obscureApiKey = true;
  bool _isSaving = false;
  bool _isSavingVoiceSettings = false;
  bool _voiceAutoEnter = false;
  bool _showThinking = true;
  bool _showToolCalls = true;
  String? _savedMessage;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadApiKey();
    });
  }

  Future<void> _loadApiKey() async {
    final prefs = ref.read(sharedPreferencesProvider);
    var apiKey = prefs.getString(AppConfig.keyOpenAiApiKey);
    if (apiKey != null && apiKey.isNotEmpty) {
      _apiKeyController.text = apiKey;
    } else if (BuildConfig.defaultApiKey.isNotEmpty) {
      // Use build-time default if no saved key, and save it to SharedPreferences
      _apiKeyController.text = BuildConfig.defaultApiKey;
      await prefs.setString(
        AppConfig.keyOpenAiApiKey,
        BuildConfig.defaultApiKey,
      );
    }
    _voiceAutoEnter = prefs.getBool(AppConfig.keyVoiceAutoEnter) ?? false;

    // Load chat display settings
    _showThinking =
        prefs.getBool(AppConfig.keyShowThinking) ??
        AppConfig.defaultShowThinking;
    _showToolCalls =
        prefs.getBool(AppConfig.keyShowToolCalls) ??
        AppConfig.defaultShowToolCalls;

    if (mounted) setState(() {});
  }

  Future<void> _saveApiKey() async {
    setState(() {
      _isSaving = true;
      _savedMessage = null;
    });

    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setString(
      AppConfig.keyOpenAiApiKey,
      _apiKeyController.text.trim(),
    );

    setState(() {
      _isSaving = false;
      _savedMessage = 'API key saved successfully';
    });

    Future.delayed(const Duration(seconds: 2), () {
      if (mounted) {
        setState(() {
          _savedMessage = null;
        });
      }
    });
  }

  Future<void> _saveVoiceAutoEnter(bool enabled) async {
    setState(() {
      _isSavingVoiceSettings = true;
      _voiceAutoEnter = enabled;
    });

    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setBool(AppConfig.keyVoiceAutoEnter, enabled);

    if (!mounted) return;
    setState(() {
      _isSavingVoiceSettings = false;
    });
  }

  Future<void> _saveShowThinking(bool enabled) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setBool(AppConfig.keyShowThinking, enabled);
    setState(() {
      _showThinking = enabled;
    });
    ref.read(chatProvider.notifier).refreshDisplaySettings();
  }

  Future<void> _saveShowToolCalls(bool enabled) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setBool(AppConfig.keyShowToolCalls, enabled);
    setState(() {
      _showToolCalls = enabled;
    });
    ref.read(chatProvider.notifier).refreshDisplaySettings();
  }

  @override
  void dispose() {
    _apiKeyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildSectionHeader('Voice Input (Whisper)'),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'OpenAI API Key',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: isDarkMode
                          ? Colors.grey.shade200
                          : Colors.grey.shade800,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Required for voice input feature (Whisper)',
                    style: TextStyle(
                      fontSize: 12,
                      color: isDarkMode
                          ? Colors.grey.shade400
                          : Colors.grey.shade600,
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _apiKeyController,
                    obscureText: _obscureApiKey,
                    decoration: InputDecoration(
                      hintText: 'sk-...',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      suffixIcon: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: Icon(
                              _obscureApiKey
                                  ? Icons.visibility
                                  : Icons.visibility_off,
                              size: 20,
                            ),
                            onPressed: () {
                              setState(() {
                                _obscureApiKey = !_obscureApiKey;
                              });
                            },
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Auto-enter transcriptions'),
                    subtitle: const Text(
                      'When enabled, voice transcription is sent immediately in chat and terminal.',
                    ),
                    value: _voiceAutoEnter,
                    onChanged: _isSavingVoiceSettings
                        ? null
                        : (value) => _saveVoiceAutoEnter(value),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: _isSaving ? null : _saveApiKey,
                      child: _isSaving
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Save API Key'),
                    ),
                  ),
                  if (_savedMessage != null) ...[
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.green.shade50,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.green.shade200),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            Icons.check_circle,
                            color: Colors.green.shade700,
                            size: 20,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            _savedMessage!,
                            style: TextStyle(color: Colors.green.shade700),
                          ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
          _buildSectionHeader('Chat Display'),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Control how AI agent activity appears in chat',
                    style: TextStyle(
                      fontSize: 12,
                      color: isDarkMode
                          ? Colors.grey.shade400
                          : Colors.grey.shade600,
                    ),
                  ),
                  const SizedBox(height: 16),
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Show AI Thinking'),
                    subtitle: const Text(
                      'Display the AI agent\'s thinking process in chat',
                    ),
                    value: _showThinking,
                    onChanged: (value) => _saveShowThinking(value),
                  ),
                  const Divider(),
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Show Tool Calls'),
                    subtitle: const Text(
                      'Display tool calls made by the AI agent (Read, Edit, Bash, etc.)',
                    ),
                    value: _showToolCalls,
                    onChanged: (value) => _saveShowToolCalls(value),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
          _buildSectionHeader('About'),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Voice Input',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: isDarkMode
                          ? Colors.grey.shade200
                          : Colors.grey.shade800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Use the microphone in chat mode to speak instead of typing. '
                    'Your voice is transcribed using OpenAI Whisper API and sent as a message.',
                    style: TextStyle(
                      fontSize: 13,
                      color: isDarkMode
                          ? Colors.grey.shade400
                          : Colors.grey.shade600,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Text(
      title,
      style: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        color: Theme.of(context).colorScheme.primary,
      ),
    );
  }
}
