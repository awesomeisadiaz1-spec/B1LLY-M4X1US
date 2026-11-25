package com.b1lly.m4x1us

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Executes the "device" tools the brain asks for. Runs on the brain's worker thread. */
object DeviceTools {

    private fun launchable(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        var pkg = name
        if (pm.getLaunchIntentForPackage(pkg) == null) {
            val q = name.lowercase()
            val apps = launchable(ctx)
            val hit = apps.firstOrNull { it.first.lowercase() == q }
                ?: apps.firstOrNull { it.first.lowercase().contains(q) }
                ?: return "error: no app matching \"$name\" (try list_apps)"
            pkg = hit.second
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "error: cannot launch $pkg"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        Thread.sleep(700)
        return "opened $pkg"
    }

    fun run(ctx: Context, tool: String, a: JSONObject): String {
        val svc = BillyAccessibilityService.instance
        val needsA11y = tool !in setOf("open_app", "list_apps", "read_notifications", "avatar", "wait")
        if (needsA11y && svc == null)
            return "error: my Accessibility service is off. Ask the user to enable it in B1LLY settings."
        val bs = BillyService.instance
        val touches = tool in setOf("tap", "long_press", "swipe", "scroll")
        if (touches) bs?.passThrough(true)
        try {
            val result = when (tool) {
                "screen_read" -> svc!!.readScreen()
                "tap" -> if (a.has("id")) svc!!.tapId(a.getInt("id"))
                else if (svc!!.tapXY(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())) "tapped" else "error: tap failed"
                "long_press" -> if (a.has("id")) svc!!.tapId(a.getInt("id"), true)
                else if (svc!!.tapXY(a.optDouble("x").toFloat(), a.optDouble("y").toFloat(), 700)) "long-pressed" else "error: failed"
                "type" -> svc!!.typeText(a.optString("text"), a.optBoolean("replace", false))
                "swipe" -> if (svc!!.swipe(a.optDouble("x1").toFloat(), a.optDouble("y1").toFloat(),
                        a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat())) "swiped" else "error: swipe failed"
                "scroll" -> svc!!.scroll(a.optString("dir", "down") != "up")
                "back" -> if (svc!!.global(AccessibilityService.GLOBAL_ACTION_BACK)) "back" else "error"
                "home" -> if (svc!!.global(AccessibilityService.GLOBAL_ACTION_HOME)) "home" else "error"
                "recents" -> if (svc!!.global(AccessibilityService.GLOBAL_ACTION_RECENTS)) "recents" else "error"
                "notifications_panel" -> if (svc!!.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) "opened notifications" else "error"
                "quick_settings" -> if (svc!!.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) "opened quick settings" else "error"
                "open_app" -> openApp(ctx, a.optString("name", a.optString("package")))
                "list_apps" -> launchable(ctx).take(120).joinToString("\n") { "${it.first} (${it.second})" }
                "read_notifications" ->
                    if (BillyNotificationListener.connected) BillyNotificationListener.snapshot()
                    else "error: notification access is off. Ask the user to enable it in B1LLY settings."
                "wait" -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(100, 8000)); "waited" }
                "avatar" -> { bs?.emote(a.optString("action", "wave")); "ok" }
                else -> "error: unknown device tool $tool"
            }
            ActivityLog.add(ctx, "$tool ${a.toString().take(80)} -> ${result.take(80)}")
            if (touches || tool == "back" || tool == "home" || tool == "type") Thread.sleep(350)
            return result
        } finally {
            if (touches) bs?.passThrough(false)
        }
    }
}
