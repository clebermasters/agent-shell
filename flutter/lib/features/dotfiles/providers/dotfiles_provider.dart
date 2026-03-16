import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/dotfile.dart';
import '../../../data/models/file_entry.dart';
import '../../../data/services/websocket_service.dart';
import '../../sessions/providers/sessions_provider.dart';

class DotfilesState {
  final List<DotFile> files;
  final DotFile? selectedFile;
  final String? fileContent;
  final bool isLoading;
  final String? error;
  final List<DotFileVersion> versions;
  final List<DotFileTemplate> templates;
  final Uint8List? binaryContent;
  final String? fileMimeType;

  const DotfilesState({
    this.files = const [],
    this.selectedFile,
    this.fileContent,
    this.isLoading = false,
    this.error,
    this.versions = const [],
    this.templates = const [],
    this.binaryContent,
    this.fileMimeType,
  });

  DotfilesState copyWith({
    List<DotFile>? files,
    DotFile? selectedFile,
    String? fileContent,
    bool? isLoading,
    String? error,
    List<DotFileVersion>? versions,
    List<DotFileTemplate>? templates,
    Uint8List? binaryContent,
    String? fileMimeType,
  }) {
    return DotfilesState(
      files: files ?? this.files,
      selectedFile: selectedFile ?? this.selectedFile,
      fileContent: fileContent ?? this.fileContent,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      versions: versions ?? this.versions,
      templates: templates ?? this.templates,
      binaryContent: binaryContent,
      fileMimeType: fileMimeType,
    );
  }
}

class DotfilesNotifier extends StateNotifier<DotfilesState> {
  final WebSocketService _wsService;

  DotfilesNotifier(this._wsService) : super(const DotfilesState()) {
    _init();
  }

  void _init() {
    _wsService.messages.listen((message) {
      final type = message['type'] as String?;
      switch (type) {
        case 'dotfiles-list':
        case 'dotfiles_list':
          final files =
              (message['files'] as List?)
                  ?.map((f) => DotFile.fromJson(f as Map<String, dynamic>))
                  .toList() ??
              [];
          state = state.copyWith(files: files, isLoading: false);
          break;
        case 'dotfile-content':
        case 'dotfile_content':
          state = state.copyWith(
            fileContent: message['content'] as String?,
            isLoading: false,
          );
          break;
        case 'dotfile-written':
        case 'dotfile_written':
          final success = message['success'] as bool? ?? false;
          if (success) {
            refresh();
          }
          break;
        case 'dotfile-history':
        case 'dotfile_history':
          final versions =
              (message['versions'] as List?)
                  ?.map(
                    (v) => DotFileVersion.fromJson(v as Map<String, dynamic>),
                  )
                  .toList() ??
              [];
          state = state.copyWith(versions: versions, isLoading: false);
          break;
        case 'dotfile-restored':
        case 'dotfile_restored':
          final success = message['success'] as bool? ?? false;
          if (success) {
            refresh();
          }
          break;
        case 'dotfile-templates':
        case 'dotfile_templates':
          final templates =
              (message['templates'] as List?)
                  ?.map(
                    (t) => DotFileTemplate.fromJson(t as Map<String, dynamic>),
                  )
                  .toList() ??
              [];
          state = state.copyWith(templates: templates, isLoading: false);
          break;
        case 'binary-file-content':
          final b64 = message['contentBase64'] as String? ?? '';
          final mimeType = message['mimeType'] as String?;
          final binaryError = message['error'] as String?;
          if (binaryError != null) {
            state = state.copyWith(
              error: binaryError,
              isLoading: false,
              binaryContent: null,
              fileMimeType: null,
            );
          } else {
            state = state.copyWith(
              binaryContent: b64.isEmpty ? null : base64Decode(b64),
              fileMimeType: mimeType,
              isLoading: false,
            );
          }
          break;
        case 'error':
          state = state.copyWith(
            error: message['message'] as String?,
            isLoading: false,
          );
          break;
      }
    });
  }

  void refresh() {
    if (state.isLoading) return;
    if (!_wsService.isConnected) {
      state = state.copyWith(
        error: 'Not connected to server',
        isLoading: false,
      );
      return;
    }
    state = state.copyWith(isLoading: true, error: null);
    _wsService.requestDotfiles();
  }

  void selectFile(DotFile file) {
    state = DotfilesState(
      files: state.files,
      selectedFile: file,
      isLoading: true,
      templates: state.templates,
    );
    _wsService.requestDotfileContent(file.path);
  }

  void selectBinaryFile(FileEntry entry) {
    final dotFile = DotFile(
      path: entry.path,
      name: entry.name,
      isDirectory: false,
      size: entry.size,
      modified: entry.modified,
      exists: true,
      writable: false,
    );
    state = DotfilesState(
      files: state.files,
      selectedFile: dotFile,
      isLoading: true,
      binaryContent: null,
      fileMimeType: null,
    );
    _wsService.requestBinaryFileContent(entry.path);
  }

  void browseFile(String path) {
    final file = DotFile(
      path: path,
      name: path.split('/').last,
      isDirectory: false,
      size: 0,
      exists: false,
      writable: true,
      fileType: _detectFileType(path),
    );
    selectFile(file);
  }

  DotFileType _detectFileType(String path) {
    final name = path.toLowerCase();
    if (name.contains('.bashrc') ||
        name.contains('.zshrc') ||
        name.contains('.profile') ||
        name.contains('.bash') ||
        name.contains('.sh')) {
      return DotFileType.shell;
    } else if (name.contains('.gitconfig') || name.contains('.gitignore')) {
      return DotFileType.git;
    } else if (name.contains('.vimrc') || name.contains('vim/')) {
      return DotFileType.vim;
    } else if (name.contains('.tmux')) {
      return DotFileType.tmux;
    } else if (name.contains('.ssh/')) {
      return DotFileType.ssh;
    }
    return DotFileType.other;
  }

  Future<void> saveFile(String path, String content) async {
    state = state.copyWith(isLoading: true);
    _wsService.saveDotfile(path, content);
    await Future.delayed(const Duration(milliseconds: 500));
    refresh();
  }

  void loadHistory(String path) {
    if (state.isLoading) return;
    state = state.copyWith(isLoading: true, versions: []);
    _wsService.requestDotfileHistory(path);
  }

  Future<void> restoreVersion(String path, DateTime timestamp) async {
    state = state.copyWith(isLoading: true);
    _wsService.restoreDotfileVersion(path, timestamp.toIso8601String());
    await Future.delayed(const Duration(milliseconds: 500));
    refresh();
  }

  void loadTemplates() {
    if (state.isLoading) return;
    state = state.copyWith(isLoading: true, templates: []);
    _wsService.requestDotfileTemplates();
  }

  void clearSelection() {
    state = DotfilesState(files: state.files, isLoading: state.isLoading);
  }
}

final dotfilesProvider = StateNotifierProvider<DotfilesNotifier, DotfilesState>(
  (ref) {
    final wsService = ref.watch(sharedWebSocketServiceProvider);
    return DotfilesNotifier(wsService);
  },
);
