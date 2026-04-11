import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:just_audio/just_audio.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';
import '../../../data/models/chat_message.dart';
import '../../../data/services/websocket_service.dart';
import '../../../data/services/audio_service.dart';
import '../../../data/services/whisper_service.dart';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../sessions/providers/sessions_provider.dart';

// Sentinel value used in copyWith to distinguish "not provided" from "explicitly null"
const _sentinel = Object();

class PendingPermission {
  final String requestId;
  final String tool;
  final String command;
  final List<Map<String, dynamic>> options;

  const PendingPermission({
    required this.requestId,
    required this.tool,
    required this.command,
    required this.options,
  });
}

class ChatState {
  final List<ChatMessage> messages;
  final bool isLoading;
  final PendingPermission? pendingPermission;
  final bool isLoadingMore;
  final String? error;
  final String? detectedTool;
  final String? sessionName;
  final int? windowIndex;
  final bool isAcp;
  final bool isRecording;
  final Duration recordingDuration;
  final bool isTranscribing;
  final String? transcribedText;
  final int? totalMessageCount;
  final bool hasMoreMessages;
  final bool showThinking;
  final bool showToolCalls;
  final String acpSessionCwd;

  const ChatState({
    this.messages = const [],
    this.isLoading = false,
    this.pendingPermission,
    this.isLoadingMore = false,
    this.error,
    this.detectedTool,
    this.sessionName,
    this.windowIndex,
    this.isAcp = false,
    this.isRecording = false,
    this.recordingDuration = Duration.zero,
    this.isTranscribing = false,
    this.transcribedText,
    this.totalMessageCount,
    this.hasMoreMessages = false,
    this.showThinking = true,
    this.showToolCalls = true,
    this.acpSessionCwd = '',
  });

  ChatState copyWith({
    List<ChatMessage>? messages,
    bool? isLoading,
    bool? isLoadingMore,
    Object? pendingPermission = _sentinel,
    String? error,
    String? detectedTool,
    String? sessionName,
    int? windowIndex,
    bool? isAcp,
    bool? isRecording,
    Duration? recordingDuration,
    bool? isTranscribing,
    String? transcribedText,
    int? totalMessageCount,
    bool? hasMoreMessages,
    bool? showThinking,
    bool? showToolCalls,
    String? acpSessionCwd,
  }) {
    return ChatState(
      messages: messages ?? this.messages,
      isLoading: isLoading ?? this.isLoading,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      pendingPermission: pendingPermission == _sentinel
          ? this.pendingPermission
          : pendingPermission as PendingPermission?,
      error: error,
      detectedTool: detectedTool ?? this.detectedTool,
      sessionName: sessionName ?? this.sessionName,
      windowIndex: windowIndex ?? this.windowIndex,
      isAcp: isAcp ?? this.isAcp,
      isRecording: isRecording ?? this.isRecording,
      recordingDuration: recordingDuration ?? this.recordingDuration,
      isTranscribing: isTranscribing ?? this.isTranscribing,
      transcribedText: transcribedText,
      totalMessageCount: totalMessageCount ?? this.totalMessageCount,
      hasMoreMessages: hasMoreMessages ?? this.hasMoreMessages,
      showThinking: showThinking ?? this.showThinking,
      showToolCalls: showToolCalls ?? this.showToolCalls,
      acpSessionCwd: acpSessionCwd ?? this.acpSessionCwd,
    );
  }
}

class ChatNotifier extends StateNotifier<ChatState> {
  final Uuid _uuid = const Uuid();
  StreamSubscription? _messageSubscription;
  StreamSubscription? _connectionSubscription;
  WebSocketService? _ws;
  final AudioService _audioService = AudioService();
  final WhisperService _whisperService = WhisperService();
  Timer? _recordingTimer;
  SharedPreferences? _prefs;

  ChatNotifier() : super(const ChatState());

  void setPrefs(SharedPreferences prefs) {
    _prefs = prefs;
    _loadDisplaySettings();
  }

  void _loadDisplaySettings() {
    if (_prefs == null) return;
    final showThinking =
        _prefs!.getBool(AppConfig.keyShowThinking) ??
        AppConfig.defaultShowThinking;
    final showToolCalls =
        _prefs!.getBool(AppConfig.keyShowToolCalls) ??
        AppConfig.defaultShowToolCalls;
    state = state.copyWith(
      showThinking: showThinking,
      showToolCalls: showToolCalls,
    );
  }

  void refreshDisplaySettings() {
    _loadDisplaySettings();
  }

