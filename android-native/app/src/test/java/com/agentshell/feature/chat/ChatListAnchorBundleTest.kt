package com.agentshell.feature.chat

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatListAnchorBundleTest {
    @Suppress("DEPRECATION")
    @Test fun everyLayoutAnchorSurvivesAndroidSavedStateParceling() {
        for (anchor in ChatListAnchor.entries) {
            val source = Bundle().apply { putSerializable("row", anchor) }
            val writer = Parcel.obtain()
            val bytes = try { source.writeToParcel(writer, 0); writer.marshall() }
                finally { writer.recycle() }
            val reader = Parcel.obtain()
            try {
                reader.unmarshall(bytes, 0, bytes.size)
                reader.setDataPosition(0)
                val restored = Bundle.CREATOR.createFromParcel(reader)
                restored.classLoader = ChatListAnchor::class.java.classLoader
                assertEquals(anchor, restored.getSerializable("row"))
            } finally { reader.recycle() }
        }
    }

    @Test fun olderHistoryRowIsExcludedFromMessageReadTracking() {
        val key: Any = ChatListAnchor.OlderHistory
        assertFalse(key is String)
        assertEquals("message-id", lastVisibleChatMessageKey(listOf("message-id", key)))
        assertNull(lastVisibleChatMessageKey(listOf(key)))
    }
}
