package com.agentshell.core.util

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShareIntentParserTest {
    @Test fun receivesUnicodeTextAndLinksWithoutChangingThem() {
        val text = "Revisão • https://example.com/path?a=1&b=2"
        val share = ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
        assertEquals(text, share.text)
        assertTrue(share.uris.isEmpty())
    }

    @Test fun imageAndAccompanyingUrlAreBothRetained() {
        val uri = Uri.parse("content://photos/screenshot")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "https://example.com")
        val share = ShareIntentParser.parse(intent)
        assertEquals(listOf(uri.toString()), share.uris)
        assertEquals("https://example.com", share.text)
        assertEquals("image/png", share.mimeType)
    }

    @Test fun acceptsMultipleFilesAndRemovesDuplicateUris() {
        val first = Uri.parse("content://files/a")
        val second = Uri.parse("content://files/b")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        assertEquals(listOf(first.toString(), second.toString()), ShareIntentParser.parse(intent).uris)
    }

    @Test fun readsClipDataWhenSenderDoesNotProvideStreamExtra() {
        val uri = Uri.parse("content://files/a")
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").apply {
            clipData = ClipData.newRawUri("PDF", uri)
        }
        assertEquals(listOf(uri.toString()), ShareIntentParser.parse(intent).uris)
    }

    @Test fun clipDataDoesNotDuplicateTheStreamExtra() {
        val uri = Uri.parse("content://files/a")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).apply {
            clipData = ClipData.newRawUri("image", uri)
        }
        assertEquals(1, ShareIntentParser.parse(intent).uris.size)
    }

    @Test fun acceptsTextProvidedOnlyInClipData() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            clipData = ClipData.newPlainText("shared", "A link https://example.com")
        }
        assertEquals("A link https://example.com", ShareIntentParser.parse(intent).text)
    }

    @Test fun shareMenuResolvesTextImagesDocumentsAndMultipleFilesToAgentShell() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("text/plain", "image/png", "application/pdf", "audio/mpeg")) {
                val matches = app.packageManager.queryIntentActivities(Intent(action).setType(mime), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                assertTrue("Missing share target for $action $mime", matches.any { it.activityInfo.name == "com.agentshell.MainActivity" })
            }
        }
    }

    @Test fun ordinaryLaunchAndNotificationAreNotShares() {
        assertFalse(ShareIntentParser.isShare(Intent(Intent.ACTION_MAIN)))
        assertFalse(ShareIntentParser.isShare(Intent("com.agentshell.OPEN_CHAT")))
        assertFalse(ShareIntentParser.isShare(null))
    }

    @Test fun rejectsFilesThatCouldExposePrivateAppPaths() {
        assertThrows(IOException::class.java) {
            ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/com.agentshell/private")))
        }
    }

    @Test fun rejectsEmptyAndOversizedShares() {
        assertThrows(IOException::class.java) { ShareIntentParser.parse(Intent(Intent.ACTION_SEND)) }
        assertThrows(IOException::class.java) {
            ShareIntentParser.parse(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "a".repeat(100_001)))
        }
        assertThrows(IOException::class.java) {
            ShareIntentParser.parse(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                ArrayList((0..20).map { Uri.parse("content://files/$it") })))
        }
    }
}
