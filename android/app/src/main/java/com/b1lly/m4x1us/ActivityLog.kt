package com.b1lly.m4x1us

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything B1LLY does on the phone is written here so you can always see it. */
object ActivityLog {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private fun file(c: Context) = File(c.filesDir, "activity.log")

    @Synchronized
    fun add(c: Context, line: String) {
        try {
            val f = file(c)
            if (f.length() > 200_000) f.writeText(tail(c, 300))
            f.appendText("${fmt.format(Date())}  ${line.replace('\n', ' ').take(300)}\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(c: Context, n: Int = 100): String = try {
        val f = file(c)
        if (!f.exists()) "" else f.readLines().takeLast(n).joinToString("\n")
    } catch (_: Exception) {
        ""
    }
}
