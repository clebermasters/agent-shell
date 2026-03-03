import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';
import '../../../data/models/host.dart';
import '../../../core/providers.dart';
import '../../../core/config/app_config.dart';

final hostsProvider = StateNotifierProvider<HostsNotifier, HostsState>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  return HostsNotifier(prefs);
});

class HostsState {
  final List<Host> hosts;
  final Host? selectedHost;

  const HostsState({required this.hosts, this.selectedHost});

  HostsState copyWith({List<Host>? hosts, Host? selectedHost}) {
    return HostsState(
      hosts: hosts ?? this.hosts,
      selectedHost: selectedHost ?? this.selectedHost,
    );
  }
}

class HostsNotifier extends StateNotifier<HostsState> {
  final SharedPreferences _prefs;
  static const String _hostsKey = 'saved_hosts';
  static const String _selectedHostIdKey = 'selected_host_id';

  HostsNotifier(this._prefs) : super(const HostsState(hosts: [])) {
    _loadHosts();
  }

  void _loadHosts() {
    final hostsJsonList = _prefs.getStringList(_hostsKey) ?? [];
    final hosts = hostsJsonList
        .map((jsonStr) => Host.fromJson(jsonDecode(jsonStr)))
        .toList();

    // Default host if none exists
    if (hosts.isEmpty) {
      // Check for build-time default servers
      final buildTimeServers = AppConfig.parsedServerList;

      if (buildTimeServers.isNotEmpty) {
        // Use build-time servers
        for (final server in buildTimeServers) {
          hosts.add(
            Host(
              id: const Uuid().v4(),
              name: server['name'] as String,
              address: server['address'] as String,
              port: server['port'] as int,
            ),
          );
        }
      } else {
        // No servers available - user will need to add manually
        // No hardcoded default
      }
      _saveHosts(hosts);
    }

    final selectedHostId = _prefs.getString(_selectedHostIdKey);
    Host? selectedHost;

    if (selectedHostId != null) {
      selectedHost = hosts.where((h) => h.id == selectedHostId).firstOrNull;
    }

    if (selectedHost == null && hosts.isNotEmpty) {
      selectedHost = hosts.first;
      _prefs.setString(_selectedHostIdKey, selectedHost.id);
    }

    state = HostsState(hosts: hosts, selectedHost: selectedHost);
  }

  void _saveHosts(List<Host> hosts) {
    final hostsJsonList = hosts.map((h) => jsonEncode(h.toJson())).toList();
    _prefs.setStringList(_hostsKey, hostsJsonList);
  }

  void addHost(Host host) {
    final newHosts = [...state.hosts, host];
    _saveHosts(newHosts);
    state = state.copyWith(hosts: newHosts);

    // Auto-select if it's the only one
    if (state.selectedHost == null) {
      selectHost(host);
    }
  }

  void updateHost(Host updatedHost) {
    final index = state.hosts.indexWhere((h) => h.id == updatedHost.id);
    if (index >= 0) {
      final newHosts = [...state.hosts];
      newHosts[index] = updatedHost;
      _saveHosts(newHosts);

      Host? newSelectedHost = state.selectedHost;
      if (state.selectedHost?.id == updatedHost.id) {
        newSelectedHost = updatedHost;
      }

      state = state.copyWith(hosts: newHosts, selectedHost: newSelectedHost);
    }
  }

  void removeHost(String id) {
    final newHosts = state.hosts.where((h) => h.id != id).toList();
    _saveHosts(newHosts);

    Host? newSelectedHost = state.selectedHost;
    if (state.selectedHost?.id == id) {
      newSelectedHost = newHosts.isNotEmpty ? newHosts.first : null;
      if (newSelectedHost != null) {
        _prefs.setString(_selectedHostIdKey, newSelectedHost.id);
      } else {
        _prefs.remove(_selectedHostIdKey);
      }
    }

    state = state.copyWith(hosts: newHosts, selectedHost: newSelectedHost);
  }

  void selectHost(Host host) {
    _prefs.setString(_selectedHostIdKey, host.id);
    state = state.copyWith(selectedHost: host);
  }
}
