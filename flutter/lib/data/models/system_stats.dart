import 'package:equatable/equatable.dart';

class SystemStats extends Equatable {
  final double cpuUsage;
  final int cpuCores;
  final String cpuModel;
  final double loadAvg5m;
  final double loadAvg15m;
  final double memoryUsage;
  final int memoryTotal;
  final int memoryUsed;
  final int memoryFree;
  final double diskUsage;
  final int diskTotal;
  final int diskUsed;
  final int diskFree;
  final String hostname;
  final String platform;
  final String arch;
  final String uptime;
  final DateTime timestamp;

  const SystemStats({
    required this.cpuUsage,
    required this.cpuCores,
    required this.cpuModel,
    required this.loadAvg5m,
    required this.loadAvg15m,
    required this.memoryUsage,
    required this.memoryTotal,
    required this.memoryUsed,
    required this.memoryFree,
    required this.diskUsage,
    required this.diskTotal,
    required this.diskUsed,
    required this.diskFree,
    required this.hostname,
    required this.platform,
    required this.arch,
    required this.uptime,
    required this.timestamp,
  });

  Map<String, dynamic> toJson() => {
    'cpuUsage': cpuUsage,
    'cpuCores': cpuCores,
    'cpuModel': cpuModel,
    'loadAvg5m': loadAvg5m,
    'loadAvg15m': loadAvg15m,
    'memoryUsage': memoryUsage,
    'memoryTotal': memoryTotal,
    'memoryUsed': memoryUsed,
    'memoryFree': memoryFree,
    'diskUsage': diskUsage,
    'diskTotal': diskTotal,
    'diskUsed': diskUsed,
    'diskFree': diskFree,
    'hostname': hostname,
    'platform': platform,
    'arch': arch,
    'uptime': uptime,
    'timestamp': timestamp.toIso8601String(),
  };

  factory SystemStats.fromJson(Map<String, dynamic> json) {
    // Handle current format: { type: 'stats', stats: { cpu: {...}, memory: {...}, disk: {...}, ... } }
    if (json.containsKey('stats')) {
      final stats = json['stats'] as Map<String, dynamic>;
      final cpu = stats['cpu'] as Map<String, dynamic>?;
      final memory = stats['memory'] as Map<String, dynamic>?;
      final disk = stats['disk'] as Map<String, dynamic>?;
      final loadAvg = cpu?['loadAvg'] as List<dynamic>?;

      final memTotal = (memory?['total'] as num?)?.toInt() ?? 0;
      final memUsed = (memory?['used'] as num?)?.toInt() ?? 0;
      final memFree = (memory?['free'] as num?)?.toInt() ?? 0;

      final diskTotal = (disk?['total'] as num?)?.toInt() ?? 0;
      final diskUsed = (disk?['used'] as num?)?.toInt() ?? 0;
      final diskFree = (disk?['free'] as num?)?.toInt() ?? 0;

      return SystemStats(
        cpuUsage: (cpu?['usage'] as num?)?.toDouble() ?? 0.0,
        cpuCores: (cpu?['cores'] as num?)?.toInt() ?? 0,
        cpuModel: cpu?['model'] as String? ?? '',
        loadAvg5m: (loadAvg != null && loadAvg.length > 1)
            ? (loadAvg[1] as num).toDouble()
            : 0.0,
        loadAvg15m: (loadAvg != null && loadAvg.length > 2)
            ? (loadAvg[2] as num).toDouble()
            : 0.0,
        memoryUsage: memTotal > 0 ? (memUsed / memTotal * 100).toDouble() : 0.0,
        memoryTotal: memTotal,
        memoryUsed: memUsed,
        memoryFree: memFree,
        diskUsage: diskTotal > 0 ? (diskUsed / diskTotal * 100).toDouble() : 0.0,
        diskTotal: diskTotal,
        diskUsed: diskUsed,
        diskFree: diskFree,
        hostname: stats['hostname'] as String? ?? '',
        platform: stats['platform'] as String? ?? '',
        arch: stats['arch'] as String? ?? '',
        uptime: _formatUptime(stats['uptime'] as int? ?? 0),
        timestamp: DateTime.now(),
      );
    }

    // Handle legacy format: { cpuUsage: ..., memoryUsage: ... }
    return SystemStats(
      cpuUsage: (json['cpuUsage'] as num).toDouble(),
      cpuCores: (json['cpuCores'] as num?)?.toInt() ?? 0,
      cpuModel: json['cpuModel'] as String? ?? '',
      loadAvg5m: (json['loadAvg5m'] as num?)?.toDouble() ?? 0.0,
      loadAvg15m: (json['loadAvg15m'] as num?)?.toDouble() ?? 0.0,
      memoryUsage: (json['memoryUsage'] as num).toDouble(),
      memoryTotal: json['memoryTotal'] as int,
      memoryUsed: json['memoryUsed'] as int,
      memoryFree: (json['memoryFree'] as num?)?.toInt() ?? 0,
      diskUsage: (json['diskUsage'] as num).toDouble(),
      diskTotal: json['diskTotal'] as int,
      diskUsed: json['diskUsed'] as int,
      diskFree: (json['diskFree'] as num?)?.toInt() ?? 0,
      hostname: json['hostname'] as String? ?? '',
      platform: json['platform'] as String? ?? '',
      arch: json['arch'] as String? ?? '',
      uptime: json['uptime'] as String,
      timestamp: DateTime.parse(json['timestamp'] as String),
    );
  }

  static String _formatUptime(int seconds) {
    final days = seconds ~/ 86400;
    final hours = (seconds % 86400) ~/ 3600;
    final minutes = (seconds % 3600) ~/ 60;
    if (days > 0) {
      return '${days}d ${hours}h ${minutes}m';
    } else if (hours > 0) {
      return '${hours}h ${minutes}m';
    } else {
      return '${minutes}m';
    }
  }

  String get memoryUsedFormatted => _formatBytes(memoryUsed);
  String get memoryTotalFormatted => _formatBytes(memoryTotal);
  String get memoryFreeFormatted => _formatBytes(memoryFree);
  String get diskUsedFormatted => _formatBytes(diskUsed);
  String get diskTotalFormatted => _formatBytes(diskTotal);
  String get diskFreeFormatted => _formatBytes(diskFree);

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  @override
  List<Object?> get props => [
    cpuUsage,
    cpuCores,
    cpuModel,
    loadAvg5m,
    loadAvg15m,
    memoryUsage,
    memoryTotal,
    memoryUsed,
    memoryFree,
    diskUsage,
    diskTotal,
    diskUsed,
    diskFree,
    hostname,
    platform,
    arch,
    uptime,
    timestamp,
  ];
}
