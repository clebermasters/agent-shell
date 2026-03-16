import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:web_socket_channel/web_socket_channel.dart';
import '../../core/config/app_config.dart';
import '../../core/config/build_config.dart';
import '../models/models.dart';

typedef MessageHandler = void Function(Map<String, dynamic> message);
typedef ConnectionHandler = void Function();

class WebSocketService {
  WebSocketChannel? _channel;
  StreamSubscription? _subscription;
  Timer? _pingTimer;
  Timer? _reconnectTimer;
  Timer? _pongTimeoutTimer;

  bool _isConnected = false;
  String? _currentUrl;
  final List<Map<String, dynamic>> _pendingQueue = [];

  final _messageController = StreamController<Map<String, dynamic>>.broadcast();
  final _connectionController = StreamController<bool>.broadcast();
  final _logController = StreamController<String>.broadcast();

  Stream<Map<String, dynamic>> get messages => _messageController.stream;
  Stream<bool> get connectionState => _connectionController.stream;
  Stream<String> get logs => _logController.stream;
  bool get isConnected => _isConnected;
  String? get currentUrl => _currentUrl;

  void _log(String message) {
    final timestamp = DateTime.now().toIso8601String();
    // print('WS LOG: [$timestamp] $message');
    _logController.add('[$timestamp] $message');
  }

  Future<void> connect(String url, {String runtimeToken = ''}) async {
    // On web (HTTPS), upgrade ws:// to wss:// to avoid mixed content errors
    if (kIsWeb && url.startsWith('ws://')) {
      url = 'wss://${url.substring(5)}';
    }
    // Remove explicit :443 port (redundant for wss://)
    url = url.replaceFirst(RegExp(r':443(/|$)'), r'$1');

    // Append auth token as query parameter (works on all platforms including web)
    // BuildConfig.authToken takes priority; fall back to runtimeToken (web login flow)
    final effectiveToken = BuildConfig.authToken.isNotEmpty
        ? BuildConfig.authToken
        : runtimeToken;
    if (effectiveToken.isNotEmpty) {
      final separator = url.contains('?') ? '&' : '?';
      url = '$url${separator}token=${Uri.encodeComponent(effectiveToken)}';
    }

    _currentUrl = url;
    _log('Connecting to: ${_sanitizeUrl(url)}');
    await _doConnect();
  }

  /// Remove token from URL for safe logging
  String _sanitizeUrl(String url) {
    return url.replaceFirst(RegExp(r'[?&]token=[^&]*'), '');
  }

  Future<void> _doConnect() async {
    if (_currentUrl == null) return;

    // Cancel any existing connection
    await _subscription?.cancel();
    await _channel?.sink.close();

    try {
      _log('Attempting WebSocket connection to: ${_sanitizeUrl(_currentUrl!)}');
      _channel = WebSocketChannel.connect(Uri.parse(_currentUrl!));

      _subscription = _channel!.stream.listen(
        _onMessage,
        onError: _onError,
        onDone: _onDone,
        cancelOnError: true,
      );

      await _channel!.ready;

      _isConnected = true;
      // ignore: avoid_print
      print('[CONN] Flutter→Backend CONNECTED to ${_sanitizeUrl(_currentUrl!)}');
      _connectionController.add(true);
      _flushPendingQueue();
      _startPingTimer();
    } catch (e) {
      // ignore: avoid_print
      print('[CONN] Flutter→Backend CONNECT FAILED: $e (url: ${_sanitizeUrl(_currentUrl!)})');

      _isConnected = false;
      _connectionController.add(false);
      _scheduleReconnect();
    }
  }

  void _onMessage(dynamic data) {
    final dataStr = data.toString();
    _log('Received: $dataStr');
    try {
      final message = jsonDecode(data as String) as Map<String, dynamic>;
      final type = message['type'];
      if (type == 'pong') {
        _pongTimeoutTimer?.cancel();
      }
      if (type != 'output' &&
          type != 'system-stats' &&
          type != 'pong' &&
          type != 'stats') {
        // print('WS RECV TYPE: $type');
      }
      _messageController.add(message);
    } catch (e) {
      _log('Failed to parse message: $e');
    }
  }

  void _onError(dynamic error) {
    // ignore: avoid_print
    print('[CONN] Flutter→Backend WebSocket ERROR: $error (url: ${_sanitizeUrl(_currentUrl ?? '')})');

    _isConnected = false;
    _connectionController.add(false);
    _scheduleReconnect();
  }

  void _onDone() {
    // ignore: avoid_print
    print('[CONN] Flutter→Backend WebSocket CLOSED (url: ${_sanitizeUrl(_currentUrl ?? '')})');

    _isConnected = false;
    _connectionController.add(false);
    _scheduleReconnect();
  }

