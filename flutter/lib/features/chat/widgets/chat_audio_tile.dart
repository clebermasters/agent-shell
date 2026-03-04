import 'package:flutter/material.dart';

class ChatAudioTile extends StatefulWidget {
  final String title;
  final Color textColor;
  final bool isDark;
  final bool isActive;
  final bool isPlaying;
  final bool isLoading;
  final bool isCompleted;
  final Duration position;
  final Duration bufferedPosition;
  final Duration? totalDuration;
  final String? inlineMessage;
  final bool inlineMessageIsError;
  final VoidCallback? onPrimaryPressed;
  final ValueChanged<Duration>? onSeek;
  final VoidCallback? onSkipBackward;
  final VoidCallback? onSkipForward;

  const ChatAudioTile({
    super.key,
    required this.title,
    required this.textColor,
    required this.isDark,
    required this.isActive,
    required this.isPlaying,
    required this.isLoading,
    required this.isCompleted,
    required this.position,
    required this.bufferedPosition,
    required this.totalDuration,
    this.inlineMessage,
    this.inlineMessageIsError = false,
    this.onPrimaryPressed,
    this.onSeek,
    this.onSkipBackward,
    this.onSkipForward,
  });

  @override
  State<ChatAudioTile> createState() => _ChatAudioTileState();
}

class _ChatAudioTileState extends State<ChatAudioTile> {
  double? _dragPositionMillis;

