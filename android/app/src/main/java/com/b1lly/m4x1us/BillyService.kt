package com.b1lly.m4x1us

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** The blue stickman: lives as an overlay on top of every app, wanders, listens, talks and acts. */
class BillyService : Service() {

    companion object {
        @Volatile var instance: BillyService? = null
        const val ACT_STOP_TASK = "com.b1lly.m4x1us.STOP_TASK"
        const val ACT_TALK = "com.b1lly.m4x1us.TALK"
        const val ACT_QUIT = "com.b1lly.m4x1us.QUIT"
        private const val CH = "billy"
    }

    private enum class Mode { IDLE, WALK, TWEEN, EMOTE, DRAG }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var stick: StickmanView
    private lateinit var stickLp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var panel: LinearLayout? = null
    private lateinit var transcriptView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var moodView: TextView
    private val brain by lazy { Brain(this) }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    // conversation state
    private var busy = false
    private var working = false
    private var gotToken = false
    private var listening = false
    private var speaking = false
    private val reply = StringBuilder()
    private val ttsBuf = StringBuilder()
    private var transcriptDone = ""
    private var bubbleHide: Runnable? = null
    private var bw = 0
    private var bh = 0

    // movement state
    private var px = 0f
    private var py = 0f
    private var mode = Mode.IDLE
    private var modeTimer = 2f
    private var walkTarget = 0f
    private var walkDir = 1
    private var emotePose = StickmanView.Pose.IDLE
    private var twFromX = 0f; private var twFromY = 0f; private var twToX = 0f; private var twToY = 0f
    private var twDur = 0.6f; private var twT = 0f; private var twArc = 0f
    private var surfMinX = 0f; private var surfMaxX = 0f; private var surfY = 0f
    private var onPlatform: Rect? = null
    @Volatile private var platforms: List<Rect> = emptyList()
    private var platformTimer = 5f
    private var onPlatformTime = 0f
    private var moodTimer = 0f
    private var lastFrame = 0L
    private var dragging = false
    private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val sw get() = wm.currentWindowMetrics.bounds.width()
    private val sh get() = wm.currentWindowMetrics.bounds.height()
    private val bottomInset: Int get() {
        val i = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars()).bottom
        return if (i > 0) i else dp(24f).toInt()
    }
    private fun floorY() = (sh - bottomInset - stick.viewH + dp(4f))

    // ------------------------------------------------------------------ lifecycle
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        buildStickman()
        buildBubble()
        initTts()
        refreshMood()
        Choreographer.getInstance().postFrameCallback(frame)
        ActivityLog.add(this, "B1LLY started")
        showBubble("Hey! I'm B1LLY.", 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP_TASK -> stopAll()
            ACT_TALK -> main.post { startListening() }
            ACT_QUIT -> { stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Choreographer.getInstance().removeFrameCallback(frame)
        try { brain.cancel() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        hidePanel()
        for (v in listOfNotNull(if (::stick.isInitialized) stick else null, if (::bubble.isInitialized) bubble else null))
            try { wm.removeView(v) } catch (_: Exception) {}
        ActivityLog.add(this, "B1LLY stopped")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "B1LLY", NotificationManager.IMPORTANCE_LOW))
        fun act(a: String, req: Int) = PendingIntent.getService(this, req,
            Intent(this, BillyService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("B1LLY M4X1US is awake")
            .setContentText("Tap to open. Use Stop to halt whatever I'm doing.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop task", act(ACT_STOP_TASK, 1)).build())
            .addAction(Notification.Action.Builder(null, "Talk", act(ACT_TALK, 2)).build())
            .addAction(Notification.Action.Builder(null, "Quit", act(ACT_QUIT, 3)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ------------------------------------------------------------------ windows
    private fun overlayLp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildStickman() {
        stick = StickmanView(this)
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        stickLp = overlayLp(stick.viewW.toInt(), stick.viewH.toInt(), f)
        px = dp(40f); py = floorY(); surfY = py; surfMinX = 0f; surfMaxX = sw - stick.viewW
        stickLp.x = px.toInt(); stickLp.y = py.toInt()
        wm.addView(stick, stickLp)
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        stick.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = stickLp.x; startY = stickLp.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; mode = Mode.DRAG; onPlatform = null }
                    if (dragging) { px = startX + dx; py = startY + dy; applyPos() }
                    true
                }
                MotionEvent.ACTION_UP -> { if (dragging) { dragging = false; fallToFloor() } else onTapped(); true }
                else -> true
            }
        }
    }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            setTextColor(0xFFE6EDF7.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            maxLines = 8; visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0xEE121826.toInt()); cornerRadius = dp(14f); setStroke(dp(1.5f).toInt(), 0xFF2F9BFF.toInt())
            }
        }
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        bubbleLp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, f)
        wm.addView(bubble, bubbleLp)
    }

    private fun applyPos() {
        stickLp.x = px.toInt(); stickLp.y = py.toInt()
        try { wm.updateViewLayout(stick, stickLp) } catch (_: Exception) {}
        positionBubble()
    }

    private fun positionBubble() {
        if (bubble.visibility != View.VISIBLE) return
        var y = py - bh - dp(4f)
        if (y < dp(30f)) y = py + stick.viewH + dp(2f)
        val x = (px + stick.viewW / 2 - bw / 2).coerceIn(dp(8f), (sw - bw - dp(8f)).coerceAtLeast(dp(8f)))
        bubbleLp.x = x.toInt(); bubbleLp.y = y.toInt()
        try { wm.updateViewLayout(bubble, bubbleLp) } catch (_: Exception) {}
    }

    private fun showBubble(text: String, hideAfterMs: Long = 0) {
        main.post {
            if (!::bubble.isInitialized) return@post
            bubbleHide?.let { main.removeCallbacks(it) }
            bubble.text = text
            bubble.visibility = View.VISIBLE
            bubble.measure(View.MeasureSpec.makeMeasureSpec((sw - dp(24f)).toInt(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED)
            bw = bubble.measuredWidth; bh = bubble.measuredHeight
            bubbleLp.width = WindowManager.LayoutParams.WRAP_CONTENT
            positionBubble()
            if (hideAfterMs > 0) {
                bubbleHide = Runnable { bubble.visibility = View.GONE; try { wm.updateViewLayout(bubble, bubbleLp) } catch (_: Exception) {} }
                main.postDelayed(bubbleHide!!, hideAfterMs)
            }
        }
    }

    /** Lets taps pass through the stickman so B1LLY can tap what is underneath him. */
    fun passThrough(on: Boolean) {
        main.post {
            if (!::stickLp.isInitialized) return@post
            stickLp.flags = if (on) stickLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else stickLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { wm.updateViewLayout(stick, stickLp) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ chat panel
    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun showPanel() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(0xF2121826.toInt()); cornerRadius = dp(18f); setStroke(dp(1.5f).toInt(), 0xFF2F9BFF.toInt())
            }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "B1LLY M4X1US"; setTextColor(0xFFE6EDF7.toInt()); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        moodView = TextView(this).apply { setTextColor(0xFF8A97AD.toInt()); text = "" }
        head.addView(moodView)
        head.addView(Button(this).apply { text = "✕"; setOnClickListener { hidePanel() } }, LinearLayout.LayoutParams(dp(48f).toInt(), dp(40f).toInt()))
        p.addView(head)
        transcriptView = TextView(this).apply { setTextColor(0xFFE6EDF7.toInt()); setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt()); text = transcriptDone }
        scroll = ScrollView(this).apply { addView(transcriptView) }
        p.addView(scroll, LinearLayout.LayoutParams(-1, dp(220f).toInt()))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message B1LLY…"; setTextColor(0xFFE6EDF7.toInt()); setHintTextColor(0xFF8A97AD.toInt())
            imeOptions = EditorInfo.IME_ACTION_SEND; maxLines = 4
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "🎤"; setOnClickListener { startListening() } }, LinearLayout.LayoutParams(dp(52f).toInt(), dp(44f).toInt()))
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { sendFromInput() } }, LinearLayout.LayoutParams(dp(72f).toInt(), dp(44f).toInt()))
        p.addView(row)

        val lp = overlayLp((sw - dp(16f)).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        lp.x = dp(8f).toInt(); lp.y = dp(44f).toInt()
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        wm.addView(p, lp)
        panel = p
        input.requestFocus()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        refreshMood()
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        ask(t, false)
    }

    private fun renderTranscript(current: String) {
        if (panel == null) return
        transcriptView.text = if (current.isEmpty()) transcriptDone else transcriptDone + "B1LLY: " + current
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ talking to the brain
    fun ask(text: String, voice: Boolean) {
        main.post {
            if (busy) { showBubble("One sec, I'm still on the last thing. Tap me to stop me.", 3000); return@post }
            busy = true; gotToken = false; working = false
            reply.setLength(0); ttsBuf.setLength(0)
            stopSpeaking()
            transcriptDone += "You: $text\n\n"
            renderTranscript("")
            showBubble("…")
            ActivityLog.add(this, "user: $text")
            brain.chat(text, voice, object : Brain.Listener {
                override fun onToken(t: String) {
                    main.post {
                        gotToken = true
                        reply.append(t)
                        val shown = reply.toString().trim()
                        showBubble(if (shown.length > 420) "…" + shown.takeLast(420) else shown)
                        renderTranscript(shown)
                        feedTts(t)
                    }
                }

                override fun onTool(s: String) { ActivityLog.add(this@BillyService, "tool: $s") }

                override fun onDevice(tool: String, args: JSONObject): String {
                    main.post { working = true }
                    try { return DeviceTools.run(applicationContext, tool, args) }
                    finally { main.post { working = false } }
                }

                override fun onConfirm(msg: String): Boolean = confirmDialog(msg)

                override fun onDone() { main.post { finishTurn() } }

                override fun onError(m: String) { main.post { busy = false; working = false; showBubble(m, 9000); ActivityLog.add(this@BillyService, "error: $m") } }
            })
        }
    }

    private fun finishTurn() {
        if (!busy) return
        busy = false; working = false
        val full = reply.toString().trim()
        transcriptDone += "B1LLY: $full\n\n"
        renderTranscript("")
        flushTts()
        if (full.isNotEmpty()) showBubble(if (full.length > 420) "…" + full.takeLast(420) else full, 7000 + full.length * 25L)
        ActivityLog.add(this, "billy: $full")
        main.postDelayed({ refreshMood() }, 6000)
    }

    /** Used for non-"full" autonomy: asks the user to approve a risky action. */
    private fun confirmDialog(msg: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        main.post {
            try {
                val dlg = android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                    .setTitle("B1LLY wants to:").setMessage(msg)
                    .setPositiveButton("Allow") { _, _ -> ok = true; latch.countDown() }
                    .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }.create()
                dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dlg.show()
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        ActivityLog.add(this, "confirm '$msg' -> $ok")
        return ok
    }

    fun stopAll() {
        main.post {
            brain.cancel()
            stopSpeaking()
            try { recognizer?.cancel() } catch (_: Exception) {}
            busy = false; working = false; listening = false
            transcriptDone += "(stopped)\n\n"; renderTranscript("")
            showBubble("Okay, stopped. Tap me when you want me.", 3000)
            ActivityLog.add(this, "STOPPED by user")
        }
    }

    private fun onTapped() {
        if (busy || speaking || listening) stopAll() else togglePanel()
    }

    // ------------------------------------------------------------------ voice
    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post { speaking = true } }
                    override fun onDone(id: String?) { main.post { if (id == "last") speaking = false } }
                    @Deprecated("deprecated") override fun onError(id: String?) { main.post { speaking = false } }
                })
            }
        }
    }

    private fun clean(s: String) = s.replace(Regex("```[\\s\\S]*?```"), " code omitted ").replace(Regex("[*_`#>]"), "").trim()

    private fun feedTts(tok: String) {
        if (!Prefs.speak(this)) return
        ttsBuf.append(tok)
        val m = Regex("[.!?\\n]\\s").find(ttsBuf)
        if (m != null && !ttsBuf.contains("```")) {
            val chunk = ttsBuf.substring(0, m.range.last + 1); ttsBuf.delete(0, m.range.last + 1)
            speakChunk(chunk, false)
        }
    }

    private fun flushTts() {
        if (!Prefs.speak(this)) { ttsBuf.setLength(0); return }
        if (ttsBuf.isNotBlank()) speakChunk(ttsBuf.toString(), true)
        ttsBuf.setLength(0)
    }

    private fun speakChunk(text: String, last: Boolean) {
        val c = clean(text)
        if (!ttsReady || c.isEmpty()) { if (last) speaking = false; return }
        speaking = true
        tts?.speak(c, TextToSpeech.QUEUE_ADD, null, if (last) "last" else "part")
    }

    private fun stopSpeaking() { try { tts?.stop() } catch (_: Exception) {}; speaking = false; ttsBuf.setLength(0) }

    private fun startListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showBubble("I need the microphone permission - open my app and allow it.", 5000); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showBubble("No speech recognizer on this phone.", 4000); return }
        stopSpeaking()
        try { recognizer?.destroy() } catch (_: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true; showBubble("Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { showBubble(it) }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onError(e: Int) {
                listening = false
                showBubble(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "I didn't catch that." else "Mic problem (code $e).", 3000)
            }
            override fun onResults(res: Bundle?) {
                listening = false
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) ask(t, true)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    // ------------------------------------------------------------------ mood
    private fun refreshMood() {
        Thread {
            try {
                val j = JSONObject(brain.get("/api/state"))
                val v = j.getJSONObject("values")
                main.post {
                    stick.valence = v.optDouble("valence", 0.2).toFloat()
                    stick.arousal = v.optDouble("arousal", 0.3).toFloat()
                    if (panel != null) moodView.text = j.optString("mood") + "  "
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ------------------------------------------------------------------ avatar behaviour
    fun emote(action: String) {
        main.post {
            when (action) {
                "hop", "jump" -> hopInPlace()
                "dance" -> startEmote(StickmanView.Pose.DANCE, 4f)
                "sleep" -> startEmote(StickmanView.Pose.SLEEP, 12f)
                "sit" -> startEmote(StickmanView.Pose.SIT, 8f)
                else -> startEmote(StickmanView.Pose.WAVE, 2.5f)
            }
        }
    }

    private fun startEmote(p: StickmanView.Pose, secs: Float) { mode = Mode.EMOTE; emotePose = p; modeTimer = secs }

    private fun tween(tx: Float, ty: Float, dur: Float, arc: Float) {
        twFromX = px; twFromY = py; twToX = tx; twToY = ty; twDur = dur; twArc = arc; twT = 0f; mode = Mode.TWEEN
    }

    private fun hopInPlace() = tween(px, py, 0.55f, dp(46f))

    private fun fallToFloor() {
        onPlatform = null
        surfMinX = 0f; surfMaxX = sw - stick.viewW; surfY = floorY()
        val tx = px.coerceIn(surfMinX, surfMaxX)
        if (py < surfY - 3f) tween(tx, surfY, 0.35f + (surfY - py) / sh * 0.5f, 0f) else { py = surfY; mode = Mode.IDLE; modeTimer = 1.5f }
    }

    private fun chooseNext() {
        if (!Prefs.wander(this)) { modeTimer = 3f; return }
        val r = Random.nextFloat()
        val plats = platforms
        when {
            onPlatform != null && onPlatformTime > 14f -> fallToFloor()
            r < 0.42f -> {
                val range = surfMaxX - surfMinX
                if (range > dp(60f)) { walkTarget = surfMinX + Random.nextFloat() * range; walkDir = if (walkTarget > px) 1 else -1; mode = Mode.WALK }
                else modeTimer = 2f
            }
            r < 0.55f -> hopInPlace()
            r < 0.67f -> startEmote(StickmanView.Pose.WAVE, 2.2f)
            r < 0.75f && stick.valence > 0f -> startEmote(StickmanView.Pose.DANCE, 3.5f)
            r < 0.85f -> startEmote(StickmanView.Pose.SIT, 6f + Random.nextFloat() * 6f)
            r < 0.90f && stick.arousal < 0.35f -> startEmote(StickmanView.Pose.SLEEP, 10f + Random.nextFloat() * 10f)
            plats.isNotEmpty() && onPlatform == null -> {
                val p = plats.random()
                val minX = (p.left - stick.viewW / 2).coerceAtLeast(0f)
                val maxX = (p.right - stick.viewW / 2).coerceAtMost(sw - stick.viewW)
                if (maxX > minX) {
                    val tx = minX + Random.nextFloat() * (maxX - minX)
                    val ty = p.top - stick.viewH + dp(4f)
                    if (ty > dp(60f) && abs(py - ty) < sh * 0.6f) {
                        onPlatform = p; surfMinX = minX; surfMaxX = maxX; surfY = ty; onPlatformTime = 0f
                        tween(tx, ty, 0.7f, (py - ty).coerceAtLeast(0f) + dp(50f))
                    } else modeTimer = 2f
                } else modeTimer = 2f
            }
            else -> modeTimer = 2f + Random.nextFloat() * 3f
        }
        if (mode == Mode.IDLE) modeTimer = 2f + Random.nextFloat() * 4f
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            val dt = if (lastFrame == 0L) 0.016f else ((ns - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = ns
            try { update(dt); stick.tick(dt) } catch (_: Exception) {}
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun update(dt: Float) {
        val occupied = busy || listening || speaking || panel != null
        when (mode) {
            Mode.DRAG -> {}
            Mode.TWEEN -> {
                twT += dt
                val k = (twT / twDur).coerceAtMost(1f)
                px = twFromX + (twToX - twFromX) * k
                py = twFromY + (twToY - twFromY) * k - twArc * 4f * k * (1 - k)
                if (twToX != twFromX) stick.facing = if (twToX > twFromX) 1 else -1
                applyPos()
                if (k >= 1f) { mode = Mode.IDLE; modeTimer = 1.5f }
            }
            Mode.WALK -> {
                px += walkDir * dp(110f) * dt
                stick.facing = walkDir
                if ((walkDir > 0 && px >= walkTarget) || (walkDir < 0 && px <= walkTarget) || occupied) { mode = Mode.IDLE; modeTimer = 1f + Random.nextFloat() * 3f }
                px = px.coerceIn(surfMinX, surfMaxX)
                applyPos()
            }
            Mode.EMOTE -> { modeTimer -= dt; if (modeTimer <= 0f || (occupied && emotePose != StickmanView.Pose.WAVE)) { mode = Mode.IDLE; modeTimer = 1f } }
            Mode.IDLE -> {
                if (onPlatform != null) onPlatformTime += dt
                modeTimer -= dt
                if (modeTimer <= 0f) { if (occupied) modeTimer = 1.5f else chooseNext() }
            }
        }
        // refresh platforms / mood in the background
        platformTimer -= dt
        if (platformTimer <= 0f) {
            platformTimer = 7f
            val svc = BillyAccessibilityService.instance
            if (svc != null && Prefs.wander(this) && !working) Thread {
                val p = svc.platforms()
                platforms = p
                main.post {
                    val cur = onPlatform
                    if (cur != null && mode == Mode.IDLE && p.none { abs(it.top - cur.top) < dp(20f) && it.left < cur.right && it.right > cur.left }) fallToFloor()
                }
            }.start() else platforms = emptyList()
        }
        moodTimer -= dt
        if (moodTimer <= 0f) { moodTimer = 45f; refreshMood() }

        // choose pose
        stick.talking = speaking
        stick.pose = when {
            mode == Mode.DRAG -> StickmanView.Pose.DRAG
            mode == Mode.TWEEN -> StickmanView.Pose.HOP
            listening -> StickmanView.Pose.LISTEN
            working -> StickmanView.Pose.WORK
            busy && !gotToken -> StickmanView.Pose.THINK
            mode == Mode.WALK -> StickmanView.Pose.WALK
            mode == Mode.EMOTE -> emotePose
            else -> StickmanView.Pose.IDLE
        }
    }
}
