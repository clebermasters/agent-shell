import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:permission_handler/permission_handler.dart';
import '../providers/terminal_provider.dart';
import '../widgets/terminal_view_widget.dart';
import '../widgets/terminal_selection_overlay.dart';
import '../widgets/mobile_keyboard.dart';
import '../widgets/terminal_accessory_bar.dart';
import '../widgets/floating_voice_button.dart';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../chat/screens/chat_screen.dart';
import '../../sessions/providers/sessions_provider.dart';
import '../../file_browser/providers/file_browser_provider.dart';
import '../../file_browser/screens/file_browser_screen.dart';

class TerminalScreen extends ConsumerStatefulWidget {
  final String sessionName;
  final int windowIndex;

  const TerminalScreen({
    super.key,
    required this.sessionName,
    this.windowIndex = 0,
  });

  @override
  ConsumerState<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends ConsumerState<TerminalScreen>
    with WidgetsBindingObserver {
  final FocusNode _focusNode = FocusNode(debugLabel: 'TerminalMainFocus');
  bool _showCustomKeyboard = false;
  bool _fullscreen = false;
  bool _showStatus = true;
  bool _wasKeyboardVisible = false;
  bool _lastKeyboardVisible = false;

  // Selection Mode state
  bool _isSelectionMode = false;

  // Swipe-between-sessions state
  double _swipeDx = 0;
  String? _swipeHintName;
  bool _showDots = false;

  // Voice button visibility (persisted)
  bool _showVoiceButton = true;

  // Modifier states for accessory bar + native keyboard
  bool _ctrlActive = false;
  bool _altActive = false;
  bool _shiftActive = false;

  final Map<String, String> _shiftMap = {
    '1': '!',
    '2': '@',
    '3': '#',
    '4': '\$',
    '5': '%',
    '6': '^',
    '7': '&',
    '8': '*',
    '9': '(',
    '0': ')',
    '-': '_',
    '=': '+',
    '[': '{',
    ']': '}',
    '\\': '|',
    ';': ':',
    '\'': '"',
    ',': '<',
    '.': '>',
    '/': '?',
    '`': '~',
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _focusNode.addListener(_onFocusChange);

    // Load voice button visibility preference
    SharedPreferences.getInstance().then((prefs) {
      if (mounted) {
        setState(() {
          _showVoiceButton =
              prefs.getBool(AppConfig.keyShowVoiceButton) ?? true;
        });
      }
    });

    // Connect to the terminal session
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final terminalNotifier = ref.read(terminalProvider.notifier);
      terminalNotifier.connect(
        widget.sessionName,
        windowIndex: widget.windowIndex,
      );

      // CRITICAL: Register custom input processor to handle sticky modifiers
      terminalNotifier.terminalService.setInputProcessor(_processInput);

      _persistActiveSession();

      Future.delayed(const Duration(seconds: 3), () {
        if (mounted) {
          setState(() {
            _showStatus = false;
          });
        }
      });

      // Initial focus
      _focusNode.requestFocus();
    });
  }

  void _onFocusChange() {}

  @override
  void dispose() {
    _focusNode.removeListener(_onFocusChange);
    WidgetsBinding.instance.removeObserver(this);
    _clearActiveSession();
    ref.read(terminalProvider.notifier).disconnect();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _wasKeyboardVisible = MediaQuery.of(context).viewInsets.bottom > 0;
    } else if (state == AppLifecycleState.resumed) {
      // Ensure the websocket is alive or force it to reconnect
      ref.read(terminalProvider.notifier).checkConnection();

      if (_wasKeyboardVisible && !_showCustomKeyboard && !_isSelectionMode) {
        Future.delayed(const Duration(milliseconds: 500), () {
          if (mounted) {
            _focusNode.requestFocus();
            SystemChannels.textInput.invokeMethod('TextInput.show');
          }
        });
      }
    }
  }

  // This method catches input from TerminalView
  // and applies our custom modifiers before sending to backend.
  void _processInput(String session, String data) {
    if (_isSelectionMode) return;

    String finalData = data;
    bool wasModified = false;

    // Apply soft modifiers for single character inputs (from native keyboard)
    if (data.length == 1 && (_ctrlActive || _altActive || _shiftActive)) {
      String char = data;
      wasModified = true;

      if (_shiftActive) {
        if (_shiftMap.containsKey(char)) {
          finalData = _shiftMap[char]!;
        } else {
          finalData = char.toUpperCase();
        }
      }

      if (_ctrlActive) {
        int code = finalData.toUpperCase().codeUnitAt(0);
        if (code >= 64 && code <= 95) {
          finalData = String.fromCharCode(code - 64);
        } else if (finalData == ' ') {
          finalData = '\x00';
        }
      }

      if (_altActive) {
        finalData = '\x1b$finalData';
      }
    }

    // Send to backend via terminal provider
    ref.read(terminalProvider.notifier).sendData(session, finalData);

    // Reset soft modifiers if they were used
    if (wasModified) {
      setState(() {
        _ctrlActive = false;
        _altActive = false;
        _shiftActive = false;
      });
    }
  }

  Future<void> _persistActiveSession() async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setString('active_terminal_session', widget.sessionName);
  }

  Future<void> _clearActiveSession() async {
    final prefs = ref.read(sharedPreferencesProvider);
    if (prefs.getString('active_terminal_session') == widget.sessionName) {
      await prefs.remove('active_terminal_session');
    }
  }

  void _switchToAdjacentSession(int direction) {
    final sessions = ref.read(sessionsProvider).sessions;
    if (sessions.isEmpty) return;
    final currentIndex = sessions.indexWhere((s) => s.name == widget.sessionName);
    if (currentIndex == -1) return;
    final nextIndex = (currentIndex + direction) % sessions.length;
    final nextSession = sessions[nextIndex].name;
    Navigator.of(context).pushReplacement(MaterialPageRoute(
      builder: (_) => TerminalScreen(sessionName: nextSession),
    ));
  }

  void _showRenameDialog() {
    final controller = TextEditingController(text: widget.sessionName);

    void doRename(String newName, NavigatorState nav) {
      if (newName.isNotEmpty && newName != widget.sessionName) {
        ref.read(sharedWebSocketServiceProvider).renameSession(widget.sessionName, newName);
      }
      nav.pop();
    }

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rename Session'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'New Name'),
          onSubmitted: (value) => doRename(value.trim(), Navigator.of(ctx)),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('Cancel')),
          ElevatedButton(
            onPressed: () => doRename(controller.text.trim(), Navigator.of(ctx)),
            child: const Text('Rename'),
          ),
        ],
      ),
    ).then((_) => controller.dispose());
  }

  void _handleResize(int cols, int rows) {
    ref.read(terminalProvider.notifier).resize(widget.sessionName, cols, rows);
  }

  void _handleInput(String data) {
    ref.read(terminalProvider.notifier).sendData(widget.sessionName, data);
  }

  void _openFileBrowser() {
    final ws = ref.read(sharedWebSocketServiceProvider);
    final notifier = ref.read(fileBrowserProvider.notifier);
    late final StreamSubscription<Map<String, dynamic>> sub;
    Timer? timeoutTimer;
    sub = ws.messages.listen((msg) {
      if (msg['type'] == 'session-cwd') {
        final cwd = msg['cwd'] as String? ?? '';
        timeoutTimer?.cancel();
        sub.cancel();
        if (mounted && cwd.isNotEmpty) {
          notifier.listFiles(cwd);
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => FileBrowserScreen(initialPath: cwd),
            ),
          );
        }
      }
    });
    // Cancel the subscription if server doesn't respond within 5 seconds
    timeoutTimer = Timer(const Duration(seconds: 5), sub.cancel);
    ws.getSessionCwd(widget.sessionName);
  }

  void _toggleVoiceButton() {
    final next = !_showVoiceButton;
    setState(() => _showVoiceButton = next);
    SharedPreferences.getInstance().then(
      (prefs) => prefs.setBool(AppConfig.keyShowVoiceButton, next),
    );
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

  void _toggleKeyboardType() {
    setState(() {
      _showCustomKeyboard = !_showCustomKeyboard;
      if (_showCustomKeyboard) {
        _focusNode.unfocus();
        SystemChannels.textInput.invokeMethod('TextInput.hide');
        _isSelectionMode = false;
      } else {
        _focusNode.requestFocus();
        SystemChannels.textInput.invokeMethod('TextInput.show');
      }
    });
  }

  void _toggleSelectionMode() {
    setState(() {
      _isSelectionMode = !_isSelectionMode;
      if (_isSelectionMode) {
        _focusNode.unfocus();
        SystemChannels.textInput.invokeMethod('TextInput.hide');
      } else {
        _focusNode.requestFocus();
        SystemChannels.textInput.invokeMethod('TextInput.show');
      }
    });
  }

  void _handlePaste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data?.text != null) {
      _handleInput(data!.text!);
    }
  }

  double _getTerminalFontSize() {
    final prefs = ref.read(sharedPreferencesProvider);
    return prefs.getDouble(AppConfig.keyTerminalFontSize) ??
        AppConfig.terminalFontSize;
  }

  Future<void> _handleVoiceButton(TerminalState terminalState) async {
    final terminalNotifier = ref.read(terminalProvider.notifier);

    if (terminalState.isRecording) {
      final audioPath = await terminalNotifier.stopVoiceRecording();
      if (audioPath != null) {
        final text = await terminalNotifier.transcribeAudio(audioPath);
        if (text != null && text.isNotEmpty) {
          terminalNotifier.injectText(text);
        }
      }
    } else {
      final status = await Permission.microphone.request();
      if (status.isGranted) {
        await terminalNotifier.startVoiceRecording();
      } else if (status.isPermanentlyDenied) {
        _showPermissionDeniedDialog();
      }
    }
  }

  void _showPermissionDeniedDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Microphone Permission'),
        content: const Text(
          'Microphone permission is required to use voice input. '
          'Please enable it in your device settings.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  Widget _buildDotIndicator() {
    final sessions = ref.read(sessionsProvider).sessions;
    final currentIndex = sessions.indexWhere((s) => s.name == widget.sessionName);
    if (sessions.length < 2 || currentIndex == -1) return const SizedBox.shrink();

    return Center(
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(sessions.length, (i) {
          final active = i == currentIndex;
          return Container(
            width: active ? 8 : 6,
            height: active ? 8 : 6,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active
                  ? Colors.white.withOpacity(0.9)
                  : Colors.white.withOpacity(0.35),
            ),
          );
        }),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final terminalState = ref.watch(terminalProvider);
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final isNativeKeyboardVisible = bottomInset > 0;

    // When the keyboard hides, the terminal gets more vertical space.
    // Schedule a resize so the backend/tmux learns about the new row count.
    if (_lastKeyboardVisible && !isNativeKeyboardVisible) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        final terminal = ref.read(terminalProvider).terminal;
        if (terminal != null) {
          final cols = terminal.viewWidth;
          final rows = terminal.viewHeight;
          if (cols > 0 && rows > 0) {
            _handleResize(cols, rows);
          }
        }
      });
    }
    _lastKeyboardVisible = isNativeKeyboardVisible;

    return Scaffold(
      resizeToAvoidBottomInset: true,
      appBar: _fullscreen
          ? null
          : AppBar(
              title: GestureDetector(
                onLongPress: _showRenameDialog,
                child: Text(widget.sessionName),
              ),
              actions: [
                IconButton(
                  icon: const Icon(Icons.chat_bubble_outline, size: 20),
                  onPressed: () {
                    Navigator.of(context).pushReplacement(
                      MaterialPageRoute(
                        builder: (context) => ChatScreen(
                          sessionName: widget.sessionName,
                          windowIndex: widget.windowIndex,
                        ),
                      ),
                    );
                  },
                  tooltip: 'Switch to Chat',
                ),
                IconButton(
                  icon: Icon(
                    _isSelectionMode ? Icons.select_all : Icons.ads_click,
                    size: 20,
                  ),
                  onPressed: _toggleSelectionMode,
                  color: _isSelectionMode ? Colors.orange : null,
                  tooltip: _isSelectionMode
                      ? 'Exit Selection'
                      : 'Selection Mode',
                ),
                IconButton(
                  icon: const Icon(Icons.paste, size: 20),
                  onPressed: _handlePaste,
                  tooltip: 'Paste',
                ),

                const VerticalDivider(width: 8),

                IconButton(
                  icon: const Icon(Icons.folder_open, size: 20),
                  onPressed: _openFileBrowser,
                  tooltip: 'Browse Files',
                ),
                IconButton(
                  icon: Icon(
                    _showVoiceButton ? Icons.mic : Icons.mic_off,
                    size: 20,
                  ),
                  onPressed: _toggleVoiceButton,
                  color: _showVoiceButton ? null : Colors.grey,
                  tooltip: _showVoiceButton
                      ? 'Hide Voice Button'
                      : 'Show Voice Button',
                ),
                IconButton(
                  icon: Icon(
                    _showCustomKeyboard
                        ? Icons.keyboard
                        : Icons.keyboard_alt_outlined,
                    size: 20,
                  ),
                  onPressed: _toggleKeyboardType,
                  tooltip: _showCustomKeyboard
                      ? 'Use Native Keyboard'
                      : 'Use Custom Keyboard',
                ),
                IconButton(
                  icon: Icon(
                    _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                    size: 20,
                  ),
                  onPressed: _toggleFullscreen,
                  tooltip: _fullscreen ? 'Exit Fullscreen' : 'Fullscreen',
                ),
              ],
            ),
      body: PopScope(
        onPopInvokedWithResult: (didPop, result) {
          if (didPop) {
            _clearActiveSession();
          }
        },
        child: GestureDetector(
          onHorizontalDragUpdate: _isSelectionMode
              ? null
              : (details) {
                  final sessions = ref.read(sessionsProvider).sessions;
                  if (sessions.length < 2) return;
                  setState(() {
                    _showDots = true;
                    final idx = sessions.indexWhere((s) => s.name == widget.sessionName);
                    if (idx == -1) return;
                    final atStart = idx == 0;
                    final atEnd = idx == sessions.length - 1;
                    if ((atStart && details.delta.dx > 0) || (atEnd && details.delta.dx < 0)) {
                      _swipeDx += details.delta.dx * 0.25;
                      _swipeHintName = atStart ? '⟵ (start)' : '(end) ⟶';
                    } else {
                      _swipeDx += details.delta.dx;
                      if (_swipeDx < -60) {
                        final next = sessions[(idx + 1) % sessions.length].name;
                        _swipeHintName = '→ $next';
                      } else if (_swipeDx > 60) {
                        final prev = sessions[(idx - 1 + sessions.length) % sessions.length].name;
                        _swipeHintName = '← $prev';
                      } else {
                        _swipeHintName = null;
                      }
                    }
                  });
                },
          onHorizontalDragEnd: _isSelectionMode
              ? null
              : (details) {
                  final dx = _swipeDx;
                  final sessions = ref.read(sessionsProvider).sessions;
                  final currentIndex = sessions.indexWhere((s) => s.name == widget.sessionName);
                  final atStart = currentIndex == 0 && dx > 0;
                  final atEnd = currentIndex == sessions.length - 1 && dx < 0;
                  setState(() {
                    _swipeDx = 0;
                    _swipeHintName = null;
                  });
                  if (atStart || atEnd) {
                    Future.delayed(const Duration(milliseconds: 600), () {
                      if (mounted) setState(() => _showDots = false);
                    });
                    return;
                  }
                  if (dx < -120) {
                    _switchToAdjacentSession(1);
                  } else if (dx > 120) {
                    _switchToAdjacentSession(-1);
                  }
                  Future.delayed(const Duration(milliseconds: 600), () {
                    if (mounted) setState(() => _showDots = false);
                  });
                },
          onHorizontalDragCancel: _isSelectionMode
              ? null
              : () {
                  setState(() {
                    _swipeDx = 0;
                    _swipeHintName = null;
                    _showDots = false;
                  });
                },
          child: Stack(
          children: [
            Column(
              children: [
                // Connection status bar
                AnimatedContainer(
                  duration: const Duration(milliseconds: 300),
                  height: (_showStatus || !terminalState.isConnected)
                      ? null
                      : 0,
                  child: (_showStatus || !terminalState.isConnected)
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

                // Scrollback hydration indicator
                if (terminalState.isHydrating)
                  const LinearProgressIndicator(minHeight: 2),

                // Terminal view / Selection overlay
                Expanded(
                  child: terminalState.terminal != null
                      ? Stack(
                          children: [
                            // Terminal always in tree (receives data)
                            TerminalViewWidget(
                              terminal: terminalState.terminal!,
                              controller: terminalState.controller,
                              onResize: _handleResize,
                              onInput: _handleInput,
                              focusNode: _focusNode,
                              ctrlActive: _ctrlActive,
                              altActive: _altActive,
                              shiftActive: _shiftActive,
                              isSelectionMode: _isSelectionMode,
                              prefs: ref.watch(sharedPreferencesProvider),
                              onTap: () {
                                if (!_showCustomKeyboard &&
                                    !_isSelectionMode) {
                                  final isKeyboardVisible =
                                      MediaQuery.of(context)
                                          .viewInsets
                                          .bottom >
                                      0;

                                  if (!isKeyboardVisible) {
                                    if (_focusNode.hasFocus) {
                                      _focusNode.unfocus();
                                      Future.delayed(
                                        const Duration(milliseconds: 50),
                                        () {
                                          if (mounted) {
                                            _focusNode.requestFocus();
                                            SystemChannels.textInput
                                                .invokeMethod(
                                              'TextInput.show',
                                            );
                                          }
                                        },
                                      );
                                    } else {
                                      _focusNode.requestFocus();
                                      SystemChannels.textInput.invokeMethod(
                                        'TextInput.show',
                                      );
                                    }
                                  } else {
                                    if (!_focusNode.hasFocus) {
                                      _focusNode.requestFocus();
                                    }
                                    SystemChannels.textInput.invokeMethod(
                                      'TextInput.show',
                                    );
                                  }
                                }
                              },
                              onModifiersReset: () {
                                setState(() {
                                  _ctrlActive = false;
                                  _altActive = false;
                                  _shiftActive = false;
                                });
                              },
                            ),

                            // Selection overlay on top
                            if (_isSelectionMode)
                              Positioned.fill(
                                child: TerminalSelectionOverlay(
                                  terminal: terminalState.terminal!,
                                  fontSize: _getTerminalFontSize(),
                                  onClose: _toggleSelectionMode,
                                ),
                              ),
                          ],
                        )
                      : const Center(child: CircularProgressIndicator()),
                ),

                // Accessory Bar (for native keyboard)
                if (!_showCustomKeyboard && isNativeKeyboardVisible)
                  TerminalAccessoryBar(
                    onKeyPressed: _handleInput,
                    onToggleKeyboard: () {
                      _focusNode.unfocus();
                      SystemChannels.textInput.invokeMethod('TextInput.hide');
                    },
                    isCtrlActive: _ctrlActive,
                    isAltActive: _altActive,
                    isShiftActive: _shiftActive,
                    onModifierTap: (mod) {
                      setState(() {
                        if (mod == 'CTRL') _ctrlActive = !_ctrlActive;
                        if (mod == 'ALT') _altActive = !_altActive;
                        if (mod == 'SHIFT') _shiftActive = !_shiftActive;
                      });
                    },
                    onModifiersReset: () {
                      setState(() {
                        _ctrlActive = false;
                        _altActive = false;
                        _shiftActive = false;
                      });
                    },
                  ),

                // Custom virtual keyboard
                if (_showCustomKeyboard)
                  MobileKeyboard(
                    onKeyPressed: _handleInput,
                    onClose: () {
                      setState(() {
                        _showCustomKeyboard = false;
                      });
                    },
                  ),
              ],
            ),

            // Floating Voice Button (only when enabled by user)
            if (_showVoiceButton)
              FutureBuilder<SharedPreferences>(
                future: SharedPreferences.getInstance(),
                builder: (context, snapshot) {
                  if (!snapshot.hasData) return const SizedBox.shrink();
                  return FloatingVoiceButton(
                    prefs: snapshot.data!,
                    isRecording: terminalState.isRecording,
                    isTranscribing: terminalState.isTranscribing,
                    recordingDuration: terminalState.recordingDuration,
                    onPressed: () => _handleVoiceButton(terminalState),
                  );
                },
              ),

            // Dot position indicator
            if (_showDots)
              Positioned(
                top: 36,
                left: 0,
                right: 0,
                child: AnimatedOpacity(
                  opacity: _showDots ? 1.0 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: _buildDotIndicator(),
                ),
              ),

            // Swipe session hint overlay
            if (_swipeHintName != null)
              Positioned(
                top: 8,
                left: 0,
                right: 0,
                child: Center(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    decoration: BoxDecoration(
                      color: Colors.black.withOpacity(0.7),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      _swipeHintName!,
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                    ),
                  ),
                ),
              ),
          ],
        ),
        ),
      ),
    );
  }
}