  @override
  void didUpdateWidget(covariant ChatAudioTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.isActive) {
      _dragPositionMillis = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final accentColor = widget.isDark
        ? const Color(0xFF6EE7B7)
        : const Color(0xFF047857);
    final fallbackDuration = widget.totalDuration;
    final displayPosition = _dragPositionMillis != null
        ? Duration(milliseconds: _dragPositionMillis!.round())
        : widget.position;
    final clampedPosition = _clampDuration(displayPosition, fallbackDuration);
    final clampedBuffered = _clampDuration(
      widget.bufferedPosition,
      fallbackDuration,
    );
    final totalMillis = (fallbackDuration?.inMilliseconds ?? 0).toDouble();
    final sliderValue = totalMillis > 0
        ? clampedPosition.inMilliseconds
              .toDouble()
              .clamp(0, totalMillis)
              .toDouble()
        : 0.0;
    final canSeek =
        widget.isActive &&
        !widget.isLoading &&
        totalMillis > 0 &&
        widget.onSeek != null;
    final playedFraction = totalMillis > 0 ? sliderValue / totalMillis : 0.0;
    final bufferedFraction = totalMillis > 0
        ? clampedBuffered.inMilliseconds / totalMillis
        : 0.0;
    final hasSkipActions =
        widget.isActive &&
        !widget.isLoading &&
        widget.totalDuration != null &&
        widget.totalDuration! > Duration.zero;

    return Container(
      decoration: BoxDecoration(
        color: widget.isDark
            ? const Color(0xFF1E293B)
            : const Color(0xFFF1F5F9),
        borderRadius: BorderRadius.circular(12),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          IconButton(
            icon: _buildPrimaryIcon(accentColor),
            iconSize: 36,
            onPressed: widget.onPrimaryPressed,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  widget.title,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: widget.textColor,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (widget.inlineMessage != null) ...[
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Icon(
                        widget.inlineMessageIsError
                            ? Icons.error_outline
                            : Icons.sync,
                        size: 12,
                        color: widget.inlineMessageIsError
                            ? Colors.red.shade400
                            : widget.textColor.withValues(alpha: 0.75),
                      ),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          widget.inlineMessage!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 10,
                            color: widget.inlineMessageIsError
                                ? Colors.red.shade400
                                : widget.textColor.withValues(alpha: 0.75),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 4),
                SizedBox(
                  height: 20,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Positioned(
                        left: 0,
                        right: 0,
                        child: _buildProgressTrack(
                          playedFraction: playedFraction,
                          bufferedFraction: bufferedFraction
                              .clamp(playedFraction, 1.0)
                              .toDouble(),
                          backgroundColor: widget.textColor.withValues(
                            alpha: 0.14,
                          ),
                          bufferedColor: accentColor.withValues(alpha: 0.32),
                          playedColor: accentColor,
                        ),
                      ),
                      SliderTheme(
                        data: SliderTheme.of(context).copyWith(
                          trackHeight: 0.01,
                          activeTrackColor: Colors.transparent,
                          inactiveTrackColor: Colors.transparent,
                          thumbColor: accentColor,
                          overlayColor: accentColor.withValues(alpha: 0.18),
                          thumbShape: const RoundSliderThumbShape(
                            enabledThumbRadius: 5,
                          ),
                          overlayShape: const RoundSliderOverlayShape(
                            overlayRadius: 12,
                          ),
                        ),
                        child: Slider(
                          value: sliderValue,
                          max: totalMillis > 0 ? totalMillis : 1,
                          onChangeStart: canSeek
                              ? (value) {
                                  setState(() {
                                    _dragPositionMillis = value;
                                  });
                                }
                              : null,
                          onChanged: canSeek
                              ? (value) {
                                  setState(() {
                                    _dragPositionMillis = value;
                                  });
                                }
                              : null,
                          onChangeEnd: canSeek
                              ? (value) {
                                  setState(() {
                                    _dragPositionMillis = null;
                                  });
                                  widget.onSeek?.call(
                                    Duration(milliseconds: value.round()),
                                  );
                                }
                              : null,
                        ),
                      ),
                    ],
                  ),
                ),
                Row(
                  children: [
                    IconButton(
                      icon: Icon(
                        Icons.replay_10,
                        size: 18,
                        color: widget.textColor.withValues(alpha: 0.8),
                      ),
                      onPressed: hasSkipActions ? widget.onSkipBackward : null,
                      visualDensity: VisualDensity.compact,
                      constraints: const BoxConstraints(
                        minWidth: 26,
                        minHeight: 26,
                      ),
                      padding: EdgeInsets.zero,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      _formatDuration(clampedPosition),
                      style: TextStyle(
                        fontSize: 10,
                        color: widget.textColor.withValues(alpha: 0.65),
                      ),
                    ),
                    const Spacer(),
                    Text(
                      _formatDuration(fallbackDuration),
                      style: TextStyle(
                        fontSize: 10,
                        color: widget.textColor.withValues(alpha: 0.65),
                      ),
                    ),
                    const SizedBox(width: 4),
                    IconButton(
                      icon: Icon(
                        Icons.forward_10,
                        size: 18,
                        color: widget.textColor.withValues(alpha: 0.8),
                      ),
                      onPressed: hasSkipActions ? widget.onSkipForward : null,
                      visualDensity: VisualDensity.compact,
                      constraints: const BoxConstraints(
                        minWidth: 26,
                        minHeight: 26,
                      ),
                      padding: EdgeInsets.zero,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPrimaryIcon(Color accentColor) {
    if (widget.isActive && widget.isLoading) {
      return SizedBox(
        width: 20,
        height: 20,
        child: CircularProgressIndicator(strokeWidth: 2, color: accentColor),
      );
    }
    if (widget.isActive && widget.isPlaying) {
      return Icon(Icons.pause_circle, color: accentColor);
    }
    if (widget.isActive && widget.isCompleted) {
      return Icon(Icons.replay_circle_filled, color: accentColor);
    }
    return Icon(Icons.play_circle, color: accentColor);
  }

  Widget _buildProgressTrack({
    required double playedFraction,
    required double bufferedFraction,
    required Color backgroundColor,
    required Color bufferedColor,
    required Color playedColor,
  }) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(3),
      child: SizedBox(
        height: 3,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Container(color: backgroundColor),
            FractionallySizedBox(
              alignment: Alignment.centerLeft,
              widthFactor: bufferedFraction,
              child: Container(color: bufferedColor),
            ),
            FractionallySizedBox(
              alignment: Alignment.centerLeft,
              widthFactor: playedFraction,
              child: Container(color: playedColor),
            ),
          ],
        ),
      ),
    );
  }

  Duration _clampDuration(Duration value, Duration? max) {
    if (value < Duration.zero) return Duration.zero;
    if (max == null) return value;
    if (value > max) return max;
    return value;
  }

  String _formatDuration(Duration? duration) {
    if (duration == null) return '--:--';
    final totalSeconds = duration.inSeconds;
    final mins = totalSeconds ~/ 60;
    final secs = totalSeconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
  }
}
