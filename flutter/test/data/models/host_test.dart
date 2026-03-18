import 'package:flutter_test/flutter_test.dart';
import 'package:agentshell/data/models/host.dart';

void main() {
  group('Host', () {
    final now = DateTime(2026, 3, 18, 12, 0, 0);

    Host makeHost({
      String id = 'h1',
      String name = 'Test',
      String address = 'example.com',
      int port = 4010,
      DateTime? lastConnected,
    }) =>
        Host(
          id: id,
          name: name,
          address: address,
          port: port,
          lastConnected: lastConnected,
        );

    group('wsUrl', () {
      // Note: kIsWeb is false in test environment
      test('returns ws:// for non-443 port', () {
        final host = makeHost(port: 4010);
        expect(host.wsUrl, 'ws://example.com:4010');
      });

      test('returns wss:// for port 443', () {
        final host = makeHost(port: 443);
        expect(host.wsUrl, 'wss://example.com');
      });
    });

    group('httpUrl', () {
      test('returns http:// for non-443 port', () {
        final host = makeHost(port: 8080);
        expect(host.httpUrl, 'http://example.com:8080');
      });

      test('returns https:// for port 443', () {
        final host = makeHost(port: 443);
        expect(host.httpUrl, 'https://example.com');
      });
    });

    group('copyWith', () {
      test('preserves unmodified fields', () {
        final host = makeHost(lastConnected: now);
        final copy = host.copyWith(name: 'Updated');
        expect(copy.id, 'h1');
        expect(copy.name, 'Updated');
        expect(copy.address, 'example.com');
        expect(copy.port, 4010);
        expect(copy.lastConnected, now);
      });

      test('updates specified field', () {
        final host = makeHost();
        final copy = host.copyWith(port: 443);
        expect(copy.port, 443);
        expect(copy.address, host.address);
      });
    });

    group('toJson', () {
      test('contains all 5 keys', () {
        final host = makeHost(lastConnected: now);
        final json = host.toJson();
        expect(json.keys, containsAll(['id', 'name', 'address', 'port', 'lastConnected']));
        expect(json.length, 5);
      });
    });

    group('fromJson', () {
      test('round trip equality', () {
        final host = makeHost(lastConnected: now);
        final restored = Host.fromJson(host.toJson());
        expect(restored, host);
      });

      test('with null lastConnected', () {
        final host = makeHost();
        final json = host.toJson();
        expect(json['lastConnected'], isNull);
        final restored = Host.fromJson(json);
        expect(restored.lastConnected, isNull);
      });
    });

    group('Equatable', () {
      test('equal when same props', () {
        expect(makeHost(), makeHost());
      });

      test('unequal when port differs', () {
        expect(makeHost(port: 4010) == makeHost(port: 443), isFalse);
      });
    });
  });
}
