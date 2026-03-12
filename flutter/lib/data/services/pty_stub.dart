// Stub for web — flutter_pty uses dart:ffi which is unavailable on web
class Pty {
  static Pty start(String executable,
      {List<String> arguments = const [],
      int columns = 80,
      int rows = 24,
      String? workingDirectory,
      Map<String, String>? environment}) {
    throw UnsupportedError('PTY not supported on web');
  }

  Stream<List<int>> get output => const Stream.empty();
  void write(List<int> data) {}
  void resize(int columns, int rows) {}
  void kill() {}
  Future<int> get exitCode async => 0;
}
