# Android File Opening Issue - Investigation & Solutions

## Problem Summary

When users tap on a downloaded file in the Flutter app, we want to open it with the appropriate app (e.g., open HTML files in browser, PDF in PDF viewer, etc.). However, Android's security model prevents sharing direct `file://` URIs across app boundaries.

## Error Received

```
Exception: file://storage/emulated/0/Download/ai_news.html exposed beyond app through Intent.getData()
FileUriExposedException
```

This error occurs because:
1. Android 7.0+ enforces **Scoped Storage** and **FileProvider** requirements
2. Apps cannot share `file://` URIs with other apps via Intents
3. Must use `content://` URIs instead, which require a FileProvider

---

## What Was Tried

### Attempt 1: Using `am start` command (Shell)

```dart
final result = await Process.run('am', [
  'start',
  '-a',
  'android.intent.action.VIEW',
  '-d',
  'file://$filePath',
  '-t',
  mimeType ?? 'application/octet-stream',
]);
```

**Result**: Failed with `FileUriExposedException`

---

### Attempt 2: Copy to cache directory

```dart
final cacheDir = await getTemporaryDirectory();
final cacheFile = File('${cacheDir.path}/${filePath.split('/').last}');
await file.copy(cacheFile.path);

final result = await Process.run('am', [
  'start',
  '-a',
  'android.intent.action.VIEW',
  '-d',
  'content://${cacheFile.path}',  // Using content:// instead
  '-t',
  mimeType ?? 'application/octet-stream',
]);
```

**Result**: Still failed - `content://` URIs also don't work directly with shell commands

---

### Attempt 3: Using `url_launcher` package

```dart
import 'package:url_launcher/url_launcher.dart';

final uri = Uri.file(filePath);
if (await canLaunchUrl(uri)) {
  await launchUrl(uri);
}
```

**Result**: Failed with same `FileUriExposedException`

---

### Attempt 4: Using Android Intent directly via platform channel

```dart
// Using MethodChannel to call Android Intent
await channel.invokeMethod('openFile', {
  'path': filePath,
  'mimeType': mimeType,
});
```

**Not implemented** - would require writing native Kotlin/Swift code

---

### Current Solution (Simplified)

For now, we simply show the file location after download. The user can manually open files using a file manager app:

```dart
ScaffoldMessenger.of(context).showSnackBar(
  SnackBar(content: Text('Downloaded to Downloads/$filename')),
);
```

---

## Recommended Solutions

### Option 1: Use `open_file` package (Easiest)

There's a Flutter package specifically for this: **`open_file`** (https://pub.dev/packages/open_file)

It handles all the FileProvider complexity internally.

```dart
import 'package:open_file/open_file.dart';

void openFile(String filePath) {
  OpenFile.open(filePath);
}
```

**Steps to implement:**
1. Add to `pubspec.yaml`:
   ```yaml
   dependencies:
     open_file: ^3.5.0
   ```

2. Add to `AndroidManifest.xml`:
   ```xml
   <provider
       android:name="androidx.core.content.FileProvider"
       android:authorities="${applicationId}.fileprovider"
       android:exported="false"
       android:grantUriPermissions="true">
       <meta-data
           android:name="android.support.FILE_PROVIDER_PATHS"
           android:resource="@xml/file_paths" />
   </provider>
   ```

3. Create `android/app/src/main/res/xml/file_paths.xml`:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <paths>
       <external-path name="external_files" path="." />
       <cache-path name="cache" path="." />
       <files-path name="files" path="." />
   </paths>
   ```

4. Update download code to use `OpenFile.open(filePath)` instead of custom logic

---

### Option 2: Use `file_picker` + `open_file` combination

The `file_picker` package already has internal handling for opening files:

```dart
import 'package:file_picker/file_picker.dart';

// After downloading the file:
await FilePicker.platform.openFile(path: filePath);
```

---

### Option 3: Manual FileProvider Setup (Most Control)

If you need full control:

1. Create `FileProvider` subclass in Kotlin:
   ```kotlin
   // android/app/src/main/kotlin/.../MyFileProvider.kt
   class MyFileProvider: FileProvider {
       // Inherit from FileProvider
   }
   ```

2. Configure in `AndroidManifest.xml`:
   ```xml
   <provider
       android:name=".MyFileProvider"
       android:authorities="${applicationId}.fileprovider"
       android:exported="false"
       android:grantUriPermissions="true">
       <meta-data
           android:name="android.support.FILE_PROVIDER_PATHS"
           android:resource="@xml/file_paths" />
   </provider>
   ```

3. Create platform channel to convert `file://` to `content://`:
   ```dart
   static const channel = MethodChannel('com.example.webmux/files');
   
   Future<String> getContentUri(String filePath) async {
     return await channel.invokeMethod('getContentUri', {'path': filePath});
   }
   ```

4. Implement in Kotlin:
   ```kotlin
   @Override
   fun getUriForFile(...): Uri {
       return FileProvider.getUriForFile(context, authority, file)
   }
   ```

---

## Files to Modify

When implementing the fix, modify:

1. **`flutter/pubspec.yaml`** - Add dependency
2. **`flutter/lib/features/chat/widgets/professional_message_bubble.dart`** - Update `_downloadAndOpenFile` and `_openFile` methods
3. **`flutter/android/app/src/main/AndroidManifest.xml`** - Add FileProvider (if not using open_file package)
4. **`flutter/android/app/src/main/res/xml/file_paths.xml`** - Create this file for path configuration

---

## Testing

To test file opening:

1. Send an HTML file via WebSocket:
   ```python
   msg = {
       "type": "send-file-to-chat",
       "sessionName": "webmux",
       "windowIndex": 0,
       "file": {
           "filename": "test.html",
           "mimeType": "text/html",
           "data": "<base64-encoded-data>"
       }
   }
   ```

2. In Flutter app, tap on the file attachment
3. File downloads to Downloads folder
4. Tap "Open" or file should auto-open

---

## Related Code

- **Download logic**: `flutter/lib/features/chat/widgets/professional_message_bubble.dart` - `_downloadAndOpenFile()` method
- **File block UI**: `flutter/lib/features/chat/widgets/professional_message_bubble.dart` - `_buildFileBlock()` method
- **Backend file storage**: `backend-rust/src/chat_file_storage.rs`
- **Backend API endpoint**: `backend-rust/src/main.rs` - `/api/chat/files/:id` route

---

## References

- [Android FileProvider Documentation](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [open_file package](https://pub.dev/packages/open_file)
- [file_picker package](https://pub.dev/packages/file_picker)
- [Android Scoped Storage](https://developer.android.com/docs/topics/media/scoped-storage)
- [FileUriExposedException](https://developer.android.com/reference/android/os/FileUriExposedException)
