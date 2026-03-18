import 'package:flutter_test/flutter_test.dart';
import 'package:agentshell/data/models/chat_message.dart';

void main() {
  group('ChatBlock factories', () {
    test('text sets type=text and text field', () {
      final block = ChatBlock.text('hello');
      expect(block.type, ChatBlockType.text);
      expect(block.text, 'hello');
    });

    test('text(null) defaults text to empty string', () {
      final block = ChatBlock.text(null);
      expect(block.text, '');
    });

    test('thinking sets type=thinking and content field', () {
      final block = ChatBlock.thinking('deep thought');
      expect(block.type, ChatBlockType.thinking);
      expect(block.content, 'deep thought');
    });

    test('thinking(null) defaults content to empty string', () {
      final block = ChatBlock.thinking(null);
      expect(block.content, '');
    });

    test('toolCall sets toolName, summary, input', () {
      final block = ChatBlock.toolCall(
        toolName: 'bash',
        summary: 'run cmd',
        input: {'command': 'ls'},
      );
      expect(block.type, ChatBlockType.toolCall);
      expect(block.toolName, 'bash');
      expect(block.summary, 'run cmd');
      expect(block.input, {'command': 'ls'});
    });

    test('toolResult sets content and summary', () {
      final block = ChatBlock.toolResult(
        toolName: 'bash',
        content: 'output',
        summary: 'done',
      );
      expect(block.type, ChatBlockType.toolResult);
      expect(block.content, 'output');
      expect(block.summary, 'done');
    });

    test('image sets id, mimeType, altText', () {
      final block = ChatBlock.image(
        id: 'img1',
        mimeType: 'image/png',
        altText: 'screenshot',
      );
      expect(block.type, ChatBlockType.image);
      expect(block.id, 'img1');
      expect(block.mimeType, 'image/png');
      expect(block.altText, 'screenshot');
    });

    test('audio sets id, mimeType, durationSeconds', () {
      final block = ChatBlock.audio(
        id: 'aud1',
        mimeType: 'audio/wav',
        durationSeconds: 5.5,
      );
      expect(block.type, ChatBlockType.audio);
      expect(block.id, 'aud1');
      expect(block.mimeType, 'audio/wav');
      expect(block.durationSeconds, 5.5);
    });

    test('file sets id, filename, mimeType, sizeBytes', () {
      final block = ChatBlock.file(
        id: 'f1',
        filename: 'data.csv',
        mimeType: 'text/csv',
        sizeBytes: 1024,
      );
      expect(block.type, ChatBlockType.file);
      expect(block.id, 'f1');
      expect(block.filename, 'data.csv');
      expect(block.mimeType, 'text/csv');
      expect(block.sizeBytes, 1024);
    });
  });

  group('ChatMessage', () {
    final ts = DateTime.utc(2026, 3, 18, 12, 0, 0);
    final blocks = [ChatBlock.text('hello')];

    ChatMessage makeMsg({
      String id = 'msg1',
      ChatMessageType type = ChatMessageType.user,
      String? content = 'hi',
      List<ChatBlock>? msgBlocks,
      bool isStreaming = false,
    }) =>
        ChatMessage(
          id: id,
          type: type,
          content: content,
          timestamp: ts,
          blocks: msgBlocks ?? blocks,
          isStreaming: isStreaming,
        );

    test('copyWith preserves unmodified fields', () {
      final msg = makeMsg();
      final copy = msg.copyWith(content: 'updated');
      expect(copy.id, 'msg1');
      expect(copy.type, ChatMessageType.user);
      expect(copy.content, 'updated');
      expect(copy.timestamp, ts);
      expect(copy.blocks, blocks);
    });

    test('copyWith replaces blocks list', () {
      final newBlocks = [ChatBlock.text('new')];
      final msg = makeMsg();
      final copy = msg.copyWith(blocks: newBlocks);
      expect(copy.blocks, newBlocks);
      expect(copy.blocks, isNot(same(blocks)));
    });

    test('toJson serializes type as .name string', () {
      final json = makeMsg().toJson();
      expect(json['type'], 'user');
      expect(json['blocks'], isList);
      expect((json['blocks'] as List).first['type'], 'text');
    });

    test('fromJson round trip equality', () {
      final msg = makeMsg();
      final restored = ChatMessage.fromJson(msg.toJson());
      expect(restored, msg);
    });

    test('fromJson unknown message type falls back to system', () {
      final json = makeMsg().toJson();
      json['type'] = 'unknown_type';
      final restored = ChatMessage.fromJson(json);
      expect(restored.type, ChatMessageType.system);
    });

    test('fromJson unknown block type falls back to text', () {
      final json = makeMsg().toJson();
      (json['blocks'] as List).first['type'] = 'unknown_block';
      final restored = ChatMessage.fromJson(json);
      expect(restored.blocks.first.type, ChatBlockType.text);
    });

    test('fromJson null blocks defaults to empty list', () {
      final json = makeMsg().toJson();
      json['blocks'] = null;
      final restored = ChatMessage.fromJson(json);
      expect(restored.blocks, isEmpty);
    });

    test('fromJson missing isStreaming defaults to false', () {
      final json = makeMsg().toJson();
      json.remove('isStreaming');
      final restored = ChatMessage.fromJson(json);
      expect(restored.isStreaming, isFalse);
    });

    test('Equatable: equal when same props', () {
      expect(makeMsg(), makeMsg());
    });
  });

  group('ParsedChatBlock', () {
    test('construction preserves id, messages, summary', () {
      final messages = [
        ChatMessage(
          id: 'm1',
          type: ChatMessageType.user,
          timestamp: DateTime.utc(2026),
        ),
      ];
      final parsed = ParsedChatBlock(
        id: 'p1',
        messages: messages,
        summary: 'test summary',
      );
      expect(parsed.id, 'p1');
      expect(parsed.messages, messages);
      expect(parsed.summary, 'test summary');
    });
  });
}
