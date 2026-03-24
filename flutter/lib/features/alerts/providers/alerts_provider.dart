import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/notification.dart';
import '../../../data/models/host.dart';
import '../../../data/services/websocket_service.dart';
import '../../hosts/providers/hosts_provider.dart';
import '../../sessions/providers/sessions_provider.dart';
import '../../../core/config/build_config.dart';

final alertsProvider = StateNotifierProvider<AlertsNotifier, AlertsState>((ref) {
  final wsService = ref.watch(sharedWebSocketServiceProvider);
  final hostsState = ref.watch(hostsProvider);
  return AlertsNotifier(wsService, hostsState.selectedHost);
});

class AlertsState {
  final List<Notification> notifications;
  final bool isLoading;
  final String? error;
  final int unreadCount;

  const AlertsState({
    this.notifications = const [],
    this.isLoading = false,
    this.error,
    this.unreadCount = 0,
  });

  AlertsState copyWith({
    List<Notification>? notifications,
    bool? isLoading,
    String? error,
    int? unreadCount,
  }) {
    return AlertsState(
      notifications: notifications ?? this.notifications,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      unreadCount: unreadCount ?? this.unreadCount,
    );
  }
}

class AlertsNotifier extends StateNotifier<AlertsState> {
  final WebSocketService _wsService;
  final Host? _host;
  final Dio _dio = Dio();
  StreamSubscription? _wsSubscription;

  AlertsNotifier(this._wsService, this._host) : super(const AlertsState()) {
    _init();
  }

  void _init() {
    _wsSubscription = _wsService.notificationStream.listen(_handleNotification);
    
    _wsService.connectionState.listen((connected) {
      if (connected) {
        fetchNotifications();
      }
    });
  }

  void _handleNotification(Notification notification) {
    final newList = [notification, ...state.notifications];
    final newUnread = state.unreadCount + 1;
    state = state.copyWith(notifications: newList, unreadCount: newUnread);
  }

  Future<void> fetchNotifications() async {
    if (_host == null) return;
    state = state.copyWith(isLoading: true);
    
    try {
      final baseUrl = _host!.httpUrl;
      final token = _host.wsUrl.contains('?token=') 
          ? _host.wsUrl.split('?token=')[1] 
          : '';
      final url = '$baseUrl/api/notifications?limit=50';
      
      final response = await _dio.get(
        url,
        options: Options(headers: {'Authorization': 'Bearer $token'}),
      );
      
      if (response.statusCode == 200) {
        final data = response.data as Map<String, dynamic>;
        final notifications = (data['notifications'] as List?)
            ?.map((n) => Notification.fromJson(n as Map<String, dynamic>))
            .toList() ?? [];
        final unreadCount = notifications.where((n) => !n.read).length;
        state = state.copyWith(
          notifications: notifications,
          isLoading: false,
          unreadCount: unreadCount,
        );
      }
    } catch (e) {
      state = state.copyWith(isLoading: false, error: e.toString());
    }
  }

  Future<void> markRead(String id) async {
    if (_host == null) return;
    
    try {
      final baseUrl = _host!.httpUrl;
      final token = _host.wsUrl.contains('?token=') 
          ? _host.wsUrl.split('?token=')[1] 
          : '';
      
      await _dio.post(
        '$baseUrl/api/notifications/$id/read',
        options: Options(headers: {'Authorization': 'Bearer $token'}),
      );
      
      final newList = state.notifications.map((n) {
        if (n.id == id) {
          return Notification(
            id: n.id,
            title: n.title,
            body: n.body,
            source: n.source,
            sourceDetail: n.sourceDetail,
            timestamp: n.timestamp,
            read: true,
            fileCount: n.fileCount,
            files: n.files,
          );
        }
        return n;
      }).toList();
      
      state = state.copyWith(
        notifications: newList,
        unreadCount: newList.where((n) => !n.read).length,
      );
    } catch (e) {
    }
  }

  Future<void> markAllRead() async {
    for (final notification in state.notifications) {
      if (!notification.read) {
        await markRead(notification.id);
      }
    }
  }

  @override
  void dispose() {
    _wsSubscription?.cancel();
    super.dispose();
  }
}
