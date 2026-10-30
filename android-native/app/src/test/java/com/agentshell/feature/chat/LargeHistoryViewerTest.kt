package com.agentshell.feature.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LargeHistoryViewerTest {
    @Test fun pagesPreserveEveryUnicodeCharacterAtByteBoundaries() {
        val file = File.createTempFile("history-page", ".json")
        try {
            val original = "{\"text\":\"${"hé😀llo\\n".repeat(500)}\"}"
            file.writeText(original)
            var offset = 0L
            val reconstructed = StringBuilder()
            do {
                val page = readHistoryFilePage(file, offset, 17)
                assertTrue(page.nextOffset > offset)
                assertTrue(page.text.toByteArray().size <= 17)
                reconstructed.append(page.text)
                offset = page.nextOffset
            } while (page.hasMore)
            assertEquals(original, reconstructed.toString())
            assertEquals(file.length(), offset)
        } finally { file.delete() }
    }
}
