/// ShellTerm — High-performance terminal emulator for Flutter.
///
/// Zero external dependencies. Optimized for low-power mobile devices.
library shellterm;

export 'src/cell.dart' show CellData, CellColor, CellContent, CellAttr;
export 'src/buffer.dart' show Buffer, BufferLine, BufferLines, CircularBuffer;
export 'src/handler.dart';
export 'src/parser.dart';
export 'src/emitter.dart';
export 'src/terminal.dart';
export 'src/theme.dart';
export 'src/controller.dart';
export 'src/platform.dart';
export 'src/renderer.dart' show RenderTerminal;
export 'src/view.dart';
export 'src/input.dart';
export 'src/mouse.dart';
