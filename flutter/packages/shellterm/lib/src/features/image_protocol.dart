import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;

/// Supported inline image protocols.
enum ImageProtocol {
  /// Sixel graphics (DCS sequence).
  sixel,

  /// iTerm2 inline images (OSC 1337).
  iterm2,
}

/// Stores decoded inline image data associated with terminal cells.
class TerminalImage {
  TerminalImage({
    required this.protocol,
    required this.width,
    required this.height,
    required this.data,
    this.image,
  });

  /// Which protocol produced this image.
  final ImageProtocol protocol;

  /// Width of the image in pixels.
  final int width;

  /// Height of the image in pixels.
  final int height;

  /// Raw image bytes (PNG, JPEG, or decoded RGBA).
  final Uint8List data;

  /// Decoded Flutter image, lazily decoded from [data].
  ui.Image? image;

  /// Number of terminal cell columns this image occupies.
  int cellCols = 0;

  /// Number of terminal cell rows this image occupies.
  int cellRows = 0;

  /// The buffer line and column where this image starts.
  int startLine = 0;
  int startCol = 0;

  bool _disposed = false;
  bool get disposed => _disposed;

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    image?.dispose();
    image = null;
  }
}

/// Manages inline images in the terminal buffer.
///
/// Images are stored by an opaque ID and referenced from the buffer.
/// The renderer queries this manager to paint images over the terminal grid.
class TerminalImageManager {
  final _images = <int, TerminalImage>{};
  int _nextId = 1;

  /// Register a new image and return its ID.
  int addImage(TerminalImage image) {
    final id = _nextId++;
    _images[id] = image;
    return id;
  }

  /// Get an image by ID.
  TerminalImage? getImage(int id) => _images[id];

  /// Remove and dispose an image.
  void removeImage(int id) {
    _images[id]?.dispose();
    _images.remove(id);
  }

  /// Remove all images.
  void clear() {
    for (final img in _images.values) {
      img.dispose();
    }
    _images.clear();
  }

  /// Number of stored images.
  int get length => _images.length;

  /// All image IDs.
  Iterable<int> get ids => _images.keys;
}

/// Handles iTerm2 inline image protocol (OSC 1337).
///
/// Format: `ESC ] 1337 ; File=[params]:base64data ST`
///
/// Supported params:
/// - `name=<base64>` - filename
/// - `size=<int>` - file size in bytes
/// - `width=<value>` - display width (N, Npx, N%, auto)
/// - `height=<value>` - display height (N, Npx, N%, auto)
/// - `preserveAspectRatio=<0|1>` - default 1
/// - `inline=<0|1>` - must be 1 to display inline
class Iterm2ImageDecoder {
  /// Try to parse an iTerm2 inline image OSC payload.
  ///
  /// The [payload] should be the part after `1337;` in the OSC sequence.
  /// Returns a [TerminalImage] if parsing succeeds, null otherwise.
  static TerminalImage? decode(String payload) {
    if (!payload.startsWith('File=')) return null;

    final colonIdx = payload.indexOf(':');
    if (colonIdx < 0) return null;

    final paramStr = payload.substring(5, colonIdx); // after "File="
    final base64Data = payload.substring(colonIdx + 1);

    final params = _parseParams(paramStr);

    // Must have inline=1 to display.
    if (params['inline'] != '1') return null;

    Uint8List data;
    try {
      data = base64Decode(base64Data);
    } catch (_) {
      return null;
    }

    if (data.isEmpty) return null;

    // Parse dimensions (simplified - defaults to auto).
    final width = _parseDimension(params['width']);
    final height = _parseDimension(params['height']);

    return TerminalImage(
      protocol: ImageProtocol.iterm2,
      width: width ?? 0,
      height: height ?? 0,
      data: data,
    );
  }

  static Map<String, String> _parseParams(String paramStr) {
    final result = <String, String>{};
    for (final part in paramStr.split(';')) {
      final eq = part.indexOf('=');
      if (eq > 0) {
        result[part.substring(0, eq)] = part.substring(eq + 1);
      }
    }
    return result;
  }

  /// Parse a dimension value. Returns pixel value or null for "auto".
  static int? _parseDimension(String? value) {
    if (value == null || value.isEmpty || value == 'auto') return null;
    if (value.endsWith('px')) {
      return int.tryParse(value.substring(0, value.length - 2));
    }
    // Cell count or percentage - just parse the number.
    return int.tryParse(value);
  }
}

/// Placeholder for Sixel decoder.
///
/// Sixel is a DCS-based protocol: `DCS P1;P2;P3 q <sixel-data> ST`
/// Full decoding requires a state machine for the Sixel graphics format.
/// This class provides the interface; decoding will be filled in when needed.
class SixelDecoder {
  /// Accumulated Sixel data bytes from DCS passthrough.
  final _buffer = BytesBuilder(copy: false);

  /// DCS params (P1=pixel-aspect, P2=background, P3=horizontal-grid).
  int p1 = 0;
  int p2 = 0;
  int p3 = 0;

  /// Called when a DCS Sixel hook is received.
  void hook(List<int> params) {
    _buffer.clear();
    p1 = params.isNotEmpty ? params[0] : 0;
    p2 = params.length > 1 ? params[1] : 0;
    p3 = params.length > 2 ? params[2] : 0;
  }

  /// Called for each byte in the DCS passthrough.
  void put(int byte) {
    _buffer.addByte(byte);
  }

  /// Called when the DCS sequence terminates. Returns the decoded image
  /// or null if decoding fails.
  TerminalImage? unhook() {
    final data = _buffer.takeBytes();
    if (data.isEmpty) return null;

    // TODO: Full Sixel decoding - parse color registers, build RGBA bitmap.
    // For now, store raw Sixel data for potential external processing.
    return TerminalImage(
      protocol: ImageProtocol.sixel,
      width: 0,
      height: 0,
      data: data,
    );
  }

  void reset() {
    _buffer.clear();
  }
}
