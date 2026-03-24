import 'dart:async';
import 'dart:io' show Platform;
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';

Future<void> initializeBackgroundService() async {
  // Background service is only supported on Android and iOS
  if (!Platform.isAndroid && !Platform.isIOS) return;

  final service = FlutterBackgroundService();

  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
      FlutterLocalNotificationsPlugin();

  await flutterLocalNotificationsPlugin.initialize(
    settings: const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
    ),
  );

  final androidPlugin = flutterLocalNotificationsPlugin
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();

  await androidPlugin?.createNotificationChannel(
    const AndroidNotificationChannel(
      'agentshell_background',
      'AgentShell Background Service',
      description: 'This channel is used for maintaining the terminal connection.',
      importance: Importance.low,
    ),
  );

  await androidPlugin?.createNotificationChannel(
    const AndroidNotificationChannel(
      'agentshell_alerts',
      'AI Task Alerts',
      description: 'Notifications for AI agent task completions.',
      importance: Importance.high,
    ),
  );

  await androidPlugin?.createNotificationChannel(
    const AndroidNotificationChannel(
      'agentshell_chat',
      'Chat Notifications',
      description: 'Notifications from chat messages.',
      importance: Importance.high,
    ),
  );

  if (Platform.isAndroid) {
    await Permission.notification.request();
  }

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: false,
      isForegroundMode: true,
      notificationChannelId: 'agentshell_background',
      initialNotificationTitle: 'AgentShell',
      initialNotificationContent: 'Connected to terminal',
      foregroundServiceNotificationId: 888,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: false,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
  );
}

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  return true;
}

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  if (service is AndroidServiceInstance) {
    service.on('setAsForeground').listen((event) {
      service.setAsForegroundService();
    });
    service.on('setAsBackground').listen((event) {
      service.setAsBackgroundService();
    });
  }

  service.on('stopService').listen((event) {
    service.stopSelf();
  });

  Timer.periodic(const Duration(seconds: 1), (timer) async {
    if (service is AndroidServiceInstance) {
      if (await service.isForegroundService()) {}
    }
  });
}