  void _startPingTimer() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      send({'type': 'ping'});
      _pongTimeoutTimer?.cancel();
      _pongTimeoutTimer = Timer(const Duration(seconds: 20), () {
        // ignore: avoid_print
        print('[CONN] Flutter→Backend PONG TIMEOUT — forcing reconnect');
        forceReconnect();
      });
    });
  }

  void _scheduleReconnect() {
    _log('Scheduling reconnect in 5 seconds...');
    _pingTimer?.cancel();
    _pongTimeoutTimer?.cancel();
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      _doConnect();
    });
  }

  void forceReconnect() {
    if (_currentUrl == null) return;
    _log('Forcing immediate reconnect...');
    _pingTimer?.cancel();
    _pongTimeoutTimer?.cancel();
    _reconnectTimer?.cancel();
    try {
      _subscription?.cancel();
      _channel?.sink.close();
    } catch (_) {}
    _isConnected = false;
    _connectionController.add(false);
    _doConnect();
  }

  void send(Map<String, dynamic> message) {
    _log('send() called with: ${message['type']}, isConnected: $_isConnected');
    if (_isConnected && _channel != null) {
      _log('Sending: $message');
      _channel!.sink.add(jsonEncode(message));
    } else {
      _log('Queuing message (not connected): ${message['type']}');
      _pendingQueue.add(message);
      if (_reconnectTimer == null || !_reconnectTimer!.isActive) {
        _scheduleReconnect();
      }
    }
  }

  void _flushPendingQueue() {
    if (_pendingQueue.isEmpty) return;
    final messages = List<Map<String, dynamic>>.from(_pendingQueue);
    _pendingQueue.clear();
    _log('Flushing ${messages.length} queued message(s) after reconnect');
    for (final msg in messages) {
      _channel!.sink.add(jsonEncode(msg));
    }
  }

  // Request methods - using Capacitor web app format (type instead of action)
  void requestSessions() {
    send({'type': 'list-sessions'});
  }

  void requestWindows(String sessionName) {
    send({'type': 'list-windows', 'sessionName': sessionName});
  }

  void createSession(String name) {
    send({'type': 'create-session', 'name': name});
  }

  void killSession(String name) {
    send({'type': 'kill-session', 'sessionName': name});
  }

  void attachSession(
    String name, {
    int cols = 80,
    int rows = 24,
    int windowIndex = 0,
    bool requestHistory = true,
  }) {
    send({
      'type': 'attach-session',
      'sessionName': name,
      'cols': cols,
      'rows': rows,
      'windowIndex': windowIndex,
      'requestHistory': requestHistory,
    });
  }

  void createWindow(String sessionName, String windowName) {
    send({
      'type': 'create-window',
      'sessionName': sessionName,
      'name': windowName,
    });
  }

  void killWindow(String sessionName, int windowId) {
    send({
      'type': 'kill-window',
      'sessionName': sessionName,
      'windowIndex': windowId,
    });
  }

  void selectWindow(String sessionName, int windowId) {
    send({
      'type': 'select-window',
      'sessionName': sessionName,
      'windowIndex': windowId,
    });
  }

  void renameSession(String oldName, String newName) {
    send({
      'type': 'rename-session',
      'sessionName': oldName,
      'newName': newName,
    });
  }

  void renameWindow(String sessionName, int windowIndex, String newName) {
    send({
      'type': 'rename-window',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
      'newName': newName,
    });
  }

  void sendTerminalData(String sessionName, String data) {
    send({'type': 'input', 'data': data});
  }

  void sendInputViaTmux(String sessionName, String data, {int? windowIndex}) {
    send({
      'type': 'inputViaTmux',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
      'data': data,
    });
  }

  void sendEnterKey(String sessionName) {
    send({'type': 'sendEnterKey'});
  }

  void resizeTerminal(String sessionName, int cols, int rows) {
    send({'type': 'resize', 'cols': cols, 'rows': rows});
  }

  void requestCronJobs() {
    send({'type': 'list-cron-jobs'});
  }

  void createCronJob(CronJob job) {
    send({'type': 'create-cron-job', 'job': job.toJson()});
  }

  void updateCronJob(CronJob job) {
    send({'type': 'update-cron-job', 'id': job.id, 'job': job.toJson()});
  }

  void deleteCronJob(String id) {
    send({'type': 'delete-cron-job', 'id': id});
  }

  void toggleCronJob(String id, bool enabled) {
    send({'type': 'toggle-cron-job', 'id': id, 'enabled': enabled});
  }

  void testCronCommand(String command) {
    send({'type': 'test-cron-command', 'command': command});
  }

  void requestDotfiles() {
    send({'type': 'list-dotfiles'});
  }

  void requestDotfileContent(String path) {
    send({'type': 'read-dotfile', 'path': path});
  }

  void requestBinaryFileContent(String path) {
    send({'type': 'read-binary-file', 'path': path});
  }

  void saveDotfile(String path, String content) {
    send({'type': 'write-dotfile', 'path': path, 'content': content});
  }

  void requestDotfileHistory(String path) {
    send({'type': 'get-dotfile-history', 'path': path});
  }

  void restoreDotfileVersion(String path, String timestamp) {
    send({
      'type': 'restore-dotfile-version',
      'path': path,
      'timestamp': timestamp,
    });
  }

  void requestDotfileTemplates() {
    send({'type': 'get-dotfile-templates'});
  }

  void requestSystemStats() {
    send({'type': 'get-stats'});
  }

  void watchChatLog(String sessionName, int windowIndex, {int? limit}) {
    send({
      'type': 'watch-chat-log',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
      if (limit != null) 'limit': limit,
    });
  }

  void watchAcpChatLog(String sessionId, {int? windowIndex, int? limit}) {
    send({
      'type': 'watch-acp-chat-log',
      'sessionId': sessionId,
      if (windowIndex != null) 'windowIndex': windowIndex,
      if (limit != null) 'limit': limit,
    });
  }

  void unwatchChatLog() {
    send({'type': 'unwatch-chat-log'});
  }

  void loadMoreChatHistory(
    String sessionName,
    int windowIndex,
    int offset,
    int limit,
  ) {
    send({
      'type': 'load-more-chat-history',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
      'offset': offset,
      'limit': limit,
    });
  }

  void clearChatLog(String sessionName, int windowIndex) {
    send({
      'type': 'clear-chat-log',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
    });
  }

  void clearAcpHistory(String sessionId) {
    send({'type': 'acp-clear-history', 'sessionId': sessionId});
  }

  void deleteAcpSession(String sessionId) {
    send({'type': 'acp-delete-session', 'sessionId': sessionId});
  }

  void sendFileToChat({
    required String sessionName,
    required int windowIndex,
    required String filename,
    required String mimeType,
    required String base64Data,
    String? prompt,
  }) {
    send({
      'type': 'send-file-to-chat',
      'sessionName': sessionName,
      'windowIndex': windowIndex,
      'file': {'filename': filename, 'mimeType': mimeType, 'data': base64Data},
      'prompt': prompt,
    });
  }

  void sendFileToAcpChat({
    required String sessionId,
    required String filename,
    required String mimeType,
    required String base64Data,
    String? prompt,
    String? cwd,
  }) {
    send({
      'type': 'send-file-to-acp-chat',
      'sessionId': sessionId,
      'file': {'filename': filename, 'mimeType': mimeType, 'data': base64Data},
      'prompt': prompt,
      'cwd': cwd,
    });
  }

  void selectBackend(String backend) {
    send({'type': 'select-backend', 'backend': backend});
  }

  void acpCreateSession(String cwd) {
    send({'type': 'acp-create-session', 'cwd': cwd});
  }

  void acpResumeSession(String sessionId, String cwd) {
    send({'type': 'acp-resume-session', 'sessionId': sessionId, 'cwd': cwd});
  }

  void acpForkSession(String sessionId, String cwd) {
    send({'type': 'acp-fork-session', 'sessionId': sessionId, 'cwd': cwd});
  }

  void acpListSessions() {
    send({'type': 'acp-list-sessions'});
  }

  void acpSendPrompt(String sessionId, String message) {
    send({
      'type': 'acp-send-prompt',
      'sessionId': sessionId,
      'message': message,
    });
  }

  void acpCancelPrompt(String sessionId) {
    send({'type': 'acp-cancel-prompt', 'sessionId': sessionId});
  }

  void acpSetModel(String sessionId, String modelId) {
    send({'type': 'acp-set-model', 'sessionId': sessionId, 'modelId': modelId});
  }

  void acpSetMode(String sessionId, String modeId) {
    send({'type': 'acp-set-mode', 'sessionId': sessionId, 'modeId': modeId});
  }

  void acpLoadHistory(String sessionId, {int offset = 0, int limit = 50}) {
    send({
      'type': 'acp-load-history',
      'sessionId': sessionId,
      'offset': offset,
      'limit': limit,
    });
  }

  void listFiles(String path) {
    send({'type': 'list-files', 'path': path});
  }

  void getSessionCwd(String sessionName) {
    send({'type': 'get-session-cwd', 'sessionName': sessionName});
  }

  void deleteFiles(List<String> paths, {bool recursive = false}) {
    send({'type': 'delete-files', 'paths': paths, 'recursive': recursive});
  }

  void renameFile(String path, String newName) {
    send({'type': 'rename-file', 'path': path, 'newName': newName});
  }

  void acpRespondPermission(String requestId, String optionId) {
    send({
      'type': 'acp-respond-permission',
      'requestId': requestId,
      'optionId': optionId,
    });
  }

  void disconnect() {
    _log('Disconnecting...');
    _pingTimer?.cancel();
    _pongTimeoutTimer?.cancel();
    _reconnectTimer?.cancel();
    _subscription?.cancel();
    _channel?.sink.close();
    _isConnected = false;
    _connectionController.add(false);
  }

  void dispose() {
    disconnect();
    _messageController.close();
    _connectionController.close();
    _logController.close();
  }
}
