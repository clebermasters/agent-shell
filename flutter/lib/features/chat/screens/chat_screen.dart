import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'dart:convert';
import 'dart:io';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../../data/services/websocket_service.dart';
import '../providers/chat_provider.dart';
import '../widgets/professional_message_bubble.dart';
import '../../hosts/providers/hosts_provider.dart';
import '../../sessions/providers/sessions_provider.dart';
import '../../terminal/screens/terminal_screen.dart';

class ChatScreen extends ConsumerStatefulWidget {
  final String sessionName;
  final int windowIndex;
  final bool isAcp;

  const ChatScreen({
    super.key,
    required this.sessionName,
    this.windowIndex = 0,
    this.isAcp = false,
  });

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  bool _showScrollButton = false;
  bool _autoScroll = true;
  int _previousMessageCount = 0;
  String? _lastTranscribedText;
  bool _wasAtBottom = true;
  static const double _bottomThreshold = 100;
  PlatformFile? _selectedFile;
  bool _isUploading = false;

  bool get isDarkMode {
    return Theme.of(context).brightness == Brightness.dark;
  }

  Color get backgroundColor =>
      isDarkMode ? const Color(0xFF0F172A) : const Color(0xFFF8FAFC);

  Color get surfaceColor => isDarkMode ? const Color(0xFF1E293B) : Colors.white;

  Color get textPrimary =>
      isDarkMode ? Colors.grey.shade100 : const Color(0xFF1E293B);

  Color get textSecondary =>
      isDarkMode ? Colors.grey.shade400 : const Color(0xFF64748B);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (widget.isAcp) {
        // First select ACP backend to ensure client is initialized, then resume the session
        final ws = ref.read(sharedWebSocketServiceProvider);
        ws.selectBackend('acp');
        // Resume the session to attach to it
        ws.acpResumeSession(widget.sessionName, '');
        ref.read(chatProvider.notifier).startAcpChat(widget.sessionName);
      } else {
        ref
            .read(chatProvider.notifier)
            .watchChatLog(widget.sessionName, widget.windowIndex);
      }

