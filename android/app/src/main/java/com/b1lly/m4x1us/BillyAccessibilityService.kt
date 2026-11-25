package com.b1lly.m4x1us

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** B1LLY's eyes and hands: reads the screen tree and performs taps, typing, swipes. */
class BillyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BillyAccessibilityService? = null
    }

    private class El(val node: AccessibilityNodeInfo, val rect: Rect)

    private var elements: List<El> = emptyList()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screenSize(): Pair<Int, Int> {
        val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** Numbered list of what is on screen, for the model to read. */
    fun readScreen(): String {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue  // skip our own overlay
                roots.add(r)
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return "error: cannot read the screen right now (secure screen, or nothing is shown)"

        val (sw, sh) = screenSize()
        val list = ArrayList<El>()
        val sb = StringBuilder()
        sb.append("screen ${sw}x${sh}, app: ${roots[0].packageName}\n")
        for (root in roots) walk(root, list, sw, sh)
        elements = list
        for ((i, e) in list.withIndex()) {
            val n = e.node
            val text = (n.text?.takeIf { !n.isShowingHintText } ?: "").toString().replace('\n', ' ').take(80)
            val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.take(60) ?: ""
            val hint = if (n.isShowingHintText) (n.hintText?.toString() ?: "") else ""
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("tap")
            if (n.isLongClickable) flags.add("hold")
            if (n.isEditable) flags.add("input")
            if (n.isScrollable) flags.add("scroll")
            if (n.isChecked) flags.add("checked")
            if (n.isFocused) flags.add("focused")
            sb.append("[$i] $cls")
            if (text.isNotEmpty()) sb.append(" \"$text\"")
            if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
            if (hint.isNotEmpty()) sb.append(" hint=\"$hint\"")
            if (flags.isNotEmpty()) sb.append(" {${flags.joinToString(",")}}")
            sb.append(" @(${e.rect.centerX()},${e.rect.centerY()})\n")
            if (sb.length > 3300) { sb.append("... (more elements; scroll to see more)\n"); break }
        }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<El>, sw: Int, sh: Int, depth: Int = 0) {
        if (n == null || depth > 40 || out.size > 160) return
        if (n.isVisibleToUser) {
            val r = Rect(); n.getBoundsInScreen(r)
            val inScreen = r.right > 0 && r.bottom > 0 && r.left < sw && r.top < sh && r.width() > 0 && r.height() > 0
            val hasContent = !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty() ||
                n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (inScreen && hasContent) out.add(El(n, r))
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, sw, sh, depth + 1)
    }

    /** Standing spots for the stickman: top edges of wide visible things. */
    fun platforms(): List<Rect> {
        val res = ArrayList<Rect>()
        try {
            val (sw, sh) = screenSize()
            val root = rootInActiveWindow ?: return res
            if (root.packageName?.toString() == packageName) return res
            val list = ArrayList<El>()
            walk(root, list, sw, sh)
            for (e in list) {
                val r = e.rect
                if (r.width() > sw / 4 && r.width() < sw && r.top > sh / 8 && r.top < sh * 3 / 4 && r.height() < sh / 3)
                    res.add(r)
            }
        } catch (_: Exception) {}
        return res.distinctBy { it.top / 20 }.take(14)
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n; var d = 0
        while (c != null && d < 6) { if (c.isClickable) return c; c = c.parent; d++ }
        return null
    }

    fun tapId(id: Int, long: Boolean = false): String {
        val e = elements.getOrNull(id) ?: return "error: no element with id $id (call screen_read first)"
        if (!long) {
            val c = clickableAncestor(e.node)
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped [$id]"
        }
        return if (tapXY(e.rect.centerX().toFloat(), e.rect.centerY().toFloat(), if (long) 700 else 60))
            "${if (long) "long-pressed" else "tapped"} [$id] at (${e.rect.centerX()},${e.rect.centerY()})"
        else "error: tap on [$id] failed"
    }

    fun tapXY(x: Float, y: Float, durMs: Long = 60): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(p, durMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long = 350): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(p, durMs)
    }

    private fun gesture(p: Path, durMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val gd = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durMs)).build()
        val started = dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!started) return false
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    fun typeText(text: String, replace: Boolean): String {
        val target = findEditable() ?: return "error: no text field is focused - tap a text field first"
        val cur = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
        val newText = if (replace) text else cur + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed ${text.length} chars"
        else "error: the field refused the text"
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        return elements.firstOrNull { it.node.isEditable && it.node.isFocused }?.node
            ?: elements.firstOrNull { it.node.isEditable }?.node
    }

    fun scroll(down: Boolean): String {
        val (sw, sh) = screenSize()
        val ok = if (down) swipe(sw / 2f, sh * 0.72f, sw / 2f, sh * 0.30f, 400)
        else swipe(sw / 2f, sh * 0.30f, sw / 2f, sh * 0.72f, 400)
        return if (ok) "scrolled ${if (down) "down" else "up"}" else "error: scroll failed"
    }

    fun global(action: Int): Boolean = performGlobalAction(action)
}
