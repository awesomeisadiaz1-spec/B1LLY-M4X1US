package com.b1lly.m4x1us

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The blue stickman. Pure Canvas drawing; BillyService decides where he is and what he does. */
class StickmanView(ctx: Context) : View(ctx) {

    enum class Pose { IDLE, WALK, HOP, WAVE, DANCE, SLEEP, SIT, THINK, LISTEN, WORK, DRAG }

    var pose = Pose.IDLE
    var talking = false
    var facing = 1
    var valence = 0.2f
    var arousal = 0.3f

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private var t = 0f

    val viewW get() = dp(84f)
    val viewH get() = dp(112f)

    private val blue = 0xFF2F9BFF.toInt()
    private val navy = 0xFF06223F.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x552F9BFF; strokeWidth = dp(10f); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val headHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F9BFF; style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = navy; style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND
    }
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBFE3FF.toInt(); textSize = dp(14f); isFakeBoldText = true
    }

    fun tick(dt: Float) { t += dt; invalidate() }

    private fun seg(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, halo); c.drawLine(x1, y1, x2, y2, line)
    }

    /** limb from (x,y) with angle a (0 = straight down, PI = straight up, +x to the right). */
    private fun limb(c: Canvas, x: Float, y: Float, a: Float, len: Float): Pair<Float, Float> {
        val ex = x + sin(a) * len; val ey = y + cos(a) * len
        seg(c, x, y, ex, ey); return ex to ey
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val ground = height - dp(4f)
        val legL = dp(32f); val torso = dp(30f); val armL = dp(24f); val headR = dp(14f)

        var bob = sin(t * 2.2f) * dp(1.2f)
        var lean = 0f
        var la = 0.14f; var ra = -0.14f          // leg angles
        var lA = 0.3f + sin(t * 1.6f) * 0.05f; var rA = -0.3f - sin(t * 1.6f) * 0.05f  // arm angles
        var hipDrop = 0f
        var bentArm = 0                          // 1 = right hand at chin (think), 2 = right hand at ear (listen)

        when (pose) {
            Pose.WALK -> {
                val p = t * 10f
                la = sin(p) * 0.65f; ra = -la; lA = -la * 0.8f; rA = -ra * 0.8f
                bob = abs(sin(p)) * dp(2.5f); lean = 0.06f
            }
            Pose.HOP -> { la = 0.5f; ra = -0.5f; lA = PI.toFloat() - 0.5f; rA = -(PI.toFloat() - 0.5f); bob = 0f }
            Pose.WAVE -> { rA = -(PI.toFloat() - 0.35f + sin(t * 12f) * 0.45f) }
            Pose.DANCE -> {
                val p = t * 8f
                la = sin(p) * 0.4f; ra = -la
                lA = PI.toFloat() - 0.5f + sin(p) * 0.6f; rA = -(PI.toFloat() - 0.5f - sin(p) * 0.6f)
                bob = abs(sin(p)) * dp(5f); lean = sin(p * 0.5f) * 0.18f
            }
            Pose.SLEEP -> { lean = 0.22f; la = 0.1f; ra = -0.1f; lA = 0.15f; rA = -0.15f; bob = sin(t * 1.2f) * dp(1.5f) }
            Pose.SIT -> { la = 1.45f; ra = 1.25f; lA = 0.5f; rA = -0.5f; hipDrop = legL * 0.55f }
            Pose.THINK -> { bentArm = 1; lA = 0.2f }
            Pose.LISTEN -> { bentArm = 2; lA = 0.2f }
            Pose.WORK -> {
                lA = 0.9f + sin(t * 22f) * 0.25f; rA = -(0.9f + cos(t * 22f) * 0.25f); bob = sin(t * 14f) * dp(1f)
            }
            Pose.DRAG -> {
                la = sin(t * 9f) * 0.35f; ra = -la
                lA = PI.toFloat() - 0.3f; rA = -(PI.toFloat() - 0.3f); bob = 0f
            }
            Pose.IDLE -> {}
        }

        c.save()
        c.scale(facing.toFloat(), 1f, cx, height / 2f)
        c.rotate(Math.toDegrees(lean.toDouble()).toFloat(), cx, ground)

        val hipY = ground - legL + hipDrop + (if (pose == Pose.WALK) -bob else 0f)
        val hx = cx
        val shY = hipY - torso + bob
        val hdY = shY - headR - dp(2f)

        // legs (sitting: thighs forward, shins down)
        if (pose == Pose.SIT) {
            val kl = limb(c, hx, hipY, la, legL * 0.7f); limb(c, kl.first, kl.second, 0.1f, legL * 0.7f)
            val kr = limb(c, hx, hipY, ra + 1.3f, legL * 0.7f); limb(c, kr.first, kr.second, 0.1f, legL * 0.7f)
        } else {
            limb(c, hx, hipY, la, legL); limb(c, hx, hipY, ra, legL)
        }
        // torso
        seg(c, hx, hipY, hx, shY)
        // arms
        val sy = shY + dp(4f)
        limb(c, hx, sy, lA, armL)
        when (bentArm) {
            1 -> { val e = limb(c, hx, sy, -0.9f, armL * 0.7f); seg(c, e.first, e.second, hx - dp(3f), hdY + headR * 0.8f) }
            2 -> { val e = limb(c, hx, sy, -1.2f, armL * 0.7f); seg(c, e.first, e.second, hx - headR * 1.1f, hdY) }
            else -> limb(c, hx, sy, rA, armL)
        }

        // head
        c.drawCircle(hx, hdY, headR + dp(3f), headHalo)
        c.drawCircle(hx, hdY, headR, fill)
        face(c, hx, hdY, headR)
        c.restore()

        // floating extras (not mirrored)
        if (pose == Pose.SLEEP) {
            val k = (t * 0.8f) % 1f
            text.alpha = (255 * (1 - k)).toInt()
            c.drawText("z", cx + dp(10f) + k * dp(8f), dp(22f) - k * dp(14f), text)
            c.drawText("Z", cx + dp(20f) + k * dp(6f), dp(12f) - k * dp(8f), text)
            text.alpha = 255
        }
        if (pose == Pose.THINK) {
            val n = 1 + ((t * 2f).toInt() % 3)
            c.drawText(".".repeat(n), cx + dp(14f), hdY - headR - dp(2f), text)
        }
        if (pose == Pose.LISTEN) {
            val k = (t * 2.2f) % 1f
            ink.alpha = (200 * (1 - k)).toInt(); val oldC = ink.color; ink.color = blue
            val rad = headR * (1.4f + k)
            c.drawArc(cx - rad - headR * facing, hdY - rad, cx + rad - headR * facing, hdY + rad, 150f, 60f, false, ink)
            ink.color = oldC; ink.alpha = 255
        }
    }

    private fun face(c: Canvas, x: Float, y: Float, r: Float) {
        val dir = facing.toFloat()
        val ex = r * 0.42f; val ey = y - r * 0.15f
        val asleep = pose == Pose.SLEEP
        val blink = (t % 3.4f) < 0.12f
        val wide = pose == Pose.LISTEN || arousal > 0.75f
        val angry = valence < -0.4f && arousal > 0.45f
        for (s in intArrayOf(-1, 1)) {
            val px = x + s * ex + dir * r * 0.08f
            if (asleep || blink) {
                c.drawLine(px - dp(2.5f), ey, px + dp(2.5f), ey, ink)
            } else {
                c.drawCircle(px, ey, dp(if (wide) 3.2f else 2.4f), inkFill)
            }
            if (angry) c.drawLine(px - s * dp(4f), ey - dp(6f), px + s * dp(4f), ey - dp(3f), ink)
        }
        val my = y + r * 0.45f
        if (talking) {
            val open = dp(1.5f) + abs(sin(t * 14f)) * dp(3.5f)
            c.drawOval(x - dp(3.5f) + dir * dp(1f), my - open / 2, x + dp(3.5f) + dir * dp(1f), my + open / 2, inkFill)
        } else if (asleep) {
            c.drawCircle(x, my, dp(1.5f), inkFill)
        } else {
            val curve = max(-1f, min(1f, valence * 1.6f)) * dp(4f)
            val p = Path().apply { moveTo(x - dp(4f), my); quadTo(x, my + curve + dp(1f), x + dp(4f), my) }
            c.drawPath(p, ink)
        }
    }
}
