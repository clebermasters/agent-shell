package com.agentshell.feature.chat

import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTerminalSplitTest {
    @Test
    fun autoStacksPortraitPhonesAndUsesAvailableWidthOnLandscapeAndTablets() {
        assertEquals(ChatSplitOrientation.STACKED, resolveChatSplitOrientation(ChatSplitOrientation.AUTO, 400, 800))
        assertEquals(ChatSplitOrientation.SIDE_BY_SIDE, resolveChatSplitOrientation(ChatSplitOrientation.AUTO, 800, 400))
        assertEquals(ChatSplitOrientation.SIDE_BY_SIDE, resolveChatSplitOrientation(ChatSplitOrientation.AUTO, 1280, 800))
        assertEquals(ChatSplitOrientation.SIDE_BY_SIDE, resolveChatSplitOrientation(ChatSplitOrientation.AUTO, 840, 1200))
        assertEquals(ChatSplitOrientation.STACKED, resolveChatSplitOrientation(ChatSplitOrientation.AUTO, 600, 900))
    }

    @Test
    fun explicitOrientationSurvivesScreenShapeChanges() {
        for ((width, height) in listOf(400 to 800, 800 to 400, 1280 to 800)) {
            assertEquals(ChatSplitOrientation.STACKED, resolveChatSplitOrientation(ChatSplitOrientation.STACKED, width, height))
            assertEquals(ChatSplitOrientation.SIDE_BY_SIDE, resolveChatSplitOrientation(ChatSplitOrientation.SIDE_BY_SIDE, width, height))
        }
    }

    @Test
    fun userRatioIsHonoredWhenBothPanesFit() {
        assertEquals(600 to 400, calculateChatSplitSizes(1000, 0.6f, 80))
        assertEquals(250 to 750, calculateChatSplitSizes(1000, 0.25f, 120))
        assertEquals(800 to 200, calculateChatSplitSizes(1000, 0.8f, 80))
    }

    @Test
    fun keyboardAndSmallViewportsKeepBothPanesWithinBounds() {
        assertEquals(120 to 180, calculateChatSplitSizes(300, 0.2f, 120))
        assertEquals(180 to 120, calculateChatSplitSizes(300, 0.8f, 120))
        assertEquals(50 to 50, calculateChatSplitSizes(100, 0.2f, 120))
        assertEquals(51 to 50, calculateChatSplitSizes(101, 0.8f, 120))
        assertEquals(0 to 0, calculateChatSplitSizes(0, 0.6f, 120))
        assertEquals(0 to 0, calculateChatSplitSizes(-100, 0.6f, 120))
    }

    @Test
    fun malformedRatiosFallBackToBalancedAndFiniteRatiosStayClamped() {
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(500 to 500, calculateChatSplitSizes(1000, fraction, 80))
        }
        assertEquals(200 to 800, calculateChatSplitSizes(1000, -10f, 80))
        assertEquals(800 to 200, calculateChatSplitSizes(1000, 10f, 80))
        assertEquals(200 to 800, calculateChatSplitSizes(1000, 0.2f, -120))
    }

    @Test
    fun roundingAndMinimumsNeverLosePixelsOrCreateNegativePanes() {
        for (available in listOf(0, 1, 2, 23, 101, 160, 239, 240, 479, 1001)) {
            for (minimum in listOf(-1, 0, 80, 120)) {
                for (fraction in listOf(Float.NaN, 0f, 0.2f, 0.333f, 0.6f, 0.8f, 1f)) {
                    val (chat, terminal) = calculateChatSplitSizes(available, fraction, minimum)
                    assertEquals(available, chat + terminal)
                    assertTrue("chat=$chat, available=$available", chat in 0..available)
                    assertTrue("terminal=$terminal, available=$available", terminal in 0..available)
                    val effectiveMinimum = minimum.coerceIn(0, available / 2)
                    assertTrue(chat >= effectiveMinimum)
                    assertTrue(terminal >= effectiveMinimum)
                }
            }
        }
    }

    @Test
    fun swappingPaneOrderPreservesChatAndTerminalSizes() {
        val preferences = ChatSplitPreferences(stackedFraction = 0.7f, sideBySideFraction = 0.35f)
        val swapped = preferences.copy(chatFirst = false)
        for (orientation in listOf(ChatSplitOrientation.STACKED, ChatSplitOrientation.SIDE_BY_SIDE)) {
            assertEquals(
                calculateChatSplitSizes(1000, preferences.fractionFor(orientation), 80),
                calculateChatSplitSizes(1000, swapped.fractionFor(orientation), 80),
            )
        }
    }
}
