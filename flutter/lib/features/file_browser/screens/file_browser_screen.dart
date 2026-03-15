import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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

  void _showEntryInfo(FileEntry entry) {
    showModalBottomSheet(
      context: context,
      backgroundColor: surfaceColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
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
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.copy, size: 18),
                  label: const Text('Copy path'),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: entry.path));
                    Navigator.pop(context);
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('Path copied'),
                        duration: Duration(seconds: 1),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
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
      ),
      body: Column(
        children: [
          if (_showSearch)
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
                    return _buildEntryTile(entry);
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEntryTile(FileEntry entry) {
    final icon = _fileIcon(entry.name, entry.isDirectory);
    final iconColor = _fileIconColor(entry.name, entry.isDirectory);

    String subtitle = '';
    if (!entry.isDirectory) {
      subtitle = _formatSize(entry.size);
      if (entry.modified != null) {
        subtitle += '  ${_formatDate(entry.modified)}';
      }
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
      onLongPress: () => _showEntryInfo(entry),
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
      case 'svg':
        return Icons.image;
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
      case 'svg':
        return Colors.green.shade400;
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