  void setWebSocket(WebSocketService ws) {
    _ws = ws;
    _listenToMessages();
    _connectionSubscription?.cancel();
    _connectionSubscription = ws.connectionState.listen((connected) {
      if (connected && state.isAcp && state.sessionName != null) {
        final rawId = state.sessionName!.replaceFirst('acp_', '');
        _ws!.watchAcpChatLog(rawId, limit: 30);
      }
    });
  }

  void _listenToMessages() {
    _messageSubscription?.cancel();
    if (_ws == null) return;

    _messageSubscription = _ws!.messages.listen((message) {
      final type = message['type'] as String?;
      // print('DEBUG: Received message of type: $type');

      if (type == 'chat-history' || type == 'chat-event') {
        // print('DEBUG: Full chat message: $message');
      }

      switch (type) {
        case 'chat-history':
          _handleChatHistory(message);
          break;
        case 'chat-event':
          _handleChatEvent(message);
          break;
        case 'chat-file-message':
          _handleChatFileMessage(message);
          break;
        case 'chat-notification':
          _handleChatNotification(message);
          break;
        case 'chat-log-error':
          _handleChatError(message);
          break;
        case 'chat-log-cleared':
          _handleChatLogCleared(message);
          break;
        case 'acp-message-chunk':
          _handleAcpMessageChunk(message);
          break;
        case 'acp-tool-call':
          _handleAcpToolCall(message);
          break;
        case 'acp-tool-result':
          _handleAcpToolResult(message);
          break;
        case 'acp-permission-request':
          _handleAcpPermissionRequest(message);
          break;
        case 'acp-prompt-done':
          _handleAcpPromptDone(message);
          break;
        case 'acp-error':
          state = state.copyWith(error: message['message']?.toString());
          break;
        case 'chat-history-chunk':
          _handleChatHistoryChunk(message);
          break;
        case 'acp-history-loaded':
          _handleAcpHistoryLoaded(message);
          break;
      }
    });
  }

