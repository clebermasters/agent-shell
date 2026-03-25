import 'package:shellterm/src/core/cell.dart';

/// Named 16-color terminal palette indices.
abstract class NamedColor {
  static const black = 0;
  static const red = 1;
  static const green = 2;
  static const yellow = 3;
  static const blue = 4;
  static const magenta = 5;
  static const cyan = 6;
  static const white = 7;

  static const brightBlack = 8;
  static const brightRed = 9;
  static const brightGreen = 10;
  static const brightYellow = 11;
  static const brightBlue = 12;
  static const brightMagenta = 13;
  static const brightCyan = 14;
  static const brightWhite = 15;
}

/// Resolves a terminal cell color integer to a Flutter-compatible ARGB integer.
class TerminalColor {
  final int _value;

  const TerminalColor._(this._value);

  static const normal = TerminalColor._(CellColor.normal);

  static TerminalColor named(int index) =>
      TerminalColor._(index | CellColor.named);

  static TerminalColor palette(int index) =>
      TerminalColor._(index | CellColor.palette);

  static TerminalColor rgb(int r, int g, int b) =>
      TerminalColor._((r << 16) | (g << 8) | b | CellColor.rgb);

  int get value => _value;

  int get colorType => _value & CellColor.typeMask;

  int get colorValue => _value & CellColor.valueMask;

  bool get isNormal => colorType == CellColor.normal;
  bool get isNamed => colorType == CellColor.named;
  bool get isPalette => colorType == CellColor.palette;
  bool get isRgb => colorType == CellColor.rgb;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TerminalColor && _value == other._value;

  @override
  int get hashCode => _value.hashCode;

  @override
  String toString() => 'TerminalColor(0x${_value.toRadixString(16)})';
}
