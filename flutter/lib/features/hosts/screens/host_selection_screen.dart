import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';
import '../../../data/models/host.dart';
import '../providers/hosts_provider.dart';

class HostSelectionScreen extends ConsumerWidget {
  const HostSelectionScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hostsState = ref.watch(hostsProvider);
    final hostsNotifier = ref.read(hostsProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Servers'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () => _showHostForm(context, hostsNotifier),
            tooltip: 'Add Server',
          ),
        ],
      ),
      body: hostsState.hosts.isEmpty
          ? const Center(child: Text('No servers added.'))
          : ListView.builder(
              itemCount: hostsState.hosts.length,
              itemBuilder: (context, index) {
                final host = hostsState.hosts[index];
                final isSelected = hostsState.selectedHost?.id == host.id;

                return ListTile(
                  leading: Icon(
                    isSelected ? Icons.check_circle : Icons.dns,
                    color: isSelected ? Colors.green : null,
                  ),
                  title: Text(host.name),
                  subtitle: Text('${host.address}:${host.port}'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.edit),
                        onPressed: () =>
                            _showHostForm(context, hostsNotifier, host: host),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete),
                        onPressed: () {
                          // Prevent deleting the last remaining host to avoid breaking the app state
                          if (hostsState.hosts.length > 1) {
                            hostsNotifier.removeHost(host.id);
                          } else {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Cannot delete the only server.'),
                              ),
                            );
                          }
                        },
                      ),
                    ],
                  ),
                  onTap: () {
                    hostsNotifier.selectHost(host);
                    Navigator.pop(context); // Close screen after selection
                  },
                );
              },
            ),
    );
  }

  void _showHostForm(
    BuildContext context,
    HostsNotifier notifier, {
    Host? host,
  }) {
    showDialog(
      context: context,
      builder: (context) => HostFormDialog(
        initialHost: host,
        onSave: (newHost) {
          if (host == null) {
            notifier.addHost(newHost);
          } else {
            notifier.updateHost(newHost);
          }
        },
      ),
    );
  }
}

class HostFormDialog extends StatefulWidget {
  final Host? initialHost;
  final Function(Host) onSave;

  const HostFormDialog({super.key, this.initialHost, required this.onSave});

  @override
  State<HostFormDialog> createState() => _HostFormDialogState();
}

class _HostFormDialogState extends State<HostFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _name;
  late String _address;
  late int _port;

  @override
  void initState() {
    super.initState();
    _name = widget.initialHost?.name ?? '';
    _address = widget.initialHost?.address ?? '';
    _port = widget.initialHost?.port ?? 4010;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.initialHost == null ? 'Add Server' : 'Edit Server'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                initialValue: _name,
                decoration: const InputDecoration(
                  labelText: 'Server Name',
                  hintText: 'e.g., Home PI',
                  prefixIcon: Icon(Icons.dns),
                  border: OutlineInputBorder(),
                ),
                validator: (val) =>
                    val == null || val.isEmpty ? 'Please enter a name' : null,
                onSaved: (val) => _name = val!,
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 16),
              TextFormField(
                initialValue: _address,
                decoration: const InputDecoration(
                  labelText: 'Address',
                  hintText: 'IP address or domain',
                  prefixIcon: Icon(Icons.router),
                  border: OutlineInputBorder(),
                ),
                validator: (val) => val == null || val.isEmpty
                    ? 'Please enter an address'
                    : null,
                onSaved: (val) => _address = val!,
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 16),
              TextFormField(
                initialValue: _port.toString(),
                decoration: const InputDecoration(
                  labelText: 'Port',
                  hintText: 'e.g., 4010',
                  prefixIcon: Icon(Icons.numbers),
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.number,
                validator: (val) {
                  final port = int.tryParse(val ?? '');
                  if (port == null || port <= 0 || port > 65535) {
                    return 'Enter a valid port (1-65535)';
                  }
                  return null;
                },
                onSaved: (val) => _port = int.parse(val!),
                textInputAction: TextInputAction.done,
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              _formKey.currentState!.save();
              final host = Host(
                id: widget.initialHost?.id ?? const Uuid().v4(),
                name: _name,
                address: _address,
                port: _port,
              );
              widget.onSave(host);
              Navigator.pop(context);
            }
          },
          child: const Text('Save'),
        ),
      ],
    );
  }
}
