import 'package:flutter/material.dart';

class MobileKeyboard extends StatefulWidget {
  final Function(String data) onKeyPressed;
  final VoidCallback onClose;

  const MobileKeyboard({
    super.key,
    required this.onKeyPressed,
    required this.onClose,
  });

  @override
  State<MobileKeyboard> createState() => _MobileKeyboardState();
}

class _MobileKeyboardState extends State<MobileKeyboard> {
  bool _ctrlActive = false;
  bool _altActive = false;
  bool _shiftActive = false;
  
  bool _ctrlLocked = false;
  bool _altLocked = false;
  bool _shiftLocked = false;

  final Map<String, String> _shiftMap = {
    '1': '!', '2': '@', '3': '#', '4': '\$', '5': '%',
    '6': '^', '7': '&', '8': '*', '9': '(', '0': ')',
    '-': '_', '=': '+', '[': '{', ']': '}', '\\': '|',
    ';': ':', '\'': '"', ',': '<', '.': '>', '/': '?',
    '`': '~',
  };

  void _handleKey(String key) {
    String data = '';

    if (key == 'ENTER') {
      data = '\r';
    } else if (key == 'TAB') {
      // Respect SHIFT modifier for reverse-tab
      data = (_shiftActive || _shiftLocked) ? '\x1b[Z' : '\t';
    } else if (key == 'ESC') {
      data = '\x1b';
    } else if (key == 'SPACE') {
      data = ' ';
    } else if (key == 'BKSPC') {
      data = '\x7f';
    } else if (key == 'UP') {
      data = '\x1b[A';
    } else if (key == 'DOWN') {
      data = '\x1b[B';
    } else if (key == 'LEFT') {
      data = '\x1b[D';
    } else if (key == 'RIGHT') {
      data = '\x1b[C';
    } else if (key == 'PGUP') {
      data = '\x1b[5~';
    } else if (key == 'PGDN') {
      data = '\x1b[6~';
    } else if (key == 'HOME') {
      data = '\x1b[H';
    } else if (key == 'END') {
      data = '\x1b[F';
    } else if (key.startsWith('F')) {
      final fMap = {
        'F1': '\x1bOP', 'F2': '\x1bOQ', 'F3': '\x1bOR', 'F4': '\x1bOS',
        'F5': '\x1b[15~', 'F6': '\x1b[17~', 'F7': '\x1b[18~', 'F8': '\x1b[19~',
        'F9': '\x1b[20~', 'F10': '\x1b[21~', 'F11': '\x1b[23~', 'F12': '\x1b[24~',
      };
      data = fMap[key] ?? '';
    } else if (key.length == 1) {
      String char = key;
      
      bool isShifted = _shiftActive || _shiftLocked;
      bool isCtrled = _ctrlActive || _ctrlLocked;
      bool isAlted = _altActive || _altLocked;

      if (isShifted) {
        if (_shiftMap.containsKey(char)) {
          char = _shiftMap[char]!;
        } else {
          char = char.toUpperCase();
        }
      } else {
        char = char.toLowerCase();
      }

      if (isCtrled) {
        int code = char.toUpperCase().codeUnitAt(0);
        if (code >= 64 && code <= 95) {
          data = String.fromCharCode(code - 64);
        } else if (char == ' ') {
          data = '\x00';
        } else {
          data = char;
        }
      } else {
        data = char;
      }

      if (isAlted) {
        data = '\x1b$data';
      }
    }

    if (data.isNotEmpty) {
      widget.onKeyPressed(data);
    }

    // Reset non-locked modifiers
    setState(() {
      _ctrlActive = false;
      _altActive = false;
      _shiftActive = false;
    });
  }

