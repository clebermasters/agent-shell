import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../data/models/connection_status.dart';
import '../../features/sessions/providers/sessions_provider.dart';

class ConnectionStatusBanner extends ConsumerStatefulWidget {
  const ConnectionStatusBanner({super.key});

  @override
  ConsumerState<ConnectionStatusBanner> createState() =>
      _ConnectionStatusBannerState();
}

class _ConnectionStatusBannerState
    extends ConsumerState<ConnectionStatusBanner> {
  bool _visible = false;
  ConnectionStatus? _status;
  Timer? _hideTimer;
  Timer? _countdownTimer;
  int _countdown = 5;

  @override
  void dispose() {
    _hideTimer?.cancel();
    _countdownTimer?.cancel();
    super.dispose();
  }

  void _onStatusChanged(ConnectionStatus? prev, ConnectionStatus next) {
    if (next == _status) return;

    _hideTimer?.cancel();
    _countdownTimer?.cancel();

    if (next == ConnectionStatus.connected) {
      setState(() {
        _status = next;
        _visible = true;
      });
      _hideTimer = Timer(const Duration(seconds: 2), () {
        if (mounted) setState(() => _visible = false);
      });
    } else if (next == ConnectionStatus.offline) {
      setState(() {
        _status = next;
        _visible = true;
        _countdown = 5;
      });
      _countdownTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() {
          _countdown = _countdown > 0 ? _countdown - 1 : 5;
        });
      });
    } else {
      setState(() {
        _status = next;
        _visible = true;
        _countdown = 5;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AsyncValue<ConnectionStatus>>(
      connectionStatusProvider,
      (prev, next) {
        next.whenData((status) =>
            _onStatusChanged(prev?.valueOrNull, status));
      },
    );

    final bool show = _visible && _status != null;

    Widget content;
    Color bgColor;

    final status = _status;
    if (status == ConnectionStatus.connected) {
      bgColor = Colors.green.shade700;
      content = const Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.check_circle_outline, color: Colors.white, size: 14),
          SizedBox(width: 6),
          Text(
            'Connected',
            style: TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      );
    } else if (status == ConnectionStatus.offline) {
      bgColor = Colors.red.shade700;
      content = Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.wifi_off, color: Colors.white, size: 14),
          const SizedBox(width: 6),
          Text(
            'Server unreachable — retrying in ${_countdown}s',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      );
    } else {
      bgColor = Colors.orange.shade700;
      final label = status == ConnectionStatus.reconnecting
          ? 'Reconnecting...'
          : 'Connecting...';
      content = Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const SizedBox(
            width: 12,
            height: 12,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            label,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      );
    }

    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      height: show ? 36 : 0,
      child: show
          ? AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              child: Container(
                key: ValueKey(status),
                width: double.infinity,
                color: bgColor,
                alignment: Alignment.center,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: content,
              ),
            )
          : const SizedBox.shrink(),
    );
  }
}
