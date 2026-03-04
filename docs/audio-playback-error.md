# Audio Playback Error Investigation

## Issue
When tapping the play button on audio files in the Flutter app, it shows "Failed to play audio: Source error".

## Environment
- **Backend**: Rust server running on port 4010
- **Flutter App**: Connected to `192.168.0.76:4010`
- **Audio file**: `/tmp/test_speech2.wav` (WAV format, ~400KB)

## What Works
- Backend receives file via WebSocket ✓
- File is saved to disk ✓
- File endpoint returns 200: `curl http://192.168.0.76:4010/api/chat/files/<id>` ✓
- Content-Type is correct: `audio/wav` ✓

## Flutter Code Implementation

### Audio Player Setup (`professional_message_bubble.dart`)
```dart
final AudioPlayer _audioPlayer = AudioPlayer();

Future<void> _playAudio(String blockId, String url, bool isPaused) async {
  if (_playingBlockId != blockId) {
    debugPrint('Audio URL: $url');
    await _audioPlayer.setUrl(url);  // <-- FAILS HERE
    _playingBlockId = blockId;
  }
  await _audioPlayer.play();
}
```

### URL being generated
```
http://192.168.0.76:4010/api/chat/files/57458011-92f1-4b4b-9f80-8ae119ba2a50
```

## Troubleshooting Steps Taken

### 1. Backend File Storage Fix
- **Problem**: Python's `mimetypes` returns `audio/x-wav` for .wav files
- **Fix**: Added `.trim_start_matches("x-")` in `chat_file_storage.rs` to strip the `x-` prefix
- **Result**: Files now save with correct `.wav` extension ✓

### 2. CORS Configuration
- Confirmed backend has CORS enabled for all origins
- File endpoint returns:
  ```
  access-control-allow-origin: *
  ```

### 3. Network Connectivity Test
```bash
$ curl -I http://192.168.0.76:4010/api/chat/files/57458011-92f1-4b4b-9f80-8ae119ba2a50
HTTP/1.1 200 OK
content-type: audio/wav
access-control-allow-origin: *
```
- Server is accessible from the Android device ✓

### 4. Added Debug Logging
- Added `debugPrint()` to log the URL and error message
- Waiting for user to rebuild APK and test

## Possible Causes

1. **just_audio HTTP headers issue**: just_audio may need specific headers to access the audio stream
2. **WAV format issue**: The WAV file may have a format that just_audio doesn't support
3. **Android permissions**: May need `INTERNET` permission (but images work, so this is unlikely)
4. **Android SSL/TLS**: If backend were HTTPS, would need network config

## Next Steps

1. Rebuild APK with debug logging enabled
2. Check logcat for the actual URL and error message
3. Try a different audio format (MP3) to see if it's WAV-specific
4. Try using a different approach for audio streaming

## Ideas to Try

### Option 1: Use Dio + AudioPlayer with local file
Download the file first, then play from local storage:
```dart
final file = File(await dio.download(url));
await _audioPlayer.setFilePath(file.path);
```

### Option 2: Add HTTP headers to just_audio
```dart
await _audioPlayer.setUrl(url, headers: {
  'Access-Control-Allow-Origin': '*',
});
```

### Option 3: Convert WAV to MP3 on backend
Use a library to convert audio to a more compatible format before saving.

## Related Files

- `backend-rust/src/chat_file_storage.rs` - File storage logic
- `backend-rust/src/main.rs` - File serving endpoint (line 132)
- `flutter/lib/features/chat/widgets/professional_message_bubble.dart` - Audio player UI
- `docs/chat-file-api.md` - API documentation
