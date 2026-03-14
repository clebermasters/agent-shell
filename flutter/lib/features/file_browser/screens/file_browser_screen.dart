import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/dotfile.dart';
import '../../../data/models/file_entry.dart';
import '../../dotfiles/providers/dotfiles_provider.dart';
import '../../dotfiles/screens/dotfile_editor_screen.dart';
import '../providers/file_browser_provider.dart';

class FileBrowserScreen extends ConsumerStatefulWidget {
  final String initialPath;

  const FileBrowserScreen({super.key, required this.initialPath});

  @override
  ConsumerState<FileBrowserScreen> createState() => _FileBrowserScreenState();
}

class _FileBrowserScreenState extends ConsumerState<FileBrowserScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(fileBrowserProvider.notifier).listFiles(widget.initialPath);
    });
  }

  bool get isDarkMode => Theme.of(context).brightness == Brightness.dark;
  Color get backgroundColor =>
      isDarkMode ? const Color(0xFF0F172A) : const Color(0xFFF8FAFC);
  Color get surfaceColor =>
      isDarkMode ? const Color(0xFF1E293B) : Colors.white;
  Color get textPrimary =>
      isDarkMode ? Colors.grey.shade100 : const Color(0xFF1E293B);
  Color get textSecondary =>
      isDarkMode ? Colors.grey.shade400 : const Color(0xFF64748B);

  void _openFile(FileEntry entry) {
    final dotFile = DotFile(
      path: entry.path,
      name: entry.name,
      isDirectory: false,
      size: entry.size,
      modified: entry.modified,
      exists: true,
      writable: true,
    );
    ref.read(dotfilesProvider.notifier).selectFile(dotFile);
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const DotfileEditorScreen()),
    );
  }

  void _navigateUp(String currentPath) {
    final parts = currentPath.split('/');
    // Remove trailing empty segment if path ends with '/'
    final filtered = parts.where((p) => p.isNotEmpty).toList();
    if (filtered.isEmpty) return;
    filtered.removeLast();
    final parent = filtered.isEmpty ? '/' : '/${filtered.join('/')}';
    ref.read(fileBrowserProvider.notifier).navigateTo(parent);
  }

  List<_BreadcrumbSegment> _buildBreadcrumbs(String path) {
    final parts = path.split('/').where((p) => p.isNotEmpty).toList();
    final segments = <_BreadcrumbSegment>[
      _BreadcrumbSegment(label: '/', path: '/'),
    ];
    String cumulative = '';
    for (final part in parts) {
      cumulative = '$cumulative/$part';
      segments.add(_BreadcrumbSegment(label: part, path: cumulative));
    }
    return segments;
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '${bytes}B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)}KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(fileBrowserProvider);
    final breadcrumbs = _buildBreadcrumbs(state.currentPath);

    return Scaffold(
      backgroundColor: backgroundColor,
      appBar: AppBar(
        backgroundColor: surfaceColor,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () {
            if (state.currentPath != widget.initialPath &&
                state.currentPath != '/') {
              _navigateUp(state.currentPath);
            } else {
              Navigator.of(context).pop();
            }
          },
        ),
        title: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (int i = 0; i < breadcrumbs.length; i++) ...[
                if (i > 0)
                  Icon(
                    Icons.chevron_right,
                    size: 16,
                    color: textSecondary,
                  ),
                GestureDetector(
                  onTap: () => ref
                      .read(fileBrowserProvider.notifier)
                      .navigateTo(breadcrumbs[i].path),
                  child: Text(
                    breadcrumbs[i].label,
                    style: TextStyle(
                      fontSize: 14,
                      color: i == breadcrumbs.length - 1
                          ? textPrimary
                          : textSecondary,
                      fontWeight: i == breadcrumbs.length - 1
                          ? FontWeight.w600
                          : FontWeight.normal,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh, size: 20),
            tooltip: 'Refresh',
            onPressed: () => ref
                .read(fileBrowserProvider.notifier)
                .listFiles(state.currentPath),
          ),
        ],
      ),
      body: Builder(
        builder: (context) {
          if (state.isLoading) {
            return const Center(child: CircularProgressIndicator());
          }
          if (state.error != null) {
            return Center(
              child: Text(
                state.error!,
                style: TextStyle(color: Colors.red.shade400),
              ),
            );
          }
          if (state.entries.isEmpty) {
            return Center(
              child: Text(
                'Empty directory',
                style: TextStyle(color: textSecondary),
              ),
            );
          }
          return ListView.builder(
            itemCount: state.entries.length,
            itemBuilder: (context, index) {
              final entry = state.entries[index];
              return ListTile(
                leading: Icon(
                  entry.isDirectory ? Icons.folder : _fileIcon(entry.name),
                  color: entry.isDirectory
                      ? const Color(0xFFFBBF24)
                      : textSecondary,
                ),
                title: Text(
                  entry.name,
                  style: TextStyle(color: textPrimary, fontSize: 14),
                ),
                subtitle: entry.isDirectory
                    ? null
                    : Text(
                        _formatSize(entry.size),
                        style:
                            TextStyle(color: textSecondary, fontSize: 12),
                      ),
                trailing: entry.isDirectory
                    ? Icon(
                        Icons.chevron_right,
                        color: textSecondary,
                        size: 20,
                      )
                    : null,
                onTap: () {
                  if (entry.isDirectory) {
                    ref
                        .read(fileBrowserProvider.notifier)
                        .navigateTo(entry.path);
                  } else {
                    _openFile(entry);
                  }
                },
              );
            },
          );
        },
      ),
    );
  }

  IconData _fileIcon(String name) {
    final ext = name.contains('.') ? name.split('.').last.toLowerCase() : '';
    switch (ext) {
      case 'dart':
      case 'py':
      case 'js':
      case 'ts':
      case 'rs':
      case 'go':
      case 'java':
      case 'cpp':
      case 'c':
      case 'h':
      case 'sh':
      case 'bash':
      case 'zsh':
        return Icons.code;
      case 'md':
      case 'txt':
      case 'rst':
        return Icons.article;
      case 'json':
      case 'yaml':
      case 'yml':
      case 'toml':
      case 'ini':
      case 'conf':
        return Icons.settings;
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'svg':
        return Icons.image;
      default:
        return Icons.insert_drive_file;
    }
  }
}

class _BreadcrumbSegment {
  final String label;
  final String path;
  const _BreadcrumbSegment({required this.label, required this.path});
}
