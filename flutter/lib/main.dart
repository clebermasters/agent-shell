import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/theme/app_theme.dart';
import 'core/config/app_config.dart';
import 'core/config/build_config.dart';
import 'core/providers.dart';
import 'core/services/background_service.dart';
import 'features/alerts/screens/alerts_screen.dart';
import 'features/auth/screens/login_screen.dart';
import 'features/debug/screens/debug_screen.dart';
import 'features/home/screens/home_screen.dart';

final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final prefs = await SharedPreferences.getInstance();

  // Initialize build-time API key if not already set
  final existingApiKey = prefs.getString(AppConfig.keyOpenAiApiKey);
  if ((existingApiKey == null || existingApiKey.isEmpty) &&
      BuildConfig.defaultApiKey.isNotEmpty) {
    await prefs.setString(AppConfig.keyOpenAiApiKey, BuildConfig.defaultApiKey);
  }

  await initializeBackgroundService();

  runApp(
    ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: AgentShellApp(prefs: prefs),
    ),
  );

  // Handle cold-start notification tap (app was terminated)
  if (!kIsWeb) {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final details = await FlutterLocalNotificationsPlugin()
          .getNotificationAppLaunchDetails();
      final payload = details?.notificationResponse?.payload ?? '';
      if (details?.didNotificationLaunchApp == true &&
          payload.startsWith('alert:')) {
        navigatorKey.currentState?.push(
          MaterialPageRoute(builder: (_) => const AlertsScreen()),
        );
      }
    });
  }
}

class AgentShellApp extends ConsumerWidget {
  final SharedPreferences prefs;
  const AgentShellApp({super.key, required this.prefs});

  Widget _getStartScreen() {
    if (kIsWeb) {
      final storedToken = prefs.getString(AppConfig.keyWebAuthToken);
      if (storedToken == null || storedToken.isEmpty) {
        return const LoginScreen();
      }
    }
    return const HomeScreen(showDebug: true);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp(
      title: AppConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: ThemeMode.dark,
      navigatorKey: navigatorKey,
      home: _getStartScreen(),
    );
  }
}