  void _handleAcpHistoryLoaded(Map<String, dynamic> message) {
    try {
      final sessionId = message['sessionId'] as String?;
      if (sessionId == null || !_matchesAcpSession(sessionId)) return;

      final messagesData = message['messages'] as List<dynamic>? ?? [];
      final hasMore = message['hasMore'] as bool? ?? false;

      final messages = messagesData
          .map((msg) => _parseMessage(msg as Map<String, dynamic>))
          .toList();

      state = state.copyWith(
        messages: messages,
        isLoading: false,
        hasMoreMessages: hasMore,
      );
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: 'Failed to parse history',
      );
    }
  }

  void _handleChatHistory(Map<String, dynamic> message) {
    try {
      final sessionName =
          (message['sessionName'] ?? message['session-name']) as String?;
      final windowIndexRaw = message['windowIndex'] ?? message['window-index'];
      final windowIndex = windowIndexRaw is num ? windowIndexRaw.toInt() : null;

      if (sessionName != state.sessionName ||
          windowIndex != state.windowIndex) {
        return;
      }

      final messagesData = message['messages'] as List<dynamic>? ?? [];

      final toolRaw = message['tool'];
      String? toolStr;
      if (toolRaw is String) {
        toolStr = toolRaw;
      } else if (toolRaw is Map) {
        toolStr = toolRaw.keys.first.toString();
      }

      final messages = messagesData
          .map((msg) => _parseMessage(msg as Map<String, dynamic>))
          .toList();

      // Get pagination info
      final totalCount = message['totalCount'] as int?;
      final hasMore = message['hasMore'] as bool? ?? false;

      state = state.copyWith(
        messages: messages,
        detectedTool: toolStr,
        isLoading: false,
        error: null,
        totalMessageCount: totalCount,
        hasMoreMessages: hasMore,
      );
      // print(
      //   'DEBUG: isLoading set to false, messages count: ${messages.length}, total: $totalCount, hasMore: $hasMore',
      // );
    } catch (e, stack) {
      // print('ERROR parsing chat history: $e\n$stack');
      state = state.copyWith(
        isLoading: false,
        error: 'Failed to parse chat history',
      );
    }
  }

  void _handleChatHistoryChunk(Map<String, dynamic> message) {
    try {
      final sessionName =
          (message['sessionName'] ?? message['session-name']) as String?;
      final windowIndexRaw = message['windowIndex'] ?? message['window-index'];
      final windowIndex = windowIndexRaw is num ? windowIndexRaw.toInt() : null;

      if (sessionName != state.sessionName ||
          windowIndex != state.windowIndex) {
        return;
      }

      final messagesData = message['messages'] as List<dynamic>? ?? [];
      final hasMore = message['hasMore'] as bool? ?? false;

      final newMessages = messagesData
          .map((msg) => _parseMessage(msg as Map<String, dynamic>))
          .toList();

      // Prepend older messages at the beginning of the list
      final allMessages = [...newMessages, ...state.messages];

      state = state.copyWith(
        messages: allMessages,
        isLoadingMore: false,
        hasMoreMessages: hasMore,
      );
      // print(
      //   'DEBUG: Loaded ${newMessages.length} more messages. Total: ${allMessages.length}, hasMore: $hasMore',
      // );
    } catch (e, stack) {
      // print('ERROR parsing chat history chunk: $e\n$stack');
      state = state.copyWith(
        isLoadingMore: false,
        error: 'Failed to load more messages',
      );
    }
  }

  void loadMoreMessages() {
    if (state.isLoadingMore || !state.hasMoreMessages || _ws == null) {
      return;
    }

    final offset = state.messages.length;
    const limit = 50;

    state = state.copyWith(isLoadingMore: true);
    _ws!.loadMoreChatHistory(
      state.sessionName!,
      state.windowIndex!,
      offset,
      limit,
    );
  }

  void _handleChatEvent(Map<String, dynamic> message) {
    try {
      final sessionName =
          (message['sessionName'] ?? message['session-name']) as String?;
      final windowIndexRaw = message['windowIndex'] ?? message['window-index'];
      final windowIndex = windowIndexRaw is num ? windowIndexRaw.toInt() : null;

      // print(
      //   'DEBUG: Chat event received for $sessionName:$windowIndex. Current state: ${state.sessionName}:${state.windowIndex}',
      // );

      if (sessionName != state.sessionName ||
          windowIndex != state.windowIndex) {
        // print(
        //   'Ignoring chat event for session: $sessionName:$windowIndex (current: ${state.sessionName}:${state.windowIndex})',
        // );
        return;
      }

      final msgData = message['message'] as Map<String, dynamic>?;
      if (msgData == null) return;

      final source = message['source'] as String?;
      final msg = _parseMessage(msgData);

      // Skip user messages from backend for live tmux events (already added locally),
      // but allow them through for ACP and webhook sources.
      if (msg.type == ChatMessageType.user && source != 'webhook') {
        return;
      }

      // Merge consecutive assistant blocks into one visual turn
      final messages = List<ChatMessage>.from(state.messages);
      if (messages.isNotEmpty &&
          messages.last.type == ChatMessageType.assistant &&
          msg.type == ChatMessageType.assistant) {
        final lastMsg = messages.last;
        messages[messages.length - 1] = lastMsg.copyWith(
          blocks: [...lastMsg.blocks, ...msg.blocks],
          content: _mergeContent(lastMsg.content, msg.content),
        );
      } else {
        messages.add(msg);
      }

      state = state.copyWith(messages: messages);
    } catch (e, stack) {
      // print('ERROR parsing chat event: $e\n$stack');
    }
  }

  void _handleChatError(Map<String, dynamic> message) {
    final error = message['error'] as String? ?? 'Unknown error';
    state = state.copyWith(error: error, isLoading: false);
  }

  void _handleChatLogCleared(Map<String, dynamic> message) {
    final sessionName = message['sessionName'] as String?;
    final windowIndexRaw = message['windowIndex'];
    final windowIndex = windowIndexRaw is num ? windowIndexRaw.toInt() : null;
    final success = message['success'] as bool? ?? false;

    if (sessionName != state.sessionName || windowIndex != state.windowIndex) {
      return;
    }

    if (success) {
      state = const ChatState();
      state = state.copyWith(
        sessionName: sessionName,
        windowIndex: windowIndex,
      );
    } else {
      final error = message['error'] as String? ?? 'Failed to clear chat';
      state = state.copyWith(error: error, isLoading: false);
    }
  }

  void _handleChatFileMessage(Map<String, dynamic> message) {
    try {
      final sessionName = message['sessionName'] as String?;
      final windowIndexRaw = message['windowIndex'];
      final windowIndex = windowIndexRaw is num ? windowIndexRaw.toInt() : null;

      // print(
      //   'DEBUG: _handleChatFileMessage - msg sessionName: $sessionName, windowIndex: $windowIndex',
      // );
      // print(
      //   'DEBUG: _handleChatFileMessage - state sessionName: ${state.sessionName}, windowIndex: ${state.windowIndex}',
      // );

      if (sessionName != state.sessionName ||
          windowIndex != state.windowIndex) {
        // print('DEBUG: Session mismatch, ignoring file message');
        return;
      }

      final msgData = message['message'] as Map<String, dynamic>?;
      if (msgData == null) return;

      final msg = _parseMessage(msgData);

      final messages = List<ChatMessage>.from(state.messages);
      if (_isDuplicateFileMessage(messages, msg)) {
        // print('DEBUG: Skipping duplicate file message');
        return;
      }
      messages.add(msg);

      state = state.copyWith(messages: messages);
      // print('DEBUG: Added file message to chat');
    } catch (e, stack) {
      // print('ERROR parsing chat file message: $e\n$stack');
    }
  }

  static final FlutterLocalNotificationsPlugin
  _flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();

  void _handleChatNotification(Map<String, dynamic> message) {
    try {
      final sessionName = message['sessionName'] as String? ?? 'Unknown';
      final preview = message['preview'] as String? ?? 'New message';

      _showLocalNotification(
        title: 'New message from $sessionName',
        body: preview,
      );
    } catch (e) {
      // print('ERROR handling chat notification: $e');
    }
  }

  Future<void> _showLocalNotification({
    required String title,
    required String body,
  }) async {
    const androidDetails = AndroidNotificationDetails(
      'agentshell_chat',
      'Chat Messages',
      channelDescription: 'Notifications for new chat messages',
      importance: Importance.high,
      priority: Priority.high,
      showWhen: true,
    );

    const notificationDetails = NotificationDetails(android: androidDetails);

    final plugin = FlutterLocalNotificationsPlugin();
    await plugin.show(
      id: DateTime.now().millisecondsSinceEpoch ~/ 1000,
      title: title,
      body: body,
      notificationDetails: notificationDetails,
    );
  }

  bool _isDuplicateFileMessage(
    List<ChatMessage> existingMessages,
    ChatMessage incomingMessage,
  ) {
    final incomingAttachmentIds = incomingMessage.blocks
        .where(_isAttachmentBlock)
        .map((block) => block.id)
        .whereType<String>()
        .toSet();

    if (incomingAttachmentIds.isEmpty) {
      return false;
    }

    for (final message in existingMessages) {
      for (final block in message.blocks) {
        if (_isAttachmentBlock(block) &&
            block.id != null &&
            incomingAttachmentIds.contains(block.id)) {
          return true;
        }
      }
    }

    return false;
  }

  bool _isAttachmentBlock(ChatBlock block) {
    return block.type == ChatBlockType.image ||
        block.type == ChatBlockType.audio ||
        block.type == ChatBlockType.file;
  }

  ChatMessage _parseMessage(Map<String, dynamic> data) {
    final role = data['role'] as String? ?? 'assistant';
    final blocksData = data['blocks'] as List<dynamic>? ?? [];

    final blocks = blocksData.map((b) {
      final block = b as Map<String, dynamic>;
      final blockType = block['type'] as String? ?? 'text';

      switch (blockType) {
        case 'tool_call':
          return ChatBlock.toolCall(
            toolName: block['name'] as String?,
            summary: block['summary'] as String?,
            input: _parseInputMap(block['input']),
          );
        case 'tool_result':
          return ChatBlock.toolResult(
            toolName: block['toolName'] as String?,
            content: block['content'] as String?,
            summary: block['summary'] as String?,
          );
        case 'thinking':
          return ChatBlock.thinking(block['content'] as String? ?? '');
        case 'image':
          return ChatBlock.image(
            id: block['id'] as String,
            mimeType: block['mimeType'] as String,
            altText: block['altText'] as String?,
          );
        case 'audio':
          return ChatBlock.audio(
            id: block['id'] as String,
            mimeType: block['mimeType'] as String,
            durationSeconds: (block['durationSeconds'] as num?)?.toDouble(),
          );
        case 'file':
          return ChatBlock.file(
            id: block['id'] as String,
            filename: block['filename'] as String,
            mimeType: block['mimeType'] as String,
            sizeBytes: block['sizeBytes'] as int?,
          );
        default:
          return ChatBlock.text(block['text'] as String? ?? '');
      }
    }).toList();

    String content = '';
    String? toolName;
    ChatMessageType type;

    if (role == 'user') {
      type = ChatMessageType.user;
      final textBlocks = blocks.where((b) => b.type == ChatBlockType.text);
      content = textBlocks.map((b) => b.text ?? '').join('\n');
    } else {
      type = ChatMessageType.assistant;
      final textBlocks = blocks.where((b) => b.type == ChatBlockType.text);
      final toolBlocks = blocks.where((b) => b.type == ChatBlockType.toolCall);
      final toolResultBlocks = blocks.where(
        (b) => b.type == ChatBlockType.toolResult,
      );

      content = textBlocks.map((b) => b.text ?? '').join('\n');

      if (toolBlocks.isNotEmpty) {
        type = ChatMessageType.toolCall;
        toolName = toolBlocks.first.toolName;
      } else if (toolResultBlocks.isNotEmpty) {
        type = ChatMessageType.toolResult;
        toolName = toolResultBlocks.first.toolName;
      }
    }

    final timestamp = _parseTimestamp(data['timestamp']);

    return ChatMessage(
      id: _uuid.v4(),
      type: type,
      content: content,
      timestamp: timestamp ?? DateTime.now(),
      toolName: toolName,
      blocks: blocks,
    );
  }

  DateTime? _parseTimestamp(dynamic value) {
    if (value is! String || value.isEmpty) {
      return null;
    }
    return DateTime.tryParse(value);
  }

  Map<String, dynamic>? _parseInputMap(dynamic value) {
    if (value is Map<String, dynamic>) {
      return value;
    }
    if (value is Map) {
      return value.map((key, val) => MapEntry(key.toString(), val));
    }
    return null;
  }

  void _handleAcpMessageChunk(Map<String, dynamic> message) {
    final sessionId = message['sessionId'] as String?;
    if (sessionId == null || !_matchesAcpSession(sessionId)) return;

    final content = message['content'] as String? ?? '';
    final isThinking = message['isThinking'] as bool? ?? false;

    if (content.isEmpty) return;

    final messages = List<ChatMessage>.from(state.messages);

    if (isThinking) {
      // Merge consecutive thinking chunks into the same thinking block
      if (messages.isNotEmpty &&
          messages.last.type == ChatMessageType.assistant &&
          messages.last.blocks.length == 1 &&
          messages.last.blocks.first.type == ChatBlockType.thinking) {
        final lastMsg = messages.last;
        final merged = (lastMsg.blocks.first.content ?? '') + content;
        messages[messages.length - 1] = lastMsg.copyWith(
          blocks: [ChatBlock.thinking(merged)],
        );
      } else {
        messages.add(
          ChatMessage(
            id: _uuid.v4(),
            type: ChatMessageType.assistant,
            timestamp: DateTime.now(),
            blocks: [ChatBlock.thinking(content)],
          ),
        );
      }
    } else {
      // Merge consecutive text chunks into the same assistant message
      if (messages.isNotEmpty &&
          messages.last.type == ChatMessageType.assistant &&
          messages.last.blocks.isEmpty) {
        final lastMsg = messages.last;
        messages[messages.length - 1] = lastMsg.copyWith(
          content: (lastMsg.content ?? '') + content,
        );
      } else {
        messages.add(
          ChatMessage(
            id: _uuid.v4(),
            type: ChatMessageType.assistant,
            content: content,
            timestamp: DateTime.now(),
          ),
        );
      }
    }

    state = state.copyWith(messages: messages);
  }

  void _handleAcpToolCall(Map<String, dynamic> message) {
    // print('DEBUG: _handleAcpToolCall received: $message');
    final sessionId = message['sessionId'] as String?;
    if (sessionId == null || !_matchesAcpSession(sessionId)) {
      // print(
      //   'DEBUG: Session mismatch - message: $sessionId, state: ${state.sessionName}',
      // );
      return;
    }

    final toolCallId = message['toolCallId'] as String? ?? '';
    final title = message['title'] as String? ?? 'Unknown Tool';
    final kind = message['kind'] as String? ?? '';
    final inputStr = message['input'] as String? ?? '';

    // print('DEBUG: Processing tool call - title: $title, kind: $kind');

    Map<String, dynamic>? inputMap;
    if (inputStr.isNotEmpty) {
      try {
        inputMap = Map<String, dynamic>.from(json.decode(inputStr) as Map);
      } catch (_) {
        inputMap = {'raw': inputStr};
      }
    }

    final chatMessage = ChatMessage(
      id: toolCallId,
      type: ChatMessageType.toolCall,
      blocks: [
        ChatBlock.toolCall(toolName: title, input: inputMap, summary: kind),
      ],
      timestamp: DateTime.now(),
    );

    state = state.copyWith(messages: [...state.messages, chatMessage]);
  }

  void _handleAcpToolResult(Map<String, dynamic> message) {
    final sessionId = message['sessionId'] as String?;
    if (sessionId == null || !_matchesAcpSession(sessionId)) return;

    final toolCallId = message['toolCallId'] as String? ?? '';
    final status = message['status'] as String? ?? '';
    final output = message['output'] as String? ?? '';

    state = state.copyWith(
      messages: [
        ...state.messages,
        ChatMessage(
          id: toolCallId,
          type: ChatMessageType.toolResult,
          blocks: [
            ChatBlock.toolResult(
              toolName: '',
              content: output,
              summary: status,
            ),
          ],
          timestamp: DateTime.now(),
        ),
      ],
    );
  }

  void _handleAcpPermissionRequest(Map<String, dynamic> message) {
    final requestId = message['requestId'] as String? ?? '';
    final tool = message['tool'] as String? ?? 'Unknown';
    final command = message['command'] as String? ?? '';
    final rawOptions = message['options'] as List?;
    final options =
        rawOptions?.map((o) => Map<String, dynamic>.from(o as Map)).toList() ??
        [
          {'id': 'approve', 'label': 'Approve'},
          {'id': 'deny', 'label': 'Deny'},
        ];

    state = state.copyWith(
      pendingPermission: PendingPermission(
        requestId: requestId,
        tool: tool,
        command: command,
        options: options,
      ),
    );
  }

  void respondPermission(String requestId, String optionId) {
    // Guard: only respond if the card belongs to the current pending request
    if (state.pendingPermission?.requestId != requestId) return;
    _ws?.acpRespondPermission(requestId, optionId);
    state = state.copyWith(pendingPermission: null);
  }

  void _handleAcpPromptDone(Map<String, dynamic> message) {
    final sessionId = message['sessionId'] as String?;
    if (sessionId == null || !_matchesAcpSession(sessionId)) return;

    final stopReason = message['stopReason'] as String? ?? '';
    state = state.copyWith(
      messages: [
        ...state.messages,
        ChatMessage(
          id: _uuid.v4(),
          type: ChatMessageType.system,
          content: 'Done (reason: $stopReason)',
          timestamp: DateTime.now(),
        ),
      ],
    );

    // Fire a local notification so the user is alerted even when app is backgrounded
    final lastUserVisible = state.messages
        .where((m) => m.type == ChatMessageType.assistant)
        .lastOrNull;
    final preview =
        lastUserVisible?.content?.trim().split('\n').first ?? 'Task completed';
    _showLocalNotification(
      title: 'AI task done',
      body: preview.length > 100 ? '${preview.substring(0, 100)}…' : preview,
    );
  }

  String _mergeContent(String? left, String? right) {
    final a = (left ?? '').trim();
    final b = (right ?? '').trim();
    if (a.isEmpty) return b;
    if (b.isEmpty) return a;
    return '$a\n$b';
  }

  String _rawAcpSessionId(String sessionName) =>
      sessionName.startsWith('acp_') ? sessionName.substring(4) : sessionName;

  bool _matchesAcpSession(String messageSessionId) {
    final rawStateSessionId = _rawAcpSessionId(state.sessionName);
    return messageSessionId == state.sessionName ||
        messageSessionId == rawStateSessionId ||
        state.sessionName == 'acp_$messageSessionId';
  }

  void startAcpChat(String sessionName, String cwd) {
    // Backend returns session_name as "acp_{sessionId}" in chat-history/chat-event,
    // so store it with the prefix so all session matching works naturally.
    final sessionKey = 'acp_$sessionName';
    state = state.copyWith(
      messages: [],
      isLoading: true,
      error: null,
      sessionName: sessionKey,
      windowIndex: 0,
      isAcp: true,
      pendingPermission: null,
      acpSessionCwd: cwd,
    );
    _ws!.watchAcpChatLog(sessionName, limit: 30);
  }

  void watchChatLog(String sessionName, int windowIndex, {int? limit}) {
    state = state.copyWith(
      messages: [],
      isLoading: true,
      error: null,
      sessionName: sessionName,
      windowIndex: windowIndex,
      hasMoreMessages: false,
      totalMessageCount: null,
      pendingPermission: null,
    );

    // First attach to the session's PTY so we can send input
    _ws?.attachSession(
      sessionName,
      cols: 80,
      rows: 24,
      windowIndex: windowIndex,
    );
    // Then watch the chat log with limit for initial load
    _ws?.watchChatLog(sessionName, windowIndex, limit: limit ?? 50);
  }

  void unwatchChatLog() {
    _ws?.unwatchChatLog();
    state = state.copyWith(isLoading: false);
  }

  void sendInput(String data) async {
    if (_ws != null && state.sessionName != null && state.windowIndex != null) {
      // Send text without appending '\n'. The backend issues two separate tmux
      // send-keys calls: one for the literal text, one for Enter.
      _ws!.sendInputViaTmux(
        state.sessionName!,
        data,
        windowIndex: state.windowIndex,
      );
    }
  }

  void addMessage(ChatMessage message) {
    state = state.copyWith(messages: [...state.messages, message]);
  }

  void addUserMessage(String content) {
    final message = ChatMessage(
      id: _uuid.v4(),
      type: ChatMessageType.user,
      content: content,
      timestamp: DateTime.now(),
    );
    addMessage(message);
  }

  void addAssistantMessage(
    String content, {
    String? toolName,
    List<ChatBlock>? blocks,
  }) {
    ChatMessageType messageType;
    if (toolName != null) {
      messageType = ChatMessageType.toolCall;
    } else if (blocks != null && blocks.isNotEmpty) {
      messageType = ChatMessageType.assistant;
    } else {
      messageType = ChatMessageType.assistant;
    }

    // Don't set content when we have blocks - blocks will be used for rendering
    // This allows filtering to work properly (e.g., hiding thinking/tool calls)
    final messageContent = (blocks != null && blocks.isNotEmpty)
        ? null
        : content;

    final message = ChatMessage(
      id: _uuid.v4(),
      type: messageType,
      content: messageContent,
      timestamp: DateTime.now(),
      toolName: toolName,
      blocks: blocks ?? [],
    );
    addMessage(message);
  }

  void addSystemMessage(String content) {
    final message = ChatMessage(
      id: _uuid.v4(),
      type: ChatMessageType.system,
      content: content,
      timestamp: DateTime.now(),
    );
    addMessage(message);
  }

  void addErrorMessage(String content) {
    final message = ChatMessage(
      id: _uuid.v4(),
      type: ChatMessageType.error,
      content: content,
      timestamp: DateTime.now(),
    );
    addMessage(message);
  }

  void updateLastMessage(String content) {
    if (state.messages.isNotEmpty) {
      final messages = List<ChatMessage>.from(state.messages);
      final lastMessage = messages.last;
      messages[messages.length - 1] = lastMessage.copyWith(
        content: '${lastMessage.content ?? ''}$content',
      );
      state = state.copyWith(messages: messages);
    }
  }

  void setStreaming(bool streaming) {
    if (state.messages.isNotEmpty) {
      final messages = List<ChatMessage>.from(state.messages);
      final lastMessage = messages.last;
      messages[messages.length - 1] = lastMessage.copyWith(
        isStreaming: streaming,
      );
      state = state.copyWith(messages: messages);
    }
  }

  void clear() {
    if (state.isAcp) {
      // ACP: clear DB via backend, then reset local state
      if (state.sessionName != null && _ws != null) {
        final rawId = state.sessionName!.replaceFirst('acp_', '');
        _ws!.clearAcpHistory(rawId);
      }
      final sessionName = state.sessionName;
      state = const ChatState();
      state = state.copyWith(
        sessionName: sessionName,
        windowIndex: 0,
        isAcp: true,
      );
    } else if (state.sessionName != null &&
        state.windowIndex != null &&
        _ws != null) {
      _ws!.clearChatLog(state.sessionName!, state.windowIndex!);
    } else {
      state = const ChatState();
    }
  }

  Future<bool> checkMicrophonePermission() async {
    return await _audioService.hasPermission();
  }

  Future<void> startVoiceRecording() async {
    final hasPermission = await _audioService.hasPermission();
    if (!hasPermission) {
      state = state.copyWith(error: 'Microphone permission denied');
      return;
    }

    final path = await _audioService.startRecording();
    if (path != null) {
      state = state.copyWith(
        isRecording: true,
        recordingDuration: Duration.zero,
        transcribedText: null,
        error: null,
      );
      _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        state = state.copyWith(
          recordingDuration: _audioService.recordingDuration,
        );
      });
    }
  }

  Future<String?> stopVoiceRecording() async {
    _recordingTimer?.cancel();
    _recordingTimer = null;

    final path = await _audioService.stopRecording();
    if (path != null) {
      state = state.copyWith(isRecording: false);
      return path;
    }
    state = state.copyWith(isRecording: false);
    return null;
  }

  Future<void> transcribeAudio(String audioPath) async {
    if (_prefs == null) {
      state = state.copyWith(
        error:
            'API key not configured. Please add your OpenAI API key in Settings.',
        isTranscribing: false,
      );
      return;
    }

    final apiKey = _prefs!.getString(AppConfig.keyOpenAiApiKey);
    if (apiKey == null || apiKey.isEmpty) {
      state = state.copyWith(
        error:
            'API key not configured. Please add your OpenAI API key in Settings.',
        isTranscribing: false,
      );
      return;
    }

    state = state.copyWith(isTranscribing: true, error: null);

    final text = await _whisperService.transcribe(audioPath, apiKey);

    state = state.copyWith(isTranscribing: false, transcribedText: text);
  }

  void clearTranscribedText() {
    state = state.copyWith(transcribedText: null);
  }

  // Parse Claude Code output into structured messages
  void parseClaudeOutput(String output) {
    final lines = output.split('\n');
    String currentBlock = '';
    String? currentType;

    for (final line in lines) {
      // Detect block types
      if (line.startsWith('Tool:') || line.startsWith('Using tool:')) {
        currentType = 'tool';
        if (currentBlock.isNotEmpty) {
          _flushBlock(currentBlock.trim(), currentType);
        }
        currentBlock = line;
      } else if (line.startsWith('Error:') || line.startsWith('Error -')) {
        currentType = 'error';
        if (currentBlock.isNotEmpty) {
          _flushBlock(currentBlock.trim(), currentType);
        }
        currentBlock = line;
      } else if (line.startsWith('>') || line.startsWith(r'$')) {
        currentType = 'user';
        if (currentBlock.isNotEmpty) {
          _flushBlock(currentBlock.trim(), currentType);
        }
        currentBlock = line;
      } else if (line.trim().isEmpty && currentBlock.isNotEmpty) {
        _flushBlock(currentBlock.trim(), currentType ?? 'assistant');
        currentBlock = '';
        currentType = null;
      } else {
        currentBlock += '\n$line';
      }
    }

    // Flush remaining
    if (currentBlock.isNotEmpty) {
      _flushBlock(currentBlock.trim(), currentType ?? 'assistant');
    }
  }

  void _flushBlock(String content, String type) {
    switch (type) {
      case 'tool':
        final toolName = _extractToolName(content);
        final blocks = toolName != null
            ? [ChatBlock.toolCall(toolName: toolName, summary: '')]
            : <ChatBlock>[];
        addAssistantMessage(content, toolName: toolName, blocks: blocks);
        break;
      case 'error':
        addErrorMessage(content);
        break;
      case 'user':
        // Skip user input blocks
        break;
      default:
        addAssistantMessage(content);
    }
  }

  String? _extractToolName(String content) {
    final match = RegExp(r'Tool:\s*(\w+)').firstMatch(content);
    return match?.group(1);
  }

  @override
  void dispose() {
    _messageSubscription?.cancel();
    _connectionSubscription?.cancel();
    _recordingTimer?.cancel();
    super.dispose();
  }
}

