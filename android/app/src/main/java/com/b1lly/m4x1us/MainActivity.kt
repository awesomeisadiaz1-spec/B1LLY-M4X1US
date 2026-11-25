package com.b1lly.m4x1us

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/** Setup screen: permissions checklist, brain address, autonomy, start/stop, activity log. */
class MainActivity : Activity() {

    private val bg = 0xFF0B0F17.toInt()
    private val panel = 0xFF121826.toInt()
    private val dim = 0xFF8A97AD.toInt()
    private val blue = 0xFF2F9BFF.toInt()
    private lateinit var root: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var brainStatus: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(28), dp(16), dp(28)) }
        sv.addView(root); setContentView(sv)

        root.addView(tv("B1LLY M4X1US", 24f, Color.WHITE, true))
        root.addView(tv("Your private AI. Lives on your phone as a blue stickman.", 14f, dim))

        root.addView(section("1. Give me permissions"))
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusBox)

        root.addView(section("2. My brain (Termux)"))
        val url = EditText(this).apply {
            setText(Prefs.brain(context)); setTextColor(Color.WHITE); setSingleLine()
            setOnFocusChangeListener { _, f -> if (!f) Prefs.setBrain(context, text.toString()) }
        }
        root.addView(url)
        brainStatus = tv("", 14f, dim)
        root.addView(brainStatus)
        root.addView(btn("Test connection") { Prefs.setBrain(this, url.text.toString()); testBrain() })
        root.addView(tv("In Termux run: bash start.sh   (keep it running)", 13f, dim))

        root.addView(section("3. How free should I be?"))
        val rg = RadioGroup(this)
        fun rb(label: String, v: String) = RadioButton(this).apply {
            text = label; setTextColor(Color.WHITE); isChecked = Prefs.autonomy(context) == v
            setOnClickListener { Prefs.setAutonomy(context, v) }
        }
        rg.addView(rb("Full autonomy - I act on my own (default)", "full"))
        rg.addView(rb("Ask before risky things (SMS, calls, shell)", "risky"))
        rg.addView(rb("Ask before every sensitive action", "ask"))
        root.addView(rg)
        root.addView(sw("Speak replies out loud", Prefs.speak(this)) { _, v -> Prefs.setSpeak(this, v) })
        root.addView(sw("Wander around the screen", Prefs.wander(this)) { _, v -> Prefs.setWander(this, v) })

        startBtn = btn("") { toggleService() }
        root.addView(section("4. Wake me up"))
        root.addView(startBtn)
        root.addView(tv("Tap the stickman to chat or to stop him mid-task. The notification has Stop / Talk / Quit.", 13f, dim))

        root.addView(section("Activity log (everything I do)"))
        logView = tv("", 12f, dim).apply { typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(logView)
        root.addView(btn("Refresh log") { refreshAll() })
    }

    override fun onResume() { super.onResume(); refreshAll() }

    private fun refreshAll() {
        statusBox.removeAllViews()
        row("Draw over other apps (my body)", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row("Accessibility (read the screen, tap, type)", accessibilityOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusBox.addView(tv("If Accessibility is greyed out: App info > ⋮ > Allow restricted settings, then try again.", 12f, dim))
        statusBox.addView(btn("Open App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        row("Notification access (read notifications)", notifListenerOn()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row("Microphone (voice)", has(Manifest.permission.RECORD_AUDIO)) { ask(Manifest.permission.RECORD_AUDIO) }
        if (Build.VERSION.SDK_INT >= 33)
            row("Show my status notification", has(Manifest.permission.POST_NOTIFICATIONS)) { ask(Manifest.permission.POST_NOTIFICATIONS) }
        startBtn.text = if (BillyService.instance != null) "Put B1LLY to sleep" else "Start B1LLY"
        logView.text = ActivityLog.tail(this, 40).ifEmpty { "(nothing yet)" }
        testBrain()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun ask(p: String) = requestPermissions(arrayOf(p), 1)
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { refreshAll() }

    private fun accessibilityOn(): Boolean {
        if (BillyAccessibilityService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun notifListenerOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun toggleService() {
        if (BillyService.instance != null) { stopService(Intent(this, BillyService::class.java)); startBtn.postDelayed({ refreshAll() }, 400); return }
        if (!Settings.canDrawOverlays(this)) { statusBoxToast("Allow 'Draw over other apps' first."); return }
        startForegroundService(Intent(this, BillyService::class.java))
        startBtn.postDelayed({ refreshAll() }, 600)
    }

    private fun statusBoxToast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    private fun testBrain() {
        brainStatus.text = "Checking…"
        Thread {
            val r = Brain(this).get("/api/state")
            val txt = try { val j = JSONObject(r); "Connected. Feeling ${j.optString("mood")}, ${j.optInt("memories")} memories." }
            catch (_: Exception) { "Not connected. Start B1LLY in Termux first (bash start.sh)." }
            runOnUiThread { brainStatus.text = txt }
        }.start()
    }

    // ---- tiny UI helpers
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun section(t: String) = tv(t, 16f, blue, true).apply { setPadding(0, dp(22), 0, dp(6)) }
    private fun btn(t: String, click: () -> Unit) = Button(this).apply { text = t; setOnClickListener { click() } }
    private fun sw(t: String, on: Boolean, l: (CompoundButton, Boolean) -> Unit) = Switch(this).apply {
        text = t; setTextColor(Color.WHITE); isChecked = on; setOnCheckedChangeListener(l)
    }
    private fun row(label: String, ok: Boolean, fix: () -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tv(if (ok) "✅" else "⬜", 18f, Color.WHITE))
        r.addView(tv(" $label", 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) r.addView(btn("Allow") { fix() })
        statusBox.addView(r)
    }
}
