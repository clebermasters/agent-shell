import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../data/models/dotfile.dart';
import '../../../data/models/file_entry.dart';
import '../../dotfiles/providers/dotfiles_provider.dart';
import '../../dotfiles/screens/dotfile_editor_screen.dart';
import '../../dotfiles/screens/file_media_viewer_screen.dart';
import '../providers/file_browser_provider.dart';

class FileBrowserScreen extends ConsumerStatefulWidget {
  final String initialPath;

  const FileBrowserScreen({super.key, required this.initialPath});

  @override
  ConsumerState<FileBrowserScreen> createState() => _FileBrowserScreenState();
}

class _FileBrowserScreenState extends ConsumerState<FileBrowserScreen> {
  bool _showSearch = false;
  String _searchQuery = '';
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(fileBrowserProvider.notifier).listFiles(widget.initialPath);
    });
    _searchController.addListener(() {
      setState(() => _searchQuery = _searchController.text);
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
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

  static const _imageExts = {
    'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp',
  };
  static const _audioExts = {
    'mp3', 'wav', 'ogg', 'm4a', 'aac', 'flac',
  };

  String _ext(String name) =>
      name.contains('.') ? name.split('.').last.toLowerCase() : '';

  void _openFile(FileEntry entry) {
    final ext = _ext(entry.name);
    if (_imageExts.contains(ext) || _audioExts.contains(ext)) {
      ref.read(dotfilesProvider.notifier).selectBinaryFile(entry);
      Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => const FileMediaViewerScreen()),
      );
    } else {
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
  }

  void _navigateUp(String currentPath) {
    final parts = currentPath.split('/');
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

  String _formatDate(DateTime? date) {
    if (date == null) return '';
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return '${months[date.month - 1]} ${date.day}';
  }

  void _showSortSheet() {
    final state = ref.read(fileBrowserProvider);
    showModalBottomSheet(
      context: context,
      backgroundColor: surfaceColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text('Sort by',
                  style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: textPrimary,
                      fontSize: 15)),
            ),
            const Divider(height: 1),
            for (final option in [
              (SortMode.nameAsc, 'Name A → Z', Icons.sort_by_alpha),
              (SortMode.nameDesc, 'Name Z → A', Icons.sort_by_alpha),
              (SortMode.sizeAsc, 'Size (smallest first)', Icons.data_array),
              (SortMode.sizeDesc, 'Size (largest first)', Icons.data_array),
              (SortMode.modifiedDesc, 'Recently modified', Icons.schedule),
            ])
              ListTile(
                leading: Icon(option.$3,
                    color: state.sortMode == option.$1
                        ? const Color(0xFF6366F1)
                        : textSecondary),
                title: Text(option.$2,
                    style: TextStyle(
                        color: state.sortMode == option.$1
                            ? const Color(0xFF6366F1)
                            : textPrimary)),
                trailing: state.sortMode == option.$1
                    ? const Icon(Icons.check, color: Color(0xFF6366F1))
                    : null,
                onTap: () {
                  ref.read(fileBrowserProvider.notifier).setSortMode(option.$1);
                  Navigator.pop(context);
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  void _showEntryActions(FileEntry entry) {
    showModalBottomSheet(
      context: context,
      backgroundColor: surfaceColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Icon(_fileIcon(entry.name, entry.isDirectory),
                    color: _fileIconColor(entry.name, entry.isDirectory),
                    size: 28),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(entry.name,
                      style: TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 16,
                          color: textPrimary)),
                ),
              ]),
              const SizedBox(height: 16),
              _infoRow('Path', entry.path),
              if (!entry.isDirectory)
                _infoRow('Size', _formatSize(entry.size)),
              if (entry.modified != null)
                _infoRow('Modified',
                    entry.modified!.toLocal().toString().substring(0, 16)),
              const SizedBox(height: 16),
              const Divider(height: 1),
              const SizedBox(height: 8),
              _actionTile(
                icon: Icons.copy,
                label: 'Copy path',
                onTap: () {
                  Clipboard.setData(ClipboardData(text: entry.path));
                  Navigator.pop(ctx);
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Path copied'),
                      duration: Duration(seconds: 1),
                    ),
                  );
                },
              ),
              _actionTile(
                icon: Icons.edit,
                label: 'Rename',
                onTap: () {
                  Navigator.pop(ctx);
                  _showRenameDialog(entry);
                },
              ),
              _actionTile(
                icon: Icons.delete_outline,
                label: 'Delete',
                color: Colors.red.shade400,
                onTap: () {
                  Navigator.pop(ctx);
                  _showDeleteConfirmation([entry]);
                },
              ),
              _actionTile(
                icon: Icons.check_circle_outline,
                label: 'Select',
                onTap: () {
                  Navigator.pop(ctx);
                  ref.read(fileBrowserProvider.notifier).toggleSelection(entry.path);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _actionTile({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
    Color? color,
  }) {
    return ListTile(
      leading: Icon(icon, color: color ?? textSecondary, size: 22),
      title: Text(label,
          style: TextStyle(color: color ?? textPrimary, fontSize: 14)),
      onTap: onTap,
      dense: true,
      contentPadding: EdgeInsets.zero,
      visualDensity: VisualDensity.compact,
    );
  }

  void _showRenameDialog(FileEntry entry) {
    final controller = TextEditingController(text: entry.name);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: surfaceColor,
        title: Text('Rename', style: TextStyle(color: textPrimary)),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(
            hintText: 'New name',
            hintStyle: TextStyle(color: textSecondary),
            filled: true,
            fillColor: backgroundColor,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: BorderSide.none,
            ),
          ),
          style: TextStyle(color: textPrimary),
          onSubmitted: (value) {
            final newName = value.trim();
            if (newName.isNotEmpty && newName != entry.name) {
              ref.read(fileBrowserProvider.notifier).renameFile(entry.path, newName);
            }
            Navigator.pop(ctx);
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              final newName = controller.text.trim();
              if (newName.isNotEmpty && newName != entry.name) {
                ref.read(fileBrowserProvider.notifier).renameFile(entry.path, newName);
              }
              Navigator.pop(ctx);
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF6366F1),
            ),
            child: const Text('Rename', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    ).then((_) => controller.dispose());
  }

  void _showDeleteConfirmation(List<FileEntry> entries) {
    final hasDirectories = entries.any((e) => e.isDirectory);
    final count = entries.length;
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: surfaceColor,
        title: Text('Delete $count ${count == 1 ? 'item' : 'items'}?',
            style: TextStyle(color: textPrimary)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (count <= 5)
              for (final e in entries)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Row(
                    children: [
                      Icon(
                        e.isDirectory ? Icons.folder : Icons.insert_drive_file,
                        size: 16,
                        color: textSecondary,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(e.name,
                            style: TextStyle(color: textPrimary, fontSize: 13),
                            overflow: TextOverflow.ellipsis),
                      ),
                    ],
                  ),
                ),
            if (count > 5)
              Text('$count items selected',
                  style: TextStyle(color: textPrimary, fontSize: 13)),
            if (hasDirectories) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.orange.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.orange.withValues(alpha: 0.3)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber, color: Colors.orange, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Directories will be deleted recursively',
                        style: TextStyle(
                            color: Colors.orange, fontSize: 12),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(ctx);
              if (entries.length == 1) {
                ref.read(fileBrowserProvider.notifier).deleteSingle(
                  entries.first.path,
                  recursive: entries.first.isDirectory,
                );
              } else {
                ref.read(fileBrowserProvider.notifier).deleteSelected();
              }
            },
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('Delete', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(label,
                style: TextStyle(fontSize: 12, color: textSecondary)),
          ),
          Expanded(
            child: Text(value,
                style: TextStyle(fontSize: 13, color: textPrimary)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(fileBrowserProvider);
    final breadcrumbs = _buildBreadcrumbs(state.currentPath);
    final entries = state.sortedEntries;
    final filtered = _searchQuery.isEmpty
        ? entries
        : entries
            .where((e) =>
                e.name.toLowerCase().contains(_searchQuery.toLowerCase()))
            .toList();

    return Scaffold(
      backgroundColor: backgroundColor,
      appBar: state.isSelectionMode
          ? _buildSelectionAppBar(state)
          : _buildNormalAppBar(state, breadcrumbs),
      body: Column(
        children: [
          if (_showSearch && !state.isSelectionMode)
            Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
              color: surfaceColor,
              child: TextField(
                controller: _searchController,
                autofocus: true,
                decoration: InputDecoration(
                  hintText: 'Filter files…',
                  hintStyle: TextStyle(color: textSecondary),
                  prefixIcon:
                      Icon(Icons.search, size: 20, color: textSecondary),
                  suffixIcon: _searchQuery.isNotEmpty
                      ? IconButton(
                          icon: const Icon(Icons.close, size: 18),
                          onPressed: () {
                            _searchController.clear();
                            setState(() => _searchQuery = '');
                          },
                        )
                      : null,
                  filled: true,
                  fillColor: backgroundColor,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: BorderSide.none,
                  ),
                  contentPadding: const EdgeInsets.symmetric(
                      horizontal: 12, vertical: 8),
                  isDense: true,
                ),
                style: TextStyle(fontSize: 14, color: textPrimary),
              ),
            ),
          Expanded(
            child: Builder(
              builder: (context) {
                if (state.isLoading) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (state.error != null) {
                  return Center(
                    child: Text(state.error!,
                        style: TextStyle(color: Colors.red.shade400)),
                  );
                }
                if (filtered.isEmpty) {
                  return Center(
                    child: Text(
                      _searchQuery.isNotEmpty
                          ? 'No matches for "$_searchQuery"'
                          : 'Empty directory',
                      style: TextStyle(color: textSecondary),
                    ),
                  );
                }
                return ListView.builder(
                  itemCount: filtered.length,
                  itemBuilder: (context, index) {
                    final entry = filtered[index];
                    return _buildEntryTile(entry, state);
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  PreferredSizeWidget _buildNormalAppBar(
      FileBrowserState state, List<_BreadcrumbSegment> breadcrumbs) {
    return AppBar(
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
                Icon(Icons.chevron_right, size: 16, color: textSecondary),
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
          icon: Icon(Icons.search,
              size: 20,
              color: _showSearch ? const Color(0xFF6366F1) : null),
          tooltip: 'Search',
          onPressed: () {
            setState(() {
              _showSearch = !_showSearch;
              if (!_showSearch) {
                _searchController.clear();
                _searchQuery = '';
              }
            });
          },
        ),
        IconButton(
          icon: Icon(Icons.sort, size: 20),
          tooltip: 'Sort',
          onPressed: _showSortSheet,
        ),
        IconButton(
          icon: Icon(
            state.showHidden ? Icons.visibility : Icons.visibility_off,
            size: 20,
          ),
          tooltip: state.showHidden ? 'Hide dotfiles' : 'Show dotfiles',
          onPressed: () =>
              ref.read(fileBrowserProvider.notifier).toggleHidden(),
        ),
        IconButton(
          icon: const Icon(Icons.refresh, size: 20),
          tooltip: 'Refresh',
          onPressed: () => ref
              .read(fileBrowserProvider.notifier)
              .listFiles(state.currentPath),
        ),
      ],
    );
  }

  PreferredSizeWidget _buildSelectionAppBar(FileBrowserState state) {
    return AppBar(
      backgroundColor: surfaceColor,
      elevation: 0,
      leading: IconButton(
        icon: const Icon(Icons.close),
        onPressed: () =>
            ref.read(fileBrowserProvider.notifier).clearSelection(),
      ),
      title: Text(
        '${state.selectedPaths.length} selected',
        style: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          color: textPrimary,
        ),
      ),
      actions: [
        IconButton(
          icon: const Icon(Icons.select_all, size: 22),
          tooltip: 'Select all',
          onPressed: () =>
              ref.read(fileBrowserProvider.notifier).selectAll(),
        ),
        IconButton(
          icon: Icon(Icons.delete, size: 22, color: Colors.red.shade400),
          tooltip: 'Delete selected',
          onPressed: () {
            final selectedEntries = state.entries
                .where((e) => state.selectedPaths.contains(e.path))
                .toList();
            if (selectedEntries.isNotEmpty) {
              _showDeleteConfirmation(selectedEntries);
            }
          },
        ),
      ],
    );
  }

  Widget _buildEntryTile(FileEntry entry, FileBrowserState state) {
    final icon = _fileIcon(entry.name, entry.isDirectory);
    final iconColor = _fileIconColor(entry.name, entry.isDirectory);
    final isSelected = state.selectedPaths.contains(entry.path);

    String subtitle = '';
    if (!entry.isDirectory) {
      subtitle = _formatSize(entry.size);
      if (entry.modified != null) {
        subtitle += '  ${_formatDate(entry.modified)}';
      }
    }

    if (state.isSelectionMode) {
      return ListTile(
        leading: Checkbox(
          value: isSelected,
          onChanged: (_) =>
              ref.read(fileBrowserProvider.notifier).toggleSelection(entry.path),
          activeColor: const Color(0xFF6366F1),
        ),
        title: Text(
          entry.name,
          style: TextStyle(color: textPrimary, fontSize: 14),
        ),
        subtitle: subtitle.isNotEmpty
            ? Text(subtitle, style: TextStyle(color: textSecondary, fontSize: 12))
            : null,
        selected: isSelected,
        selectedTileColor: const Color(0xFF6366F1).withValues(alpha: 0.08),
        onTap: () =>
            ref.read(fileBrowserProvider.notifier).toggleSelection(entry.path),
      );
    }

    return ListTile(
      leading: Icon(icon, color: iconColor),
      title: Text(
        entry.name,
        style: TextStyle(color: textPrimary, fontSize: 14),
      ),
      subtitle: subtitle.isNotEmpty
          ? Text(subtitle, style: TextStyle(color: textSecondary, fontSize: 12))
          : null,
      trailing: entry.isDirectory
          ? Icon(Icons.chevron_right, color: textSecondary, size: 20)
          : null,
      onTap: () {
        if (entry.isDirectory) {
          ref.read(fileBrowserProvider.notifier).navigateTo(entry.path);
        } else {
          _openFile(entry);
        }
      },
      onLongPress: () => _showEntryActions(entry),
    );
  }

  IconData _fileIcon(String name, bool isDirectory) {
    if (isDirectory) return Icons.folder;
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
      case 'webp':
      case 'bmp':
      case 'svg':
        return Icons.image;
      case 'mp3':
      case 'wav':
      case 'ogg':
      case 'm4a':
      case 'aac':
      case 'flac':
        return Icons.audiotrack;
      case 'html':
      case 'htm':
        return Icons.language;
      default:
        return Icons.insert_drive_file;
    }
  }

  Color _fileIconColor(String name, bool isDirectory) {
    if (isDirectory) return const Color(0xFFFBBF24);
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
        return Colors.blue.shade400;
      case 'json':
      case 'yaml':
      case 'yml':
      case 'toml':
      case 'ini':
      case 'conf':
        return Colors.orange.shade400;
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
      case 'bmp':
      case 'svg':
        return Colors.green.shade400;
      case 'mp3':
      case 'wav':
      case 'ogg':
      case 'm4a':
      case 'aac':
      case 'flac':
        return Colors.purple.shade400;
      case 'html':
      case 'htm':
        return Colors.teal.shade400;
      case 'md':
        return Colors.cyan.shade400;
      default:
        return textSecondary;
    }
  }
}

class _BreadcrumbSegment {
  final String label;
  final String path;
  const _BreadcrumbSegment({required this.label, required this.path});
}