  void _toggleModifier(String mod) {
    setState(() {
      if (mod == 'CTRL') {
        if (_ctrlActive) {
          _ctrlActive = false;
          _ctrlLocked = true;
        } else if (_ctrlLocked) {
          _ctrlLocked = false;
        } else {
          _ctrlActive = true;
        }
      } else if (mod == 'ALT') {
        if (_altActive) {
          _altActive = false;
          _altLocked = true;
        } else if (_altLocked) {
          _altLocked = false;
        } else {
          _altActive = true;
        }
      } else if (mod == 'SHIFT') {
        if (_shiftActive) {
          _shiftActive = false;
          _shiftLocked = true;
        } else if (_shiftLocked) {
          _shiftLocked = false;
        } else {
          _shiftActive = true;
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      color: theme.colorScheme.surface,
      padding: const EdgeInsets.fromLTRB(2, 2, 2, 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Row 1: Function keys and special
          _buildRow([
            _buildKey('ESC', () => _handleKey('ESC')),
            _buildKey('TAB', () => _handleKey('TAB')),
            _buildModifierKey('CTRL', _ctrlActive, _ctrlLocked),
            _buildModifierKey('ALT', _altActive, _altLocked),
            _buildModifierKey('SHIFT', _shiftActive, _shiftLocked),
            _buildKey('F1', () => _handleKey('F1')),
            _buildKey('F2', () => _handleKey('F2')),
            _buildKey('F3', () => _handleKey('F3')),
            _buildKey('F4', () => _handleKey('F4')),
            _buildKey('F5', () => _handleKey('F5')),
            _buildKey('⌫', () => _handleKey('BKSPC'), flex: 2),
          ]),

          // Row 2: Numbers
          _buildRow([
            _buildKey('1', () => _handleKey('1')),
            _buildKey('2', () => _handleKey('2')),
            _buildKey('3', () => _handleKey('3')),
            _buildKey('4', () => _handleKey('4')),
            _buildKey('5', () => _handleKey('5')),
            _buildKey('6', () => _handleKey('6')),
            _buildKey('7', () => _handleKey('7')),
            _buildKey('8', () => _handleKey('8')),
            _buildKey('9', () => _handleKey('9')),
            _buildKey('0', () => _handleKey('0')),
            _buildKey('-', () => _handleKey('-')),
            _buildKey('=', () => _handleKey('=')),
          ]),

          // Row 3: QWERTY
          _buildRow([
            _buildKey('Q', () => _handleKey('Q')),
            _buildKey('W', () => _handleKey('W')),
            _buildKey('E', () => _handleKey('E')),
            _buildKey('R', () => _handleKey('R')),
            _buildKey('T', () => _handleKey('T')),
            _buildKey('Y', () => _handleKey('Y')),
            _buildKey('U', () => _handleKey('U')),
            _buildKey('I', () => _handleKey('I')),
            _buildKey('O', () => _handleKey('O')),
            _buildKey('P', () => _handleKey('P')),
            _buildKey('[', () => _handleKey('[')),
            _buildKey(']', () => _handleKey(']')),
          ]),

          // Row 4: ASDF
          _buildRow([
            _buildKey('A', () => _handleKey('A')),
            _buildKey('S', () => _handleKey('S')),
            _buildKey('D', () => _handleKey('D')),
            _buildKey('F', () => _handleKey('F')),
            _buildKey('G', () => _handleKey('G')),
            _buildKey('H', () => _handleKey('H')),
            _buildKey('J', () => _handleKey('J')),
            _buildKey('K', () => _handleKey('K')),
            _buildKey('L', () => _handleKey('L')),
            _buildKey(';', () => _handleKey(';')),
            _buildKey('\'', () => _handleKey('\'')),
            _buildKey('ENTER', () => _handleKey('ENTER'), flex: 2),
          ]),

          // Row 5: ZXCV + Arrows
          _buildRow([
            _buildKey('Z', () => _handleKey('Z')),
            _buildKey('X', () => _handleKey('X')),
            _buildKey('C', () => _handleKey('C')),
            _buildKey('V', () => _handleKey('V')),
            _buildKey('B', () => _handleKey('B')),
            _buildKey('N', () => _handleKey('N')),
            _buildKey('M', () => _handleKey('M')),
            _buildKey(',', () => _handleKey(',')),
            _buildKey('.', () => _handleKey('.')),
            _buildKey('/', () => _handleKey('/')),
            _buildKey('▲', () => _handleKey('UP')),
            _buildKey('\\', () => _handleKey('\\')),
          ]),

          // Row 6: Space and more arrows
          _buildRow([
            _buildKey('SPACE', () => _handleKey('SPACE'), flex: 4),
            _buildKey('◀', () => _handleKey('LEFT')),
            _buildKey('▼', () => _handleKey('DOWN')),
            _buildKey('▶', () => _handleKey('RIGHT')),
            _buildKey('HOME', () => _handleKey('HOME')),
            _buildKey('END', () => _handleKey('END')),
            _buildKey('PGUP', () => _handleKey('PGUP')),
            _buildKey('PGDN', () => _handleKey('PGDN')),
            _buildKey('CLOSE', widget.onClose),
          ]),
        ],
      ),
    );
  }

  Widget _buildRow(List<Widget> keys) {
    return Container(
      height: 38,
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Row(
        children: keys,
      ),
    );
  }

  Widget _buildModifierKey(String label, bool isActive, bool isLocked) {
    Color color = Colors.grey[800]!;
    if (isLocked) {
      color = Colors.red[900]!;
    } else if (isActive) {
      color = Colors.blue[700]!;
    }

    return _buildKey(
      label,
      () => _toggleModifier(label),
      color: color,
    );
  }

  Widget _buildKey(String label, VoidCallback onTap, {int flex = 1, Color? color}) {
    return Expanded(
      flex: flex,
      child: Padding(
        padding: const EdgeInsets.all(1),
        child: Material(
          color: color ?? Colors.grey[850],
          borderRadius: BorderRadius.circular(3),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(3),
            child: Center(
              child: Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 9,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
