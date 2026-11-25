package com.b1lly.m4x1us

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the B1LLY brain running in Termux (app.py) and streams the reply. */
class Brain(private val ctx: Context) {
    interface Listener {
        fun onToken(t: String)
        fun onTool(s: String)
        fun onDevice(tool: String, args: JSONObject): String  // runs on the brain thread; return result
        fun onConfirm(msg: String): Boolean
        fun onDone()
        fun onError(m: String)
    }

    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    fun chat(message: String, voice: Boolean, l: Listener) {
        cancelled = false
        Thread {
            try {
                val base = Prefs.brain(ctx)
                val c = URL("$base/api/chat").openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 0
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("message", message).put("voice", voice)
                    .put("device", true).put("autonomy", Prefs.autonomy(ctx))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val ev = JSONObject(line.substring(6))
                        when (ev.optString("t")) {
                            "token" -> l.onToken(ev.optString("v"))
                            "tool" -> l.onTool(ev.optString("v"))
                            "device" -> {
                                val id = ev.optString("id")
                                val res = try {
                                    l.onDevice(ev.optString("tool"), ev.optJSONObject("args") ?: JSONObject())
                                } catch (e: Exception) { "error: ${e.message}" }
                                post("/api/device_result", JSONObject().put("id", id).put("result", res))
                            }
                            "confirm" -> {
                                val ok = l.onConfirm(ev.optString("msg"))
                                post("/api/confirm", JSONObject().put("id", ev.optString("id")).put("ok", ok))
                            }
                            "error" -> l.onError(ev.optString("v"))
                            "done" -> { l.onDone(); return@use }
                        }
                    }
                }
                if (cancelled) l.onDone()
            } catch (e: Exception) {
                if (cancelled) l.onDone()
                else l.onError("Can't reach my brain at ${Prefs.brain(ctx)} - is Termux running B1LLY? (${e.message})")
            }
        }.start()
    }

    fun post(path: String, body: JSONObject): String = try {
        val c = URL(Prefs.brain(ctx) + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 5000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        c.inputStream.bufferedReader().readText()
    } catch (e: Exception) { "" }

    fun get(path: String): String = try {
        val c = URL(Prefs.brain(ctx) + path).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000
        c.inputStream.bufferedReader().readText()
    } catch (e: Exception) { "" }
}
