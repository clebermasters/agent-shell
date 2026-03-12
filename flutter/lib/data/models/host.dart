import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

class Host extends Equatable {
  final String id;
  final String name;
  final String address;
  final int port;
  final DateTime? lastConnected;

  const Host({
    required this.id,
    required this.name,
    required this.address,
    required this.port,
    this.lastConnected,
  });

  bool get _secure => kIsWeb || port == 443;

  String get wsUrl => _secure ? 'wss://$address' : 'ws://$address:$port';
  String get httpUrl => _secure ? 'https://$address' : 'http://$address:$port';

  Host copyWith({
    String? id,
    String? name,
    String? address,
    int? port,
    DateTime? lastConnected,
  }) {
    return Host(
      id: id ?? this.id,
      name: name ?? this.name,
      address: address ?? this.address,
      port: port ?? this.port,
      lastConnected: lastConnected ?? this.lastConnected,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'address': address,
    'port': port,
    'lastConnected': lastConnected?.toIso8601String(),
  };

  factory Host.fromJson(Map<String, dynamic> json) => Host(
    id: json['id'] as String,
    name: json['name'] as String,
    address: json['address'] as String,
    port: json['port'] as int,
    lastConnected: json['lastConnected'] != null
        ? DateTime.parse(json['lastConnected'] as String)
        : null,
  );

  @override
  List<Object?> get props => [id, name, address, port, lastConnected];
}