      // Initialize scroll position tracking
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _updateScrollState();
      });
    });

    _scrollController.addListener(_onScroll);
  }

  void _updateScrollState() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    _wasAtBottom = position.pixels >= position.maxScrollExtent - 10;
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;

    final position = _scrollController.position;
    final isNearBottom =
        position.maxScrollExtent - position.pixels < _bottomThreshold;
    final atBottom = position.pixels >= position.maxScrollExtent - 10;

    // Show/hide scroll button based on position
    if (_showScrollButton != !isNearBottom) {
      setState(() {
        _showScrollButton = !isNearBottom;
      });
    }

    // Detect if user scrolled away from bottom - disable auto-scroll
    if (_wasAtBottom && !atBottom && _autoScroll) {
      setState(() {
        _autoScroll = false;
      });
    }

    // Detect if user scrolled to bottom - enable auto-scroll
    if (!_wasAtBottom && atBottom && !_autoScroll) {
      setState(() {
        _autoScroll = true;
      });
    }

    // Load more messages when scrolled near the top
    final isNearTop = position.pixels < 100;
    if (isNearTop) {
      final chatState = ref.read(chatProvider);
      if (chatState.hasMoreMessages &&
          !chatState.isLoadingMore &&
          !chatState.isLoading) {
        ref.read(chatProvider.notifier).loadMoreMessages();
      }
    }

    _wasAtBottom = atBottom;
  }

  void _checkAndScrollToBottom(int newCount) {
    if (_autoScroll && newCount > _previousMessageCount) {
      _smoothScrollToBottom();
    }
    _previousMessageCount = newCount;
  }

  void _smoothScrollToBottom() {
    if (!_scrollController.hasClients) return;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;

      final maxScroll = _scrollController.position.maxScrollExtent;
      final currentScroll = _scrollController.position.pixels;

      if (maxScroll - currentScroll > 50) {
        _scrollController.animateTo(
          maxScroll,
          duration: const Duration(milliseconds: 400),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  @override
  void dispose() {
    ref.read(chatProvider.notifier).unwatchChatLog();
    _controller.dispose();
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _sendMessage() {
    final content = _controller.text.trim();

    if (_selectedFile != null) {
      _sendFileWithPrompt();
    } else if (content.isNotEmpty) {
      _submitMessage(content);
    }
  }

  void _submitMessage(String content) {
    if (content.isEmpty) return;

    ref.read(chatProvider.notifier).addUserMessage(content);

    if (widget.isAcp) {
      ref
          .read(sharedWebSocketServiceProvider)
          .acpSendPrompt(widget.sessionName, content);
    } else {
      ref.read(chatProvider.notifier).sendInput(content);
    }

    _controller.clear();
    _scrollToBottom();
    setState(() {
      _autoScroll = true;
      _showScrollButton = false;
    });
  }

  void _scrollToBottom() {
    setState(() {
      _autoScroll = true;
    });
    _smoothScrollToBottom();
  }

  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: false,
      );

      if (result != null && result.files.isNotEmpty) {
        setState(() {
          _selectedFile = result.files.first;
        });
      }
    } catch (e) {
      print('Error picking file: $e');
    }
  }

  void _removeSelectedFile() {
    setState(() {
      _selectedFile = null;
    });
  }

  Widget _buildSelectedFilePreview() {
    if (_selectedFile == null) return const SizedBox.shrink();

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: isDarkMode ? const Color(0xFF2D3748) : const Color(0xFFE2E8F0),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(
            _getFileIcon(_selectedFile!.extension ?? ''),
            size: 20,
            color: textSecondary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _selectedFile!.name,
              style: TextStyle(fontSize: 14, color: textPrimary),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (_isUploading)
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else
            GestureDetector(
              onTap: _removeSelectedFile,
              child: Icon(Icons.close, size: 18, color: textSecondary),
            ),
        ],
      ),
    );
  }

  Widget _buildAttachButton() {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: _isUploading ? null : _pickFile,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: isDarkMode
                ? const Color(0xFF4A5568)
                : const Color(0xFFE2E8F0),
            shape: BoxShape.circle,
          ),
          child: Icon(
            Icons.attach_file,
            size: 20,
            color: isDarkMode ? Colors.white : Colors.grey.shade700,
          ),
        ),
      ),
    );
  }

  IconData _getFileIcon(String extension) {
    switch (extension.toLowerCase()) {
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
      case 'bmp':
        return Icons.image;
      case 'mp3':
      case 'wav':
      case 'ogg':
      case 'm4a':
        return Icons.audio_file;
      case 'pdf':
        return Icons.picture_as_pdf;
      case 'doc':
      case 'docx':
        return Icons.description;
      case 'xls':
      case 'xlsx':
        return Icons.table_chart;
      case 'zip':
      case 'rar':
      case '7z':
      case 'tar':
      case 'gz':
        return Icons.folder_zip;
      default:
        return Icons.insert_drive_file;
    }
  }

  Future<void> _sendFileWithPrompt() async {
    if (_selectedFile == null) return;

    final ws = ref.read(sharedWebSocketServiceProvider);

    setState(() {
      _isUploading = true;
    });

    try {
      final filePath = _selectedFile!.path;
      if (filePath == null) return;

      final file = File(filePath);
      final bytes = await file.readAsBytes();
      final base64Data = base64Encode(bytes);

      final prompt = _controller.text.trim();

      ws.sendFileToChat(
        sessionName: widget.sessionName,
        windowIndex: widget.windowIndex,
        filename: _selectedFile!.name,
        mimeType: _selectedFile!.extension ?? 'application/octet-stream',
        base64Data: base64Data,
        prompt: prompt.isNotEmpty ? prompt : null,
      );

      _controller.clear();
      setState(() {
        _selectedFile = null;
        _autoScroll = true;
      });
      _scrollToBottom();
    } catch (e) {
      print('Error sending file: $e');
    } finally {
      setState(() {
        _isUploading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatProvider);
    final prefs = ref.read(sharedPreferencesProvider);
    final shouldAutoEnterVoiceText =
        prefs.getBool(AppConfig.keyVoiceAutoEnter) ?? false;

    if (chatState.transcribedText != null &&
        chatState.transcribedText!.isNotEmpty &&
        chatState.transcribedText != _lastTranscribedText) {
      final transcribedText = chatState.transcribedText!.trim();
      _lastTranscribedText = chatState.transcribedText;
      ref.read(chatProvider.notifier).clearTranscribedText();

      if (shouldAutoEnterVoiceText && transcribedText.isNotEmpty) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          _submitMessage(transcribedText);
        });
      } else {
        _controller.text = transcribedText;
        _controller.selection = TextSelection.fromPosition(
          TextPosition(offset: _controller.text.length),
        );
      }
    }

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkAndScrollToBottom(chatState.messages.length);
    });

    return Scaffold(
      backgroundColor: backgroundColor,
      appBar: AppBar(
        backgroundColor: surfaceColor,
        elevation: 0,
        scrolledUnderElevation: 1,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              widget.sessionName,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: textPrimary,
              ),
            ),
            if (chatState.detectedTool != null)
              Row(
                children: [
                  Container(
                    width: 6,
                    height: 6,
                    decoration: const BoxDecoration(
                      color: Color(0xFF10B981),
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Text(
                    chatState.detectedTool!,
                    style: TextStyle(fontSize: 11, color: textSecondary),
                  ),
                ],
              ),
          ],
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.terminal, color: textSecondary),
            onPressed: () {
              Navigator.of(context).pushReplacement(
                MaterialPageRoute(
                  builder: (context) =>
                      TerminalScreen(sessionName: widget.sessionName),
                ),
              );
            },
            tooltip: 'Switch to Terminal',
          ),
          if (chatState.isLoading)
            const Padding(
              padding: EdgeInsets.all(12.0),
              child: SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Color(0xFF0369A1),
                ),
              ),
            ),
          IconButton(
            icon: Icon(Icons.delete_outline, color: textSecondary),
            onPressed: () {
              ref.read(chatProvider.notifier).clear();
            },
            tooltip: 'Clear Chat',
          ),
        ],
      ),
      body: Column(
        children: [
          if (chatState.error != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              margin: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.red.shade50,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.red.shade200),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.error_outline,
                    color: Colors.red.shade700,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      chatState.error!,
                      style: TextStyle(
                        color: Colors.red.shade700,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          Expanded(
            child: chatState.messages.isEmpty && !chatState.isLoading
                ? _buildEmptyState()
                : Stack(
                    children: [
                      ListView.builder(
                        controller: _scrollController,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 16,
                        ),
                        itemCount: chatState.messages.length,
                        itemBuilder: (context, index) {
                          final message = chatState.messages[index];
                          final hostsState = ref.watch(hostsProvider);
                          return ProfessionalMessageBubble(
                            message: message,
                            showTimestamp: true,
                            isDarkMode: isDarkMode,
                            baseUrl: hostsState.selectedHost?.httpUrl,
                          );
                        },
                      ),
                      if (_showScrollButton)
                        Positioned(
                          right: 16,
                          bottom: 100,
                          child: AnimatedOpacity(
                            opacity: _showScrollButton ? 1.0 : 0.0,
                            duration: const Duration(milliseconds: 200),
                            child: FloatingActionButton.small(
                              onPressed: () {
                                setState(() {
                                  _autoScroll = true;
                                });
                                _scrollToBottom();
                              },
                              backgroundColor: const Color(0xFF6366F1),
                              child: const Icon(
                                Icons.keyboard_arrow_down,
                                color: Colors.white,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
          ),
          _buildInputArea(),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: isDarkMode
                  ? const Color(0xFF1E293B)
                  : const Color(0xFFE0F2FE),
              shape: BoxShape.circle,
            ),
            child: Icon(
              Icons.smart_toy_outlined,
              size: 48,
              color: isDarkMode
                  ? const Color(0xFF67E8F9)
                  : const Color(0xFF0369A1),
            ),
          ),
          const SizedBox(height: 24),
          Text(
            'No messages yet',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w600,
              color: textSecondary,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Start a conversation with Claude Code or Opencode',
            style: TextStyle(
              fontSize: 14,
              color: isDarkMode ? Colors.grey.shade500 : Colors.grey.shade500,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInputArea() {
    final chatState = ref.watch(chatProvider);
    final isRecording = chatState.isRecording;
    final isTranscribing = chatState.isTranscribing;
    final hasText = _controller.text.trim().isNotEmpty;
    final hasFile = _selectedFile != null;

    return Container(
      decoration: BoxDecoration(
        color: surfaceColor,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 8,
            offset: const Offset(0, -1),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (isRecording || isTranscribing)
                _buildRecordingIndicator(
                  chatState,
                  isRecording,
                  isTranscribing,
                ),
              if (hasFile) _buildSelectedFilePreview(),
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  _buildAttachButton(),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Container(
                      decoration: BoxDecoration(
                        color: isDarkMode
                            ? const Color(0xFF2D3748)
                            : const Color(0xFFEDF2F7),
                        borderRadius: BorderRadius.circular(20),
                      ),
                      child: TextField(
                        controller: _controller,
                        maxLines: 4,
                        minLines: 1,
                        textInputAction: TextInputAction.newline,
                        keyboardType: TextInputType.multiline,
                        decoration: InputDecoration(
                          hintText: isRecording ? 'Recording...' : 'Message',
                          hintStyle: TextStyle(
                            color: isDarkMode
                                ? Colors.grey.shade500
                                : const Color(0xFFA0AEC0),
                            fontSize: 16,
                          ),
                          border: InputBorder.none,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 10,
                          ),
                        ),
                        style: TextStyle(fontSize: 16, color: textPrimary),
                        onChanged: (_) => setState(() {}),
                        onSubmitted: (_) => _sendMessage(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  _buildMicButton(isRecording, isTranscribing),
                  if ((hasText || hasFile) &&
                      !isRecording &&
                      !isTranscribing &&
                      !_isUploading) ...[
                    const SizedBox(width: 8),
                    _buildSendButton(),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRecordingIndicator(
    ChatState chatState,
    bool isRecording,
    bool isTranscribing,
  ) {
    final duration = chatState.recordingDuration;
    final minutes = duration.inMinutes.toString().padLeft(2, '0');
    final seconds = (duration.inSeconds % 60).toString().padLeft(2, '0');

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: isRecording ? Colors.red.shade50 : Colors.blue.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isRecording ? Colors.red.shade200 : Colors.blue.shade200,
        ),
      ),
      child: Row(
        children: [
          if (isRecording) ...[
            Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                color: Colors.red,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 12),
            Text(
              'Recording $minutes:$seconds',
              style: TextStyle(
                color: Colors.red.shade700,
                fontWeight: FontWeight.w500,
              ),
            ),
          ] else if (isTranscribing) ...[
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.blue.shade700,
              ),
            ),
            const SizedBox(width: 12),
            Text(
              'Transcribing...',
              style: TextStyle(
                color: Colors.blue.shade700,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildMicButton(bool isRecording, bool isTranscribing) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: isTranscribing ? null : _handleMicPress,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: isRecording
                ? Colors.red
                : (isTranscribing
                      ? Colors.grey.shade400
                      : (isDarkMode
                            ? const Color(0xFF4A5568)
                            : const Color(0xFFE2E8F0))),
            shape: BoxShape.circle,
          ),
          child: Icon(
            isRecording ? Icons.stop_rounded : Icons.mic,
            size: 22,
            color: isRecording
                ? Colors.white
                : (isTranscribing
                      ? Colors.white
                      : (isDarkMode ? Colors.white : Colors.grey.shade700)),
          ),
        ),
      ),
    );
  }

  Widget _buildSendButton() {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: _sendMessage,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: const BoxDecoration(
            color: Color(0xFF3182CE),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.send_rounded, size: 22, color: Colors.white),
        ),
      ),
    );
  }

  Future<void> _handleMicPress() async {
    final chatState = ref.read(chatProvider);

    if (chatState.isRecording) {
      final audioPath = await ref
          .read(chatProvider.notifier)
          .stopVoiceRecording();
      if (audioPath != null) {
        await ref.read(chatProvider.notifier).transcribeAudio(audioPath);
      }
    } else {
      final status = await Permission.microphone.request();
      if (status.isGranted) {
        await ref.read(chatProvider.notifier).startVoiceRecording();
      } else if (status.isPermanentlyDenied) {
        _showPermissionDeniedDialog();
      }
    }
  }

  void _showPermissionDeniedDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Microphone Permission'),
        content: const Text(
          'Microphone permission is required to use voice input. '
          'Please enable it in your device settings.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }
}
