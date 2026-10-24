package com.agentshell.data.model

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatTargetTest {
    @Test fun cachedIdentityRemainsCompatibleWithPreviouslyStoredKeys() {
        val target = ChatTarget.create("server-a", "my session", 1)
        val legacyInput = listOf(target.hostId, target.isAcp.toString(), target.sessionName, target.windowIndex.toString()).joinToString("\u0000")
        val legacyKey = MessageDigest.getInstance("SHA-256").digest(legacyInput.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(legacyKey, target.key)
        assertSame(target.key, target.key)
        assertEquals(target.key, target.copy(cwd = "/other", title = "New title").key)
        assertNotEquals(target.key, target.copy(windowIndex = 2).key)
    }

    @Test fun computedKeyIsNotSerializedAndIsRebuiltAfterRestore() {
        val target = ChatTarget.create("server-a", "acp_codex:thread", isAcp = true)
        val encoded = Json.encodeToString(target)
        assertFalse(encoded.contains("\"key\""))
        val restored = Json.decodeFromString<ChatTarget>(encoded)
        assertEquals(target.key, restored.key)
        assertEquals(target, restored)
    }

    @Test fun efficientHexEncodingKeepsStandardSha256Representation() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", chatFingerprint("abc"))
    }
}
