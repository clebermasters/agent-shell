import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:xterm/xterm.dart';
import '../providers/terminal_provider.dart';
import '../widgets/terminal_view_widget.dart';
import '../widgets/mobile_keyboard.dart';

class TerminalScreen extends ConsumerStatefulWidget {
  final String sessionName;

  const TerminalScreen({super.key, required this.sessionName});

  @override
  ConsumerState<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends ConsumerState<TerminalScreen> {
  final FocusNode _focusNode = FocusNode();
  bool _showKeyboard = false;
  bool _fullscreen = false;
  bool _showStatus = true;

  @override
  void initState() {
    super.initState();

    // Connect to the terminal session
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(terminalProvider.notifier).connect(widget.sessionName);
      // Auto-hide status bar after 3 seconds
      Future.delayed(const Duration(seconds: 3), () {
        if (mounted) {
          setState(() {
            _showStatus = false;
          });
        }
      });
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    super.dispose();
  }

  void _handleResize(int cols, int rows) {
    ref.read(terminalProvider.notifier).resize(widget.sessionName, cols, rows);
  }

  void _handleInput(String data) {
    ref.read(terminalProvider.notifier).sendData(widget.sessionName, data);
  }

  void _toggleFullscreen() {
    setState(() {
      _fullscreen = !_fullscreen;
    });
    if (_fullscreen) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    } else {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
  }

  @override
  Widget build(BuildContext context) {
    final terminalState = ref.watch(terminalProvider);

    return Scaffold(
      appBar: _fullscreen
          ? null
          : AppBar(
              title: Text(widget.sessionName),
              actions: [
                IconButton(
                  icon: Icon(
                    _showKeyboard ? Icons.keyboard_hide : Icons.keyboard,
                  ),
                  onPressed: () {
                    setState(() {
                      _showKeyboard = !_showKeyboard;
                    });
                  },
                  tooltip: _showKeyboard ? 'Hide Keyboard' : 'Show Keyboard',
                ),
                IconButton(
                  icon: Icon(
                    _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                  ),
                  onPressed: _toggleFullscreen,
                  tooltip: _fullscreen ? 'Exit Fullscreen' : 'Fullscreen',
                ),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  onPressed: () {
                    ref
                        .read(terminalProvider.notifier)
                        .connect(widget.sessionName);
                  },
                  tooltip: 'Reconnect',
                ),
              ],
            ),
      body: Column(
        children: [
          // Connection status bar - shown briefly then auto-hides
          AnimatedContainer(
            duration: const Duration(milliseconds: 300),
            height: _showStatus ? null : 0,
            child: _showStatus
                ? Container(
                    width: double.infinity,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    color: terminalState.isConnected
                        ? Colors.green
                        : (terminalState.isLoading
                              ? Colors.orange
                              : Colors.red),
                    child: Text(
                      terminalState.isLoading
                          ? 'Connecting...'
                          : terminalState.isConnected
                          ? 'Connected'
                          : terminalState.error ?? 'Disconnected',
                      style: const TextStyle(color: Colors.white),
                      textAlign: TextAlign.center,
                    ),
                  )
                : const SizedBox.shrink(),
          ),

          // Terminal view
          Expanded(
            child: terminalState.terminal != null
                ? GestureDetector(
                    onTap: () {
                      _focusNode.requestFocus();
                    },
                    onDoubleTap: _toggleFullscreen,
                    onLongPress: () {
                      setState(() {
                        _showStatus = !_showStatus;
                      });
                    },
                    child: TerminalViewWidget(
                      terminal: terminalState.terminal!,
                      onResize: _handleResize,
                      onInput: _handleInput,
                      focusNode: _focusNode,
                    ),
                  )
                : const Center(child: CircularProgressIndicator()),
          ),

          // Mobile keyboard (toggleable)
          if (_showKeyboard)
            MobileKeyboard(
              onKeyPressed: _handleInput,
              onClose: () {
                setState(() {
                  _showKeyboard = false;
                });
              },
            ),
        ],
      ),
    );
  }
}
