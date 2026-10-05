package com.agentshell.core.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agentshell.R
import com.agentshell.MainActivity
import com.agentshell.data.model.ChatTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NotificationHelper {

    const val CHANNEL_ID = "agentshell_alerts"
    private const val CHANNEL_NAME = "Alerts"
    const val CHAT_CHANNEL_ID = "agentshell_chat_messages"
    const val CHAT_TARGET_EXTRA = "chat_target"
    private const val CHAT_NOTIFICATION_ID = 2002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "AgentShell alert notifications"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(
                NotificationChannel(CHAT_CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New unread replies in your chats"
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                    )
                }
            )
        }
    }

    fun chatIntent(context: Context, target: ChatTarget): Intent = Intent(context, MainActivity::class.java).apply {
        action = "com.agentshell.OPEN_CHAT"
        data = Uri.Builder().scheme("agentshell").authority("chat").appendPath(target.key).build()
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(CHAT_TARGET_EXTRA, Json.encodeToString(target))
    }

    fun chatTarget(intent: Intent?): ChatTarget? = intent?.getStringExtra(CHAT_TARGET_EXTRA)?.let {
        runCatching { Json.decodeFromString<ChatTarget>(it) }.getOrNull()
            ?.takeIf { target -> target.hostId.isNotBlank() && target.sessionName.isNotBlank() && target.windowIndex >= 0 }
    }

    fun showChat(context: Context, target: ChatTarget, preview: String, unreadCount: Int, silent: Boolean = false) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, chatIntent(context, target), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New message · ${target.displayName}")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(unreadCount)
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("chat:${target.key}", CHAT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android's notification permission and channel settings remain authoritative.
        }
    }

    fun cancelChat(context: Context, target: ChatTarget) {
        NotificationManagerCompat.from(context).cancel("chat:${target.key}", CHAT_NOTIFICATION_ID)
    }

    fun show(context: Context, title: String, body: String, notificationId: String) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("notification_id", notificationId)
            }

        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                notificationId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted — silently ignore
        }
    }
}