final chatProvider = StateNotifierProvider<ChatNotifier, ChatState>((ref) {
  final notifier = ChatNotifier();

  // Set SharedPreferences
  final prefs = ref.read(sharedPreferencesProvider);
  notifier.setPrefs(prefs);

  // Watch the shared WebSocket service
  ref.listen<WebSocketService?>(sharedWebSocketServiceProvider, (
    previous,
    next,
  ) {
    if (next != null) {
      notifier.setWebSocket(next);
    }
  });

  // Set initial WebSocket if already available
  final ws = ref.read(sharedWebSocketServiceProvider);
  if (ws != null) {
    notifier.setWebSocket(ws);
  }

  ref.onDispose(() {
    notifier.unwatchChatLog();
    notifier.dispose();
  });

  return notifier;
});

final filteredChatMessagesProvider = Provider<List<ChatMessage>>((ref) {
  final chatState = ref.watch(chatProvider);

  final showThinking = chatState.showThinking;
  final showToolCalls = chatState.showToolCalls;

  return chatState.messages
      .map((message) {
        if (message.blocks.isEmpty) return message;

        final filteredBlocks = message.blocks.where((block) {
          if (!showThinking && block.type == ChatBlockType.thinking) {
            return false;
          }
          if (!showToolCalls &&
              (block.type == ChatBlockType.toolCall ||
                  block.type == ChatBlockType.toolResult)) {
            return false;
          }
          return true;
        }).toList();

        // If all blocks were filtered out, return null to exclude this message
        if (filteredBlocks.isEmpty) {
          return null;
        }

        return message.copyWith(blocks: filteredBlocks);
      })
      .whereType<ChatMessage>()
      .toList();
});

// Global audio player - never disposed, persists across scrolls
final globalAudioPlayerProvider = Provider<AudioPlayer>((ref) {
  final player = AudioPlayer();
  ref.onDispose(() {});
  return player;
});

// Tracks which audio block is currently loaded in the global player.
// Survives widget disposal/recreation during ListView scrolling.
final activeAudioBlockIdProvider = StateProvider<String?>((ref) => null);
