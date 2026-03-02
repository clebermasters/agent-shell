import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';
import '../../../data/models/chat_message.dart';
import '../../../data/services/websocket_service.dart';
import '../../sessions/providers/sessions_provider.dart';

class ChatState {
  final List<ChatMessage> messages;
  final bool isLoading;
  final String? error;
  final String? detectedTool;
  final String? sessionName;
  final int? windowIndex;

  const ChatState({
    this.messages = const [],
    this.isLoading = false,
    this.error,
    this.detectedTool,
    this.sessionName,
    this.windowIndex,
  });

  ChatState copyWith({
    List<ChatMessage>? messages,
    bool? isLoading,
    String? error,
    String? detectedTool,
    String? sessionName,
    int? windowIndex,
  }) {
    return ChatState(
      messages: messages ?? this.messages,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      detectedTool: detectedTool ?? this.detectedTool,
      sessionName: sessionName ?? this.sessionName,
      windowIndex: windowIndex ?? this.windowIndex,
    );
  }
}

class ChatNotifier extends StateNotifier<ChatState> {
  final Uuid _uuid = const Uuid();
  StreamSubscription? _messageSubscription;
  WebSocketService? _ws;

  ChatNotifier() : super(const ChatState());

  void setWebSocket(WebSocketService ws) {
    _ws = ws;
    _listenToMessages();
  }

  void _listenToMessages() {
    _messageSubscription?.cancel();
    if (_ws == null) return;

    _messageSubscription = _ws!.messages.listen((message) {
      final type = message['type'] as String?;
      switch (type) {
        case 'chat-history':
          _handleChatHistory(message);
          break;
        case 'chat-event':
          _handleChatEvent(message);
          break;
        case 'chat-log-error':
          _handleChatError(message);
          break;
      }
    });
  }

  void _handleChatHistory(Map<String, dynamic> message) {
    final messagesData = message['messages'] as List<dynamic>? ?? [];
    final tool = message['tool'] as String?;

    // Filter out user messages from backend history (we add them locally)
    final messages = messagesData
        .map((msg) => _parseMessage(msg as Map<String, dynamic>))
        .where((msg) => msg.type != ChatMessageType.user)
        .toList();

    state = state.copyWith(
      messages: messages,
      detectedTool: tool,
      isLoading: false,
      error: null,
    );
  }

  void _handleChatEvent(Map<String, dynamic> message) {
    final msgData = message['message'] as Map<String, dynamic>?;
    if (msgData == null) return;

    final msg = _parseMessage(msgData);

    // Skip user messages from backend since we already add them locally
    if (msg.type == ChatMessageType.user) {
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
  }

  void _handleChatError(Map<String, dynamic> message) {
    final error = message['error'] as String? ?? 'Unknown error';
    state = state.copyWith(error: error, isLoading: false);
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
        type = ChatMessageType.tool;
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

  String _mergeContent(String? left, String? right) {
    final a = (left ?? '').trim();
    final b = (right ?? '').trim();
    if (a.isEmpty) return b;
    if (b.isEmpty) return a;
    return '$a\n$b';
  }

  void watchChatLog(String sessionName, int windowIndex) {
    state = state.copyWith(
      messages: [],
      isLoading: true,
      error: null,
      sessionName: sessionName,
      windowIndex: windowIndex,
    );
    // First attach to the session's PTY so we can send input
    _ws?.attachSession(
      sessionName,
      cols: 80,
      rows: 24,
      windowIndex: windowIndex,
    );
    // Then watch the chat log
    _ws?.watchChatLog(sessionName, windowIndex);
  }

  void unwatchChatLog() {
    _ws?.unwatchChatLog();
    state = state.copyWith(isLoading: false);
  }

  void sendInput(String data) async {
    if (_ws != null && state.sessionName != null && state.windowIndex != null) {
      final messageWithNewline = "$data\n";
      _ws!.sendInputViaTmux(
        state.sessionName!,
        messageWithNewline,
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

  void addAssistantMessage(String content, {String? toolName}) {
    final message = ChatMessage(
      id: _uuid.v4(),
      type: toolName != null ? ChatMessageType.tool : ChatMessageType.assistant,
      content: content,
      timestamp: DateTime.now(),
      toolName: toolName,
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
    state = const ChatState();
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
        addAssistantMessage(content, toolName: toolName);
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
    super.dispose();
  }
}

final chatProvider = StateNotifierProvider<ChatNotifier, ChatState>((ref) {
  final notifier = ChatNotifier();

  // Watch the shared WebSocket service
  ref.listen(sharedWebSocketServiceProvider, (previous, next) {
    notifier.setWebSocket(next as WebSocketService);
  });

  // Set initial WebSocket if already available
  final ws = ref.read(sharedWebSocketServiceProvider);
  notifier.setWebSocket(ws as WebSocketService);

  ref.onDispose(() {
    notifier.unwatchChatLog();
    notifier.dispose();
  });

  return notifier;
});
