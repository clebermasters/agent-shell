package com.agentshell.core.util

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.model.ChatTarget
import com.agentshell.data.model.AgentSignal
import com.agentshell.data.model.AgentSignalKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatNotificationTest {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private val first = ChatTarget.create("host-1", "project / special 名", 2)

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannel(app)
    }

    @Test fun chatChannelHasHighImportanceSoundAndVibration() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHAT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
    }

    @Test fun finishedAndQuietAlertsUseIndependentChannelsAndTapTargets() {
        NotificationHelper.showChat(app, first, "A reply", 1)
        NotificationHelper.showAgentSignal(app, first, AgentSignal(AgentSignalKind.FINISHED, 120))
        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        val finished = notifications.single { it.channelId == NotificationHelper.FINISHED_CHANNEL_ID }
        assertEquals(first, NotificationHelper.chatTarget(shadowOf(finished.contentIntent).savedIntent))
        assertNotNull(manager.getNotificationChannel(NotificationHelper.FINISHED_CHANNEL_ID).sound)
        NotificationHelper.showAgentSignal(app, first, AgentSignal(AgentSignalKind.QUIET))
        assertEquals(2, shadowOf(manager).allNotifications.size)
        assertTrue(shadowOf(manager).allNotifications.any { it.channelId == NotificationHelper.QUIET_CHANNEL_ID })
        assertNotNull(manager.getNotificationChannel(NotificationHelper.QUIET_CHANNEL_ID).sound)
    }

    @Test fun anotherFinishedTurnCanAlertWhilePreviousNoticeIsUnread() {
        NotificationHelper.showAgentSignal(app, first, AgentSignal(AgentSignalKind.FINISHED))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        NotificationHelper.showAgentSignal(app, first, AgentSignal(AgentSignalKind.FINISHED))
        assertEquals(0, shadowOf(manager).allNotifications.single().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun twoChatsHaveSeparateNotificationsAndCorrectTapTargets() {
        val second = first.copy(windowIndex = 3)
        NotificationHelper.showChat(app, first, "First reply", 1)
        NotificationHelper.showChat(app, second, "Second reply", 2)
        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        val destinations = notifications.map { NotificationHelper.chatTarget(shadowOf(it.contentIntent).savedIntent) }.toSet()
        assertEquals(setOf(first, second), destinations)
        assertNotEquals(notifications[0].contentIntent, notifications[1].contentIntent)
        assertTrue(notifications.all { it.flags and Notification.FLAG_AUTO_CANCEL != 0 })
    }

    @Test fun updatesReplaceOnlyThatChatsNotificationAndReadCancelsIt() {
        val second = first.copy(hostId = "host-2")
        NotificationHelper.showChat(app, first, "First", 1)
        NotificationHelper.showChat(app, second, "Other server", 1)
        NotificationHelper.showChat(app, first, "Updated", 2, silent = true)
        assertEquals(2, shadowOf(manager).allNotifications.size)
        NotificationHelper.cancelChat(app, first)
        val remaining = shadowOf(manager).allNotifications.single()
        assertEquals(second, NotificationHelper.chatTarget(shadowOf(remaining.contentIntent).savedIntent))
    }

    @Test fun anotherChatRequestsSoundWithAnUnreadNotificationStillInThePanel() {
        NotificationHelper.showChat(app, first, "Unread first chat", 1)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        val second = first.copy(sessionName = "second chat")
        NotificationHelper.showChat(app, second, "New second chat reply", 1)
        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        val newNotification = notifications.single { NotificationHelper.chatTarget(shadowOf(it.contentIntent).savedIntent) == second }
        assertEquals(Notification.GROUP_ALERT_ALL, newNotification.groupAlertBehavior)
        assertEquals(0, newNotification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(NotificationHelper.CHAT_CHANNEL_ID, newNotification.channelId)
        assertNotNull(manager.getNotificationChannel(newNotification.channelId).sound)
    }

    @Test fun aNewReplyCanRealertAnExistingNotificationForTheSameChat() {
        NotificationHelper.showChat(app, first, "Earlier unread reply", 1)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        NotificationHelper.showChat(app, first, "Another new reply", 2)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.GROUP_ALERT_ALL, notification.groupAlertBehavior)
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun malformedAndIncompleteNotificationDestinationsAreRejected() {
        assertNull(NotificationHelper.chatTarget(Intent().putExtra(NotificationHelper.CHAT_TARGET_EXTRA, "invalid")))
        assertNull(NotificationHelper.chatTarget(NotificationHelper.chatIntent(app, first.copy(hostId = ""))))
    }
}
