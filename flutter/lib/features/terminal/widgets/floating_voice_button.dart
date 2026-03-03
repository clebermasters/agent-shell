import 'dart:async';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class FloatingVoiceButton extends StatefulWidget {
  final bool isRecording;
  final bool isTranscribing;
  final Duration recordingDuration;
  final VoidCallback onPressed;
  final SharedPreferences prefs;

  const FloatingVoiceButton({
    super.key,
    required this.isRecording,
    required this.isTranscribing,
    required this.recordingDuration,
    required this.onPressed,
    required this.prefs,
  });

  @override
  State<FloatingVoiceButton> createState() => _FloatingVoiceButtonState();
}

class _FloatingVoiceButtonState extends State<FloatingVoiceButton> {
  static const String _posXKey = 'voice_button_pos_x';
  static const String _posYKey = 'voice_button_pos_y';

  late double _posX;
  late double _posY;
  bool _isDragging = false;

  @override
  void initState() {
    super.initState();
    _loadPosition();
  }

  void _loadPosition() {
    _posX = widget.prefs.getDouble(_posXKey) ?? -1;
    _posY = widget.prefs.getDouble(_posYKey) ?? -1;

    if (_posX < 0 || _posY < 0) {
      final size = MediaQuery.of(context).size;
      _posX = size.width - 64;
      _posY = size.height - 150;
    }
  }

  void _savePosition() {
    widget.prefs.setDouble(_posXKey, _posX);
    widget.prefs.setDouble(_posYKey, _posY);
  }

  String _formatDuration(Duration duration) {
    final minutes = duration.inMinutes.toString().padLeft(2, '0');
    final seconds = (duration.inSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    return Positioned(
      left: _posX,
      top: _posY,
      child: GestureDetector(
        onPanStart: (_) => setState(() => _isDragging = true),
        onPanEnd: (_) {
          setState(() => _isDragging = false);
          _savePosition();
        },
        onPanUpdate: (details) {
          setState(() {
            _posX += details.delta.dx;
            _posY += details.delta.dy;

            final size = MediaQuery.of(context).size;
            _posX = _posX.clamp(0, size.width - 48);
            _posY = _posY.clamp(0, size.height - 48);
          });
        },
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          width: widget.isRecording ? 56 : 48,
          height: widget.isRecording ? 56 : 48,
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: widget.isTranscribing ? null : widget.onPressed,
              borderRadius: BorderRadius.circular(24),
              child: Container(
                decoration: BoxDecoration(
                  color: widget.isRecording
                      ? Colors.red
                      : (widget.isTranscribing
                            ? Colors.grey
                            : const Color(0xFF6366F1)),
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color:
                          (widget.isRecording
                                  ? Colors.red
                                  : const Color(0xFF6366F1))
                              .withValues(alpha: 0.4),
                      blurRadius: _isDragging ? 12 : 8,
                      offset: const Offset(0, 2),
                    ),
                  ],
                ),
                child: Center(
                  child: widget.isTranscribing
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(
                              widget.isRecording ? Icons.stop : Icons.mic,
                              size: 20,
                              color: Colors.white,
                            ),
                            if (widget.isRecording) ...[
                              const SizedBox(height: 2),
                              Text(
                                _formatDuration(widget.recordingDuration),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 8,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ],
                        ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class VoiceButtonController extends ChangeNotifier {
  bool _isRecording = false;
  bool _isTranscribing = false;
  Duration _recordingDuration = Duration.zero;
  Timer? _recordingTimer;

  bool get isRecording => _isRecording;
  bool get isTranscribing => _isTranscribing;
  Duration get recordingDuration => _recordingDuration;

  void startRecording() {
    _isRecording = true;
    _recordingDuration = Duration.zero;
    _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _recordingDuration += const Duration(seconds: 1);
      notifyListeners();
    });
    notifyListeners();
  }

  void stopRecording() {
    _recordingTimer?.cancel();
    _recordingTimer = null;
    _isRecording = false;
    notifyListeners();
  }

  void startTranscribing() {
    _isTranscribing = true;
    notifyListeners();
  }

  void stopTranscribing() {
    _isTranscribing = false;
    notifyListeners();
  }

  void reset() {
    _recordingTimer?.cancel();
    _recordingTimer = null;
    _isRecording = false;
    _isTranscribing = false;
    _recordingDuration = Duration.zero;
    notifyListeners();
  }

  @override
  void dispose() {
    _recordingTimer?.cancel();
    super.dispose();
  }
}
