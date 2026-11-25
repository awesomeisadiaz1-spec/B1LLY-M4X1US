package com.b1lly.m4x1us

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets B1LLY read incoming notifications. */
class BillyNotificationListener : NotificationListenerService() {
    companion object {
        private val recent = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm", Locale.US)
        @Volatile var connected = false

        @Synchronized fun snapshot(): String =
            if (recent.isEmpty()) "no recent notifications" else recent.joinToString("\n")

        @Synchronized fun push(s: String) { recent.addFirst(s); while (recent.size > 30) recent.removeLast() }
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val ex = n.notification.extras
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = ex.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        push("${fmt.format(Date(n.postTime))} ${n.packageName}: $title - ${text.take(160)}")
    }
}
