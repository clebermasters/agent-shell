import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:just_audio/just_audio.dart';
import '../providers/dotfiles_provider.dart';
import '../../chat/widgets/chat_audio_tile.dart';

class FileMediaViewerScreen extends ConsumerStatefulWidget {
  const FileMediaViewerScreen({super.key});

  @override
  ConsumerState<FileMediaViewerScreen> createState() =>
      _FileMediaViewerScreenState();
}

class _FileMediaViewerScreenState extends ConsumerState<FileMediaViewerScreen> {
  AudioPlayer? _player;
  Uint8List? _loadedBytes;
  bool _playerReady = false;

  @override
  void dispose() {
    _player?.dispose();
    super.dispose();
  }

  Future<void> _initAudio(Uint8List bytes, String mimeType) async {
    if (_loadedBytes == bytes) return; // already loaded
    _loadedBytes = bytes;
    _player?.dispose();
    _player = AudioPlayer();
    try {
      await _player!.setAudioSource(_BytesAudioSource(bytes, mimeType));
      if (mounted) setState(() => _playerReady = true);
    } catch (_) {
      if (mounted) setState(() => _playerReady = false);
    }
  }

  bool get isDarkMode => Theme.of(context).brightness == Brightness.dark;
  Color get backgroundColor =>
      isDarkMode ? const Color(0xFF0F172A) : const Color(0xFFF8FAFC);
  Color get textPrimary =>
      isDarkMode ? Colors.grey.shade100 : const Color(0xFF1E293B);

  @override
  Widget build(BuildContext context) {
    final dotfilesState = ref.watch(dotfilesProvider);
    final fileName = dotfilesState.selectedFile?.name ?? '';
    final mimeType = dotfilesState.fileMimeType ?? '';
    final bytes = dotfilesState.binaryContent;
    final isLoading = dotfilesState.isLoading;
    final error = dotfilesState.error;

    final isImage = mimeType.startsWith('image/');
    final isAudio = mimeType.startsWith('audio/');

    return Scaffold(
      backgroundColor: backgroundColor,
      appBar: AppBar(
        backgroundColor:
            isDarkMode ? const Color(0xFF1E293B) : Colors.white,
        elevation: 0,
        title: Row(
          children: [
            Icon(
              isImage
                  ? Icons.image
                  : isAudio
                      ? Icons.audiotrack
                      : Icons.insert_drive_file,
              size: 18,
              color: isImage
                  ? Colors.green.shade400
                  : isAudio
                      ? Colors.purple.shade400
                      : Colors.grey,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                fileName,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: textPrimary,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
      body: _buildBody(
        isLoading: isLoading,
        bytes: bytes,
        mimeType: mimeType,
        isImage: isImage,
        isAudio: isAudio,
        error: error,
      ),
    );
  }

  Widget _buildBody({
    required bool isLoading,
    required Uint8List? bytes,
    required String mimeType,
    required bool isImage,
    required bool isAudio,
    required String? error,
  }) {
    if (isLoading && bytes == null) {
      return const Center(child: CircularProgressIndicator());
    }

    if (error != null || bytes == null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 48, color: Colors.red.shade400),
            const SizedBox(height: 12),
            Text(
              error ?? 'Failed to load file',
              style: TextStyle(color: Colors.red.shade400),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    if (isImage) {
      return InteractiveViewer(
        panEnabled: true,
        minScale: 0.5,
        maxScale: 6.0,
        child: Center(
          child: Image.memory(bytes, fit: BoxFit.contain),
        ),
      );
    }

    if (isAudio) {
      _initAudio(bytes, mimeType);
      return _buildAudioPlayer();
    }

    // Fallback for unknown binary types
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.insert_drive_file, size: 48,
              color: Colors.grey.shade500),
          const SizedBox(height: 12),
          Text(
            'Binary file (${bytes.length} bytes)',
            style: TextStyle(color: Colors.grey.shade500),
          ),
        ],
      ),
    );
  }

  Widget _buildAudioPlayer() {
    final player = _player;
    if (player == null || !_playerReady) {
      return const Center(child: CircularProgressIndicator());
    }

    final isDark = isDarkMode;

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: StreamBuilder<PlayerState>(
          stream: player.playerStateStream,
          builder: (context, stateSnap) {
            final playerState = stateSnap.data;
            final processingState =
                playerState?.processingState ?? ProcessingState.idle;
            final playing = playerState?.playing ?? false;
            final isBuffering = processingState == ProcessingState.buffering ||
                processingState == ProcessingState.loading;
            final isCompleted = processingState == ProcessingState.completed;

            return StreamBuilder<Duration>(
              stream: player.positionStream,
              builder: (context, posSnap) {
                final position = posSnap.data ?? Duration.zero;
                return StreamBuilder<Duration?>(
                  stream: player.bufferedPositionStream,
                  builder: (context, bufSnap) {
                    final buffered = bufSnap.data ?? Duration.zero;
                    return StreamBuilder<Duration?>(
                      stream: player.durationStream,
                      builder: (context, durSnap) {
                        final duration = durSnap.data;
                        return ChatAudioTile(
                          title: ref.read(dotfilesProvider).selectedFile?.name
                                  ?? '',
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
                              await player.seek(Duration.zero);
                              await player.play();
                            } else if (playing) {
                              await player.pause();
                            } else {
                              await player.play();
                            }
                          },
                          onSeek: (d) => player.seek(d),
                          onSkipBackward: () {
                            final target = position -
                                const Duration(seconds: 10);
                            player.seek(
                              target < Duration.zero
                                  ? Duration.zero
                                  : target,
                            );
                          },
                          onSkipForward: () {
                            if (duration == null) return;
                            final target = position +
                                const Duration(seconds: 10);
                            player.seek(
                              target > duration ? duration : target,
                            );
                          },
                        );
                      },
                    );
                  },
                );
              },
            );
          },
        ),
      ),
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
