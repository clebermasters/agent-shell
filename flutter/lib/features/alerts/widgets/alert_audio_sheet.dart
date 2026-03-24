import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import '../../chat/widgets/chat_audio_tile.dart';

class AlertAudioSheet extends StatefulWidget {
  final Uint8List bytes;
  final String filename;
  final String mimeType;

  const AlertAudioSheet({
    super.key,
    required this.bytes,
    required this.filename,
    required this.mimeType,
  });

  @override
  State<AlertAudioSheet> createState() => _AlertAudioSheetState();
}

class _AlertAudioSheetState extends State<AlertAudioSheet> {
  late final AudioPlayer _player;
  double _volume = 1.0;
  bool _ready = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _player = AudioPlayer();
    _loadAudio();
  }

  Future<void> _loadAudio() async {
    try {
      await _player.setAudioSource(
        _BytesAudioSource(widget.bytes, widget.mimeType),
      );
      if (mounted) {
        setState(() => _ready = true);
        await _player.play();
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return DraggableScrollableSheet(
      initialChildSize: 0.35,
      minChildSize: 0.2,
      maxChildSize: 0.5,
      expand: false,
      builder: (context, scrollController) {
        return Container(
          decoration: BoxDecoration(
            color: Theme.of(context).scaffoldBackgroundColor,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
          ),
          child: ListView(
            controller: scrollController,
            padding: const EdgeInsets.all(16),
            children: [
              Center(
                child: Container(
                  width: 40,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 16),
                  decoration: BoxDecoration(
                    color: Colors.grey.shade400,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              if (_error != null)
                Center(
                  child: Text(
                    'Failed to load audio: $_error',
                    style: const TextStyle(color: Colors.red),
                  ),
                )
              else if (!_ready)
                const Center(child: CircularProgressIndicator())
              else
                _buildAudioPlayer(isDark),
            ],
          ),
        );
      },
    );
  }

  Widget _buildAudioPlayer(bool isDark) {
    return StreamBuilder<PlayerState>(
      stream: _player.playerStateStream,
      builder: (context, stateSnap) {
        final playerState = stateSnap.data;
        final playing = playerState?.playing ?? false;
        final processingState =
            playerState?.processingState ?? ProcessingState.idle;
        final isBuffering = processingState == ProcessingState.buffering ||
            processingState == ProcessingState.loading;
        final isCompleted = processingState == ProcessingState.completed;

        return StreamBuilder<Duration>(
          stream: _player.positionStream,
          builder: (context, posSnap) {
            final position = posSnap.data ?? Duration.zero;
            return StreamBuilder<Duration?>(
              stream: _player.bufferedPositionStream,
              builder: (context, bufSnap) {
                final buffered = bufSnap.data ?? Duration.zero;
                return StreamBuilder<Duration?>(
                  stream: _player.durationStream,
                  builder: (context, durSnap) {
                    final duration = durSnap.data;
                    return ChatAudioTile(
                      title: widget.filename,
                      textColor: isDark
                          ? Colors.grey.shade100
                          : const Color(0xFF1E293B),
                      isDark: isDark,
                      isActive: true,
                      isPlaying: playing && !isBuffering,
                      isLoading: isBuffering,
                      isCompleted: isCompleted,
                      position: position,
                      bufferedPosition: buffered,
                      totalDuration: duration,
                      onPrimaryPressed: () async {
                        if (isCompleted) {
                          await _player.seek(Duration.zero);
                          await _player.play();
                        } else if (playing) {
                          await _player.pause();
                        } else {
                          await _player.play();
                        }
                      },
                      onSeek: (d) => _player.seek(d),
                      onSkipBackward: () {
                        final target =
                            position - const Duration(seconds: 10);
                        _player.seek(
                          target < Duration.zero ? Duration.zero : target,
                        );
                      },
                      onSkipForward: () {
                        if (duration == null) return;
                        final target =
                            position + const Duration(seconds: 10);
                        _player.seek(
                          target > duration ? duration : target,
                        );
                      },
                      volume: _volume,
                      onVolumeChanged: (v) {
                        setState(() => _volume = v);
                        _player.setVolume(v);
                      },
                    );
                  },
                );
              },
            );
          },
        );
      },
    );
  }
}

class _BytesAudioSource extends StreamAudioSource {
  final Uint8List bytes;
  final String mimeType;

  _BytesAudioSource(this.bytes, this.mimeType);

  @override
  Future<StreamAudioResponse> request([int? start, int? end]) async {
    start ??= 0;
    end ??= bytes.length;
    return StreamAudioResponse(
      sourceLength: bytes.length,
      contentLength: end - start,
      offset: start,
      stream: Stream.value(bytes.sublist(start, end)),
      contentType: mimeType,
    );
  }
}
