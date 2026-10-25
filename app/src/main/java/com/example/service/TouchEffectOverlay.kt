package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** ON/OFF switch for the on-screen touch effect (saved). The AI can flip it by voice via `app_control`. */
object TouchEffectBus {
    private const val PREFS = "omni_touch_fx"
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load(ctx: Context) {
        _enabled.value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("on", true)
    }

    fun set(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("on", on).apply()
        _enabled.value = on
        if (!on) TouchEffectOverlay.hide()
    }
}

/**
 * Draws a glowing ripple at every tap and a trail for every swipe the agent performs, on top of ANY app.
 * Uses an accessibility overlay window (no extra permission) and never intercepts touches.
 */
object TouchEffectOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: RippleView? = null
    private var wm: WindowManager? = null

    fun tap(x: Float, y: Float) = post { ensure()?.add(Fx(x, y, x, y, false)) }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = post { ensure()?.add(Fx(x1, y1, x2, y2, true)) }

    private fun post(r: () -> Unit) {
        if (!TouchEffectBus.enabled.value) return
        main.post { try { r() } catch (_: Exception) {} }
    }

    private fun ensure(): RippleView? {
        view?.let { return it }
        val svc = AccessibilityAutomationBus.serviceInstance ?: return null
        val manager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = RippleView(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        manager.addView(v, lp)
        wm = manager
        view = v
        return v
    }

    fun hide() {
        main.post {
            try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
            view = null
        }
    }
}

private class Fx(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val swipe: Boolean) {
    val start: Long = SystemClock.uptimeMillis()
    val dur: Long = if (swipe) 750L else 650L
}

private class RippleView(ctx: Context) : View(ctx) {
    private val fxs = CopyOnWriteArrayList<Fx>()
    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.parseColor("#00FF9C")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#00E5FF") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00E5FF")
    }

    fun add(fx: Fx) { fxs.add(fx); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (fx in fxs) {
            val t = (now - fx.start).toFloat() / fx.dur
            if (t >= 1f) { fxs.remove(fx); continue }
            val fade = 1f - t
            if (!fx.swipe) {
                ring.alpha = (230 * fade).toInt()
                c.drawCircle(fx.x1, fx.y1, (14f + 46f * t) * d, ring)
                fill.alpha = (150 * fade).toInt()
                c.drawCircle(fx.x1, fx.y1, 11f * d * (1f - 0.4f * t), fill)
            } else {
                val p = (1f - (1f - t.coerceAtMost(0.6f) / 0.6f) * (1f - t.coerceAtMost(0.6f) / 0.6f))
                val cx = fx.x1 + (fx.x2 - fx.x1) * p
                val cy = fx.y1 + (fx.y2 - fx.y1) * p
                line.alpha = (200 * fade).toInt()
                c.drawLine(fx.x1, fx.y1, cx, cy, line)
                fill.alpha = (230 * fade).toInt()
                c.drawCircle(cx, cy, 13f * d, fill)
                ring.alpha = (200 * fade).toInt()
                c.drawCircle(cx, cy, 24f * d, ring)
            }
        }
        if (fxs.isNotEmpty()) postInvalidateOnAnimation()
    }
}
