import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:flutter_highlight/flutter_highlight.dart';
import 'package:flutter_highlight/themes/atom-one-dark.dart';
import 'package:flutter_highlight/themes/atom-one-light.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:dio/dio.dart';
import 'package:just_audio/just_audio.dart';
import 'package:open_file/open_file.dart';
import 'package:path_provider/path_provider.dart';
import '../../../data/models/chat_message.dart';
import 'chat_audio_tile.dart';

class ProfessionalMessageBubble extends StatefulWidget {
  final ChatMessage message;
  final bool showTimestamp;
  final bool isDarkMode;
  final String? baseUrl;

  const ProfessionalMessageBubble({
    super.key,
    required this.message,
    this.showTimestamp = true,
    this.isDarkMode = false,
    this.baseUrl,
  });

  @override
  State<ProfessionalMessageBubble> createState() =>
      _ProfessionalMessageBubbleState();
}

class _ProfessionalMessageBubbleState extends State<ProfessionalMessageBubble>
    with SingleTickerProviderStateMixin {
  late AnimationController _animationController;
  late Animation<double> _fadeAnimation;
  late Animation<Offset> _slideAnimation;
  final AudioPlayer _audioPlayer = AudioPlayer();
  final Map<String, String> _audioCachePaths = {};
  StreamSubscription<PlayerState>? _playerStateSubscription;
  StreamSubscription<Duration?>? _durationSubscription;
  StreamSubscription<Duration>? _positionSubscription;
  StreamSubscription<Duration>? _bufferedPositionSubscription;

  String? _playingBlockId;
  String? _audioErrorBlockId;
  Duration _audioPosition = Duration.zero;
  Duration _audioBufferedPosition = Duration.zero;
  Duration? _audioDuration;
  String? _audioErrorMessage;
  bool _isAudioPlaying = false;
  bool _isAudioLoading = false;
  bool _isAudioCompleted = false;

  bool get isDark => widget.isDarkMode;

  @override
  void initState() {
    super.initState();
    _animationController = AnimationController(
      duration: const Duration(milliseconds: 300),
      vsync: this,
    );
    _fadeAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.easeOut),
    );
    _slideAnimation =
        Tween<Offset>(begin: const Offset(0, 0.1), end: Offset.zero).animate(
          CurvedAnimation(
            parent: _animationController,
            curve: Curves.easeOutCubic,
          ),
        );
    _animationController.forward();

    _playerStateSubscription = _audioPlayer.playerStateStream.listen((state) {
      if (!mounted) return;
      setState(() {
        _isAudioPlaying = state.playing;
        _isAudioLoading =
            state.processingState == ProcessingState.loading ||
            state.processingState == ProcessingState.buffering;
        if (state.processingState == ProcessingState.completed) {
          _isAudioCompleted = true;
          if (_audioDuration != null) {
            _audioPosition = _audioDuration!;
          }
        } else if (state.processingState == ProcessingState.ready) {
          _isAudioCompleted = false;
        }
      });
    });

    _durationSubscription = _audioPlayer.durationStream.listen((duration) {
      if (!mounted || duration == null) return;
      setState(() {
        _audioDuration = duration;
      });
    });

    _positionSubscription = _audioPlayer.positionStream.listen((position) {
      if (!mounted) return;
      setState(() {
        _audioPosition = position;
      });
    });

    _bufferedPositionSubscription = _audioPlayer.bufferedPositionStream.listen((
      buffered,
    ) {
      if (!mounted) return;
      setState(() {
        _audioBufferedPosition = buffered;
      });
    });
  }

  @override
  void dispose() {
    _animationController.dispose();
    _playerStateSubscription?.cancel();
    _durationSubscription?.cancel();
    _positionSubscription?.cancel();
    _bufferedPositionSubscription?.cancel();
    _audioPlayer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isUser = widget.message.type == ChatMessageType.user;
    final isError = widget.message.type == ChatMessageType.error;
    final isTool =
        widget.message.type == ChatMessageType.tool ||
        widget.message.type == ChatMessageType.toolCall ||
        widget.message.type == ChatMessageType.toolResult;

    return FadeTransition(
      opacity: _fadeAnimation,
      child: SlideTransition(
        position: _slideAnimation,
        child: Align(
          alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            constraints: BoxConstraints(
              maxWidth: MediaQuery.of(context).size.width * 0.82,
            ),
            margin: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
            child: Column(
              crossAxisAlignment: isUser
                  ? CrossAxisAlignment.end
                  : CrossAxisAlignment.start,
              children: [
                _buildHeader(isUser, isError, isTool),
                const SizedBox(height: 6),
                _buildContent(isUser, isError, isTool),
                if (widget.showTimestamp) ...[
                  const SizedBox(height: 4),
                  _buildTimestamp(isUser),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(bool isUser, bool isError, bool isTool) {
    final Color headerColor;
    final String label;
    final IconData icon;

    if (isUser) {
      headerColor = isDark ? const Color(0xFF7DD3FC) : const Color(0xFF0369A1);
      label = 'You';
      icon = Icons.person;
    } else if (isError) {
      headerColor = Colors.red.shade400;
      label = 'Error';
      icon = Icons.error_outline;
    } else if (isTool) {
      headerColor = isDark ? const Color(0xFF94A3B8) : const Color(0xFF64748B);
      label = widget.message.toolName ?? 'Tool';
      icon = Icons.build;
    } else {
      headerColor = isDark ? const Color(0xFF6EE7B7) : const Color(0xFF047857);
      label = 'Assistant';
      icon = Icons.smart_toy;
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (!isUser) ...[
          Icon(icon, size: 14, color: headerColor),
          const SizedBox(width: 4),
        ],
        Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: headerColor,
          ),
        ),
        if (isUser) ...[
          const SizedBox(width: 4),
          Icon(icon, size: 14, color: headerColor),
        ],
      ],
    );
  }

  Widget _buildContent(bool isUser, bool isError, bool isTool) {
    Color bubbleColor;
    Color textColor;
    Color borderColor;

    if (isUser) {
      bubbleColor = isDark ? const Color(0xFF0C4A6E) : const Color(0xFF0369A1);
      textColor = Colors.white;
      borderColor = Colors.transparent;
    } else if (isError) {
      bubbleColor = isDark ? Colors.red.shade900 : Colors.red.shade50;
      textColor = isDark ? Colors.red.shade100 : Colors.red.shade900;
      borderColor = isDark ? Colors.red.shade700 : Colors.red.shade200;
    } else if (isTool) {
      bubbleColor = Colors.transparent;
      textColor = isDark ? Colors.grey.shade300 : Colors.grey.shade800;
      borderColor = Colors.transparent;
    } else {
      bubbleColor = isDark ? const Color(0xFF1E293B) : Colors.white;
      textColor = isDark ? Colors.grey.shade100 : const Color(0xFF1E293B);
      borderColor = isDark ? const Color(0xFF334155) : const Color(0xFFE2E8F0);
    }

    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      decoration: BoxDecoration(
        color: bubbleColor,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(18),
          topRight: const Radius.circular(18),
          bottomLeft: Radius.circular(isUser ? 18 : 4),
          bottomRight: Radius.circular(isUser ? 4 : 18),
        ),
        border: (isUser || isTool)
            ? null
            : Border.all(color: borderColor, width: 1),
        boxShadow: isUser
            ? [
                BoxShadow(
                  color: const Color(0xFF0369A1).withValues(alpha: 0.35),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ]
            : null,
      ),
      padding: isTool
          ? const EdgeInsets.symmetric(vertical: 2)
          : const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: _buildMessageContent(isUser, isError, isTool, textColor),
    );
  }

  Widget _buildMessageContent(
    bool isUser,
    bool isError,
    bool isTool,
    Color textColor,
  ) {
    if (widget.message.blocks.isNotEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: widget.message.blocks.map((block) {
          switch (block.type) {
            case ChatBlockType.text:
              return _buildMarkdownBlock(block.text ?? '', textColor, isUser);
            case ChatBlockType.thinking:
              return _buildThinkingBlock(block.content ?? '', textColor);
            case ChatBlockType.toolCall:
              return _buildToolCallCard(block);
            case ChatBlockType.toolResult:
              return _buildToolResultCard(block);
            case ChatBlockType.image:
              return _buildImageBlock(block, textColor);
            case ChatBlockType.audio:
              return _buildAudioBlock(block, textColor);
            case ChatBlockType.file:
              return _buildFileBlock(block, textColor);
          }
        }).toList(),
      );
    }

    final content = widget.message.content ?? '';
    if (isUser || isError || isTool) {
      return SelectableText(
        content,
        style: TextStyle(color: textColor, fontSize: 14, height: 1.5),
      );
    }

    return _buildMarkdownBlock(content, textColor, isUser);
  }

  Widget _buildMarkdownBlock(String text, Color textColor, bool isUser) {
    if (text.isEmpty) return const SizedBox.shrink();

    return MarkdownBody(
      data: text,
      selectable: true,
      builders: {'pre': CodeBlockBuilder(isUser: isUser, isDark: isDark)},
      styleSheet: MarkdownStyleSheet(
        p: TextStyle(color: textColor, fontSize: 14, height: 1.6),
        h1: TextStyle(
          color: textColor,
          fontSize: 20,
          fontWeight: FontWeight.bold,
        ),
        h2: TextStyle(
          color: textColor,
          fontSize: 18,
          fontWeight: FontWeight.bold,
        ),
        h3: TextStyle(
          color: textColor,
          fontSize: 16,
          fontWeight: FontWeight.bold,
        ),
        h4: TextStyle(
          color: textColor,
          fontSize: 15,
          fontWeight: FontWeight.bold,
        ),
        h5: TextStyle(
          color: textColor,
          fontSize: 14,
          fontWeight: FontWeight.bold,
        ),
        h6: TextStyle(
          color: textColor,
          fontSize: 13,
          fontWeight: FontWeight.bold,
        ),
        code: TextStyle(
          backgroundColor: isDark
              ? Colors.white.withValues(alpha: 0.1)
              : Colors.grey.shade200,
          color: isDark ? const Color(0xFF67E8F9) : const Color(0xFF0369A1),
          fontFamily: 'monospace',
          fontSize: 13,
        ),
        codeblockDecoration: const BoxDecoration(color: Colors.transparent),
        codeblockPadding: EdgeInsets.zero,
        blockquote: TextStyle(
          color: textColor.withValues(alpha: 0.8),
          fontStyle: FontStyle.italic,
        ),
        blockquoteDecoration: BoxDecoration(
          border: Border(
            left: BorderSide(color: textColor.withValues(alpha: 0.3), width: 3),
          ),
        ),
        blockquotePadding: const EdgeInsets.only(left: 12),
        listBullet: TextStyle(color: textColor),
        tableHead: TextStyle(color: textColor, fontWeight: FontWeight.bold),
        tableBody: TextStyle(color: textColor),
        tableBorder: TableBorder.all(
          color: textColor.withValues(alpha: 0.2),
          width: 1,
        ),
        horizontalRuleDecoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: textColor.withValues(alpha: 0.2), width: 1),
          ),
        ),
        strong: TextStyle(color: textColor, fontWeight: FontWeight.bold),
        em: TextStyle(color: textColor, fontStyle: FontStyle.italic),
        a: TextStyle(
          color: isDark ? const Color(0xFF67E8F9) : const Color(0xFF0369A1),
          decoration: TextDecoration.underline,
        ),
      ),
    );
  }

  Widget _buildToolCallCard(ChatBlock block) {
    final toolIcon = _getToolIcon(block.toolName ?? '');
    final toolName = block.toolName ?? 'Unknown Tool';
    final summary = block.summary ?? '';

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: _CollapsibleToolCard(
        icon: toolIcon,
        title: toolName,
        summary: summary,
        isDark: isDark,
        child: block.input != null
            ? Container(
                width: double.infinity,
                margin: const EdgeInsets.only(top: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: isDark
                      ? Colors.black26
                      : Colors.black.withValues(alpha: 0.05),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  _formatJson(block.input!),
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: isDark ? Colors.grey.shade300 : Colors.black87,
                  ),
                ),
              )
            : null,
      ),
    );
  }

  Widget _buildToolResultCard(ChatBlock block) {
    final content = block.content ?? '';
    final summary = block.summary ?? '';
    final toolName = block.toolName ?? '';
    final title = toolName.isNotEmpty ? 'Result • $toolName' : 'Result';

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: _CollapsibleToolCard(
        icon: '📄',
        title: title,
        summary: summary.isNotEmpty ? summary : content.take(50),
        isDark: isDark,
        child: content.isNotEmpty
            ? Container(
                width: double.infinity,
                margin: const EdgeInsets.only(top: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: isDark
                      ? Colors.black26
                      : Colors.black.withValues(alpha: 0.05),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  content,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: isDark ? Colors.grey.shade300 : Colors.black87,
                  ),
                ),
              )
            : null,
      ),
    );
  }

  Widget _buildImageBlock(ChatBlock block, Color textColor) {
    final imageUrl = widget.baseUrl != null && block.id != null
        ? '${widget.baseUrl}/api/chat/files/${block.id}'
        : null;

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: GestureDetector(
        onTap: () => _showImageFullScreen(block.id),
        child: Hero(
          tag: 'image_${block.id}',
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Container(
              constraints: const BoxConstraints(maxWidth: 200, maxHeight: 200),
              color: isDark ? Colors.grey[800] : Colors.grey[200],
              child: imageUrl != null
                  ? CachedNetworkImage(
                      imageUrl: imageUrl,
                      fit: BoxFit.cover,
                      width: 200,
                      height: 200,
                      placeholder: (context, url) => Center(
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: isDark ? Colors.grey[600] : Colors.grey[400],
                        ),
                      ),
                      errorWidget: (context, url, error) => Center(
                        child: Icon(
                          Icons.broken_image,
                          size: 48,
                          color: isDark ? Colors.grey[600] : Colors.grey[400],
                        ),
                      ),
                    )
                  : Center(
                      child: Icon(
                        Icons.image,
                        size: 48,
                        color: isDark ? Colors.grey[600] : Colors.grey[400],
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }

  void _showImageFullScreen(String? imageId) {
    if (imageId == null) return;

    final imageUrl = widget.baseUrl != null
        ? '${widget.baseUrl}/api/chat/files/$imageId'
        : null;

    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (context) => Scaffold(
          backgroundColor: Colors.black,
          appBar: AppBar(
            backgroundColor: Colors.transparent,
            iconTheme: const IconThemeData(color: Colors.white),
          ),
          body: Center(
            child: Hero(
              tag: 'image_$imageId',
              child: imageUrl != null
                  ? CachedNetworkImage(
                      imageUrl: imageUrl,
                      fit: BoxFit.contain,
                      placeholder: (context, url) =>
                          const CircularProgressIndicator(),
                      errorWidget: (context, url, error) => Icon(
                        Icons.broken_image,
                        size: 200,
                        color: Colors.grey[700],
                      ),
                    )
                  : InteractiveViewer(
                      child: Icon(
                        Icons.image,
                        size: 200,
                        color: Colors.grey[700],
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildAudioBlock(ChatBlock block, Color textColor) {
    final blockId = block.id;
    final audioUrl = widget.baseUrl != null && block.id != null
        ? '${widget.baseUrl}/api/chat/files/${block.id}'
        : null;
    final isActiveBlock = blockId != null && _playingBlockId == blockId;
    final fallbackDuration = _durationFromSeconds(block.durationSeconds);
    final effectiveDuration = isActiveBlock
        ? (_audioDuration ?? fallbackDuration)
        : fallbackDuration;
    final inlineErrorVisible =
        blockId != null &&
        blockId == _audioErrorBlockId &&
        _audioErrorMessage != null &&
        !_isAudioLoading;
    final inlineMessage = isActiveBlock && _isAudioLoading
        ? 'Loading audio...'
        : (inlineErrorVisible ? _audioErrorMessage : null);

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: ChatAudioTile(
        title: block.filename ?? 'Audio',
        textColor: textColor,
        isDark: isDark,
        isActive: isActiveBlock,
        isPlaying: _isAudioPlaying,
        isLoading: _isAudioLoading,
        isCompleted: _isAudioCompleted,
        position: isActiveBlock ? _audioPosition : Duration.zero,
        bufferedPosition: isActiveBlock
            ? _audioBufferedPosition
            : Duration.zero,
        totalDuration: effectiveDuration,
        inlineMessage: inlineMessage,
        inlineMessageIsError: inlineErrorVisible,
        onPrimaryPressed: audioUrl != null && blockId != null
            ? () => _onAudioButtonPressed(blockId, audioUrl)
            : null,
        onSeek: isActiveBlock ? _seekAudio : null,
        onSkipBackward: isActiveBlock
            ? () => _skipAudioBy(const Duration(seconds: -10))
            : null,
        onSkipForward: isActiveBlock
            ? () => _skipAudioBy(const Duration(seconds: 10))
            : null,
      ),
    );
  }

  Future<void> _onAudioButtonPressed(String blockId, String url) async {
    try {
      if (_isAudioLoading) return;

      if (_playingBlockId != blockId) {
        await _loadAudioSource(blockId, url);
        await _audioPlayer.play();
        return;
      }

      if (_isAudioPlaying) {
        await _audioPlayer.pause();
        return;
      }

      if (_isAudioCompleted) {
        await _audioPlayer.seek(Duration.zero);
        if (mounted) {
          setState(() {
            _audioPosition = Duration.zero;
            _isAudioCompleted = false;
          });
        }
      }

      await _audioPlayer.play();
    } catch (e) {
      debugPrint('Audio error: $e');
      if (mounted) {
        setState(() {
          _audioErrorMessage = '$e';
          _audioErrorBlockId = blockId;
        });
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Failed to play audio: $e')));
      }
    }
  }

  Future<void> _loadAudioSource(String blockId, String url) async {
    if (mounted) {
      setState(() {
        _isAudioLoading = true;
        _isAudioCompleted = false;
        _audioPosition = Duration.zero;
        _audioBufferedPosition = Duration.zero;
        _audioDuration = null;
        _audioErrorMessage = null;
        _audioErrorBlockId = null;
      });
    }

    try {
      Duration? duration;
      debugPrint('Audio URL: $url');
      try {
        duration = await _audioPlayer.setUrl(url);
      } catch (streamError) {
        debugPrint(
          'Streaming audio failed, trying local fallback: $streamError',
        );
        final localPath = await _downloadAudioToTemp(blockId, url);
        duration = await _audioPlayer.setFilePath(localPath);
      }

      if (!mounted) return;
      setState(() {
        _playingBlockId = blockId;
        _audioDuration = duration ?? _audioPlayer.duration;
        _audioErrorMessage = null;
        _audioErrorBlockId = null;
      });
    } finally {
      if (mounted) {
        setState(() {
          _isAudioLoading = false;
        });
      }
    }
  }

  Future<void> _seekAudio(Duration position) async {
    final totalDuration = _audioDuration;
    final safePosition = _clampDuration(position, totalDuration);
    await _audioPlayer.seek(safePosition);
    if (!mounted) return;
    setState(() {
      _audioPosition = safePosition;
      if (totalDuration != null && safePosition < totalDuration) {
        _isAudioCompleted = false;
      }
    });
  }

  Future<void> _skipAudioBy(Duration delta) async {
    if (_playingBlockId == null || _audioDuration == null) return;
    await _seekAudio(_audioPosition + delta);
  }

  Future<String> _downloadAudioToTemp(String blockId, String url) async {
    final cachedPath = _audioCachePaths[blockId];
    if (cachedPath != null && await File(cachedPath).exists()) {
      return cachedPath;
    }

    final tempDir = await getTemporaryDirectory();
    final filePath = '${tempDir.path}/chat_audio_$blockId.bin';
    await Dio().download(url, filePath, deleteOnError: true);
    _audioCachePaths[blockId] = filePath;
    return filePath;
  }

  Duration? _durationFromSeconds(double? seconds) {
    if (seconds == null || seconds <= 0) return null;
    return Duration(milliseconds: (seconds * 1000).round());
  }

  Duration _clampDuration(Duration value, Duration? max) {
    if (value < Duration.zero) return Duration.zero;
    if (max == null) return value;
    if (value > max) return max;
    return value;
  }

  Widget _buildFileBlock(ChatBlock block, Color textColor) {
    final fileUrl = widget.baseUrl != null && block.id != null
        ? '${widget.baseUrl}/api/chat/files/${block.id}'
        : null;

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: InkWell(
        onTap: () => _downloadAndOpenFile(block, fileUrl),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1E293B) : const Color(0xFFF1F5F9),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isDark ? const Color(0xFF334155) : const Color(0xFFE2E8F0),
            ),
          ),
          padding: const EdgeInsets.all(12),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                _getFileIcon(block.mimeType),
                size: 32,
                color: isDark
                    ? const Color(0xFF6EE7B7)
                    : const Color(0xFF047857),
              ),
              const SizedBox(width: 12),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    block.filename ?? 'File',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: textColor,
                    ),
                  ),
                  if (block.sizeBytes != null)
                    Text(
                      _formatFileSize(block.sizeBytes!),
                      style: TextStyle(
                        fontSize: 11,
                        color: textColor.withValues(alpha: 0.6),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 8),
              Icon(
                Icons.download,
                size: 18,
                color: textColor.withValues(alpha: 0.6),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _downloadAndOpenFile(ChatBlock block, String? fileUrl) async {
    if (fileUrl == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Cannot download file')));
      return;
    }

    final scaffoldMessenger = ScaffoldMessenger.of(context);
    try {
      scaffoldMessenger.showSnackBar(
        SnackBar(content: Text('Downloading ${block.filename ?? 'file'}...')),
      );

      final dio = Dio();
      final cacheDir = await getTemporaryDirectory();
      final downloadsDir = Directory('${cacheDir.path}/chat_downloads');

      if (!await downloadsDir.exists()) {
        await downloadsDir.create(recursive: true);
      }

      final filename = _sanitizeFilename(block.filename ?? 'file');
      final filePath = '${downloadsDir.path}/$filename';
      final file = File(filePath);

      if (await file.exists()) {
        await file.delete();
      }

      await dio.download(fileUrl, filePath);

      if (!mounted) return;

      scaffoldMessenger.hideCurrentSnackBar();
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text('Downloaded. Opening...'),
          duration: const Duration(seconds: 1),
        ),
      );

      final openResult = await OpenFile.open(filePath, type: block.mimeType);
      if (openResult.type != ResultType.done) {
        scaffoldMessenger.showSnackBar(
          SnackBar(
            content: Text(
              'Downloaded, but could not open (${openResult.message}). File: $filename',
            ),
          ),
        );
      }
    } catch (e) {
      scaffoldMessenger.showSnackBar(
        SnackBar(content: Text('Failed to download: $e')),
      );
    }
  }

  String _sanitizeFilename(String filename) {
    final trimmed = filename.trim();
    final fallback = trimmed.isEmpty ? 'file' : trimmed;
    return fallback.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
  }

  IconData _getFileIcon(String? mimeType) {
    if (mimeType == null) return Icons.insert_drive_file;
    if (mimeType.contains('pdf')) return Icons.picture_as_pdf;
    if (mimeType.contains('word') || mimeType.contains('document')) {
      return Icons.description;
    }
    if (mimeType.contains('excel') || mimeType.contains('spreadsheet')) {
      return Icons.table_chart;
    }
    if (mimeType.contains('zip') || mimeType.contains('archive')) {
      return Icons.folder_zip;
    }
    if (mimeType.contains('text')) return Icons.text_snippet;
    return Icons.insert_drive_file;
  }

  String _formatFileSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  Widget _buildThinkingBlock(String content, Color textColor) {
    if (content.isEmpty) return const SizedBox.shrink();

    final thinkingColor = isDark
        ? const Color(0xFFFBBF24)
        : const Color(0xFFD97706);
    final bgColor = isDark ? const Color(0xFF422006) : const Color(0xFFFEF3C7);
    final borderColor = isDark
        ? const Color(0xFF78350F)
        : const Color(0xFFFDE68A);

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Container(
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: borderColor, width: 1),
        ),
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.psychology, size: 14, color: thinkingColor),
                const SizedBox(width: 6),
                Text(
                  'Thinking',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: thinkingColor,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            SelectableText(
              content,
              style: TextStyle(
                fontSize: 12,
                color: textColor.withValues(alpha: 0.9),
                height: 1.5,
                fontFamily: 'monospace',
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTimestamp(bool isUser) {
    final time = widget.message.timestamp;
    final formattedTime =
        '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';

    return Text(
      formattedTime,
      style: TextStyle(
        fontSize: 10,
        color: isUser
            ? Colors.white.withValues(alpha: 0.7)
            : isDark
            ? Colors.grey.shade500
            : Colors.grey.shade500,
      ),
    );
  }

  String _getToolIcon(String toolName) {
    const icons = {
      'Read': '📄',
      'Edit': '✏️',
      'Write': '📝',
      'Bash': '💻',
      'Glob': '🔍',
      'Grep': '🔍',
      'Task': '🤖',
      'TaskCreate': '🤖',
      'TaskUpdate': '🤖',
      'TaskList': '🤖',
      'TaskGet': '🤖',
      'WebSearch': '🌐',
      'WebFetch': '🌐',
    };
    return icons[toolName] ?? '🔧';
  }

  String _formatJson(Map<String, dynamic> json) {
    const encoder = JsonEncoder.withIndent('  ');
    return encoder.convert(json);
  }
}

class _CollapsibleToolCard extends StatefulWidget {
  final String icon;
  final String title;
  final String summary;
  final bool isDark;
  final Widget? child;

  const _CollapsibleToolCard({
    required this.icon,
    required this.title,
    required this.summary,
    required this.isDark,
    this.child,
  });

  @override
  State<_CollapsibleToolCard> createState() => _CollapsibleToolCardState();
}

class _CollapsibleToolCardState extends State<_CollapsibleToolCard>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _expandAnimation;
  bool _isExpanded = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 200),
      vsync: this,
    );
    _expandAnimation = CurvedAnimation(
      parent: _controller,
      curve: Curves.easeInOut,
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final borderColor = widget.isDark
        ? const Color(0xFF1E293B)
        : const Color(0xFFE2E8F0);
    final bgColor = widget.isDark
        ? const Color(0xFF0F172A)
        : const Color(0xFFF8FAFC);
    final textColor = widget.isDark
        ? const Color(0xFF94A3B8)
        : const Color(0xFF475569);
    final hasDetails = widget.child != null;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: hasDetails
            ? () {
                setState(() => _isExpanded = !_isExpanded);
                if (_isExpanded) {
                  _controller.forward();
                } else {
                  _controller.reverse();
                }
              }
            : null,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          decoration: BoxDecoration(
            border: Border.all(color: borderColor),
            borderRadius: BorderRadius.circular(12),
            color: bgColor,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                child: Row(
                  children: [
                    Text(widget.icon, style: const TextStyle(fontSize: 14)),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.title,
                            style: TextStyle(
                              fontWeight: FontWeight.w700,
                              fontSize: 13,
                              color: textColor,
                            ),
                          ),
                          if (widget.summary.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text(
                                widget.summary,
                                maxLines: _isExpanded ? 8 : 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 12,
                                  color: textColor.withValues(alpha: 0.85),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    if (hasDetails)
                      AnimatedRotation(
                        turns: _isExpanded ? 0.25 : 0,
                        duration: const Duration(milliseconds: 200),
                        child: Icon(
                          Icons.chevron_right,
                          size: 18,
                          color: textColor,
                        ),
                      ),
                  ],
                ),
              ),
              SizeTransition(
                sizeFactor: _expandAnimation,
                child: widget.child ?? const SizedBox.shrink(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class CodeBlockBuilder extends MarkdownElementBuilder {
  final bool isUser;
  final bool isDark;

  CodeBlockBuilder({this.isUser = false, this.isDark = false});

  @override
  Widget? visitElementAfter(md.Element element, preferredStyle) {
    final code = element.textContent;
    var language = '';

    // In a 'pre' tag, the language class is usually on its 'code' child
    if (element.children != null && element.children!.isNotEmpty) {
      final child = element.children!.first;
      if (child is md.Element && child.attributes['class'] != null) {
        language = child.attributes['class']!.replaceFirst('language-', '');
      }
    }

    if (language.isEmpty && element.attributes['class'] != null) {
      language = element.attributes['class']!.replaceFirst('language-', '');
    }

    final codeColor = isDark ? Colors.grey.shade300 : Colors.grey.shade800;
    final hasLanguage = language.isNotEmpty;

    final Map<String, TextStyle> customTheme = Map.from(
      isDark ? atomOneDarkTheme : atomOneLightTheme,
    );
    customTheme['root'] = TextStyle(
      color: customTheme['root']?.color,
      backgroundColor: Colors.transparent,
    );

    if (hasLanguage) {
      // Single container for entire code block - no separate header
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(
          color: isDark ? const Color(0xFF282C34) : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            // Header row with language and copy button
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF21252B) : Colors.grey.shade200,
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(8),
                ),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    language,
                    style: TextStyle(
                      color: isDark
                          ? Colors.grey.shade400
                          : Colors.grey.shade600,
                      fontSize: 11,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  GestureDetector(
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: code));
                    },
                    child: Row(
                      children: [
                        Icon(
                          Icons.copy,
                          size: 14,
                          color: isDark
                              ? Colors.grey.shade400
                              : Colors.grey.shade600,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          'Copy',
                          style: TextStyle(
                            color: isDark
                                ? Colors.grey.shade400
                                : Colors.grey.shade600,
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            // Code content - no additional decoration or border
            Container(
              padding: const EdgeInsets.all(12),
              child: HighlightView(
                code.trimRight(),
                language: _mapLanguage(language),
                theme: customTheme,
                padding: EdgeInsets.zero,
                textStyle: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 13,
                  height: 1.5,
                ),
              ),
            ),
          ],
        ),
      );
    }

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isDark
            ? Colors.grey.shade800.withValues(alpha: 0.3)
            : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(8),
      ),
      child: SelectableText(
        code.trimRight(),
        style: TextStyle(
          fontFamily: 'monospace',
          fontSize: 13,
          color: codeColor,
          height: 1.5,
        ),
      ),
    );
  }

  String _mapLanguage(String lang) {
    final languageMap = {
      'js': 'javascript',
      'ts': 'typescript',
      'py': 'python',
      'rb': 'ruby',
      'yml': 'yaml',
      'sh': 'bash',
      'shell': 'bash',
      'zsh': 'bash',
      'md': 'markdown',
      'dockerfile': 'dockerfile',
      'html': 'xml',
      'css': 'css',
      'json': 'json',
      'sql': 'sql',
      'go': 'go',
      'rs': 'rust',
      'swift': 'swift',
      'kt': 'kotlin',
      'java': 'java',
      'c': 'c',
      'cpp': 'cpp',
      'csharp': 'cs',
      'php': 'php',
      'r': 'r',
      'scala': 'scala',
      'hcl': 'hcl',
    };
    return languageMap[lang.toLowerCase()] ?? lang.toLowerCase();
  }
}

extension StringExtension on String {
  String take(int count) {
    if (length <= count) return this;
    return '${substring(0, count)}...';
  }
}
