package com.goldfinger.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole game is drawn and driven here.
 *
 * Rules (the classic "last finger" party game):
 *  1. At least two players each place a finger on the screen.
 *  2. After a short settle the round arms and an alarm is scheduled at a
 *     RANDOM time (3-20 s). Tense music plays while everyone waits.
 *  3. Lifting a finger BEFORE the alarm = false start, that player loses.
 *  4. When the alarm sounds everyone lifts as fast as they can.
 *  5. Whoever is LAST to lift is the Goldfinger and loses.
 */
class GameView(context: Context) : View(context) {

    private enum class State { WAITING, ARMED, ALARM, RESULT }
    private enum class Reason { LAST, FALSE_START }

    private class Finger(var x: Float, var y: Float, val color: Int, val number: Int)

    private val fingers = LinkedHashMap<Int, Finger>()
    private val random = Random()
    private val density = resources.displayMetrics.density
    private val siren = SirenPlayer()
    private val music = MusicPlayer()

    private var state = State.WAITING
    private var settleUntil = 0L
    private var alarmAt = 0L
    private var alarmStart = 0L

    private var loser: Finger? = null
    private var loserReason = Reason.LAST

    // --- demo/screenshot support (driven via launch intent extra) ---
    private var frozen = false
    private var demoCount = 0
    private var demoLoserIndex = -1

    private val gold = 0xFFF5C542.toInt()
    private val goldDeep = 0xFFE8B923.toInt()

    private val palette = intArrayOf(
        0xFFFF5252.toInt(), 0xFF448AFF.toInt(), 0xFF69F0AE.toInt(),
        0xFFFFD740.toInt(), 0xFFE040FB.toInt(), 0xFFFFAB40.toInt(),
        0xFF18FFFF.toInt(), 0xFFFF4081.toInt(), 0xFF64FFDA.toInt(),
        0xFF7C4DFF.toInt()
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF8A93A6.toInt()
        letterSpacing = 0.18f
    }
    private val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = gold
        isFakeBoldText = true
        letterSpacing = 0.22f
    }

    init {
        keepScreenOn = true
    }

    private fun dp(v: Float) = v * density

    companion object {
        private const val SETTLE_MS = 1500L
        private const val ALARM_MIN = 3000
        private const val ALARM_MAX = 20000
        private val DEMO_POS = arrayOf(
            0.28f to 0.46f, 0.72f to 0.43f, 0.34f to 0.70f,
            0.69f to 0.71f, 0.50f to 0.57f
        )
    }

    /** Force a fixed visual state for screenshots (no audio, no transitions). */
    fun applyDemo(name: String) {
        frozen = true
        when (name.lowercase()) {
            "title" -> { state = State.WAITING; demoCount = 0 }
            "waiting" -> { state = State.WAITING; demoCount = 3 }
            "armed" -> { state = State.ARMED; demoCount = 4 }
            "alarm" -> { state = State.ALARM; demoCount = 3 }
            "busted" -> { state = State.RESULT; loserReason = Reason.LAST; demoCount = 3; demoLoserIndex = 2 }
            "early" -> { state = State.RESULT; loserReason = Reason.FALSE_START; demoCount = 3; demoLoserIndex = 1 }
            else -> { state = State.WAITING; demoCount = 0 }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!frozen) return
        fingers.clear()
        for (i in 0 until min(demoCount, DEMO_POS.size)) {
            val (fx, fy) = DEMO_POS[i]
            fingers[i] = Finger(fx * w, fy * h, palette[i % palette.size], i + 1)
        }
        loser = if (demoLoserIndex >= 0) fingers[demoLoserIndex] else null
    }

    // ---------------------------------------------------------------- Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frozen) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                addFinger(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    fingers[event.getPointerId(i)]?.let {
                        it.x = event.getX(i)
                        it.y = event.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                removeFinger(event.getPointerId(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                fingers.clear()
                siren.stop(); music.stop()
                state = State.WAITING
                settleUntil = 0
            }
        }
        invalidate()
        return true
    }

    private fun addFinger(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (state == State.RESULT) resetToWaiting()
        val number = nextPlayerNumber()
        fingers[id] = Finger(x, y, palette[(number - 1) % palette.size], number)
        if (state == State.WAITING && fingers.size >= 2) settleUntil = now + SETTLE_MS
    }

    /** Smallest player number (1, 2, 3 ...) not currently in use by an active finger. */
    private fun nextPlayerNumber(): Int {
        val used = fingers.values.mapTo(HashSet()) { it.number }
        var n = 1
        while (n in used) n++
        return n
    }

    private fun removeFinger(id: Int) {
        val f = fingers.remove(id) ?: return
        when (state) {
            State.WAITING -> if (fingers.size < 2) settleUntil = 0
            State.ARMED -> declareLoser(f, Reason.FALSE_START)
            State.ALARM -> when (fingers.size) {
                1 -> declareLoser(fingers.values.first(), Reason.LAST)
                0 -> declareLoser(f, Reason.LAST)
            }
            State.RESULT -> {}
        }
    }

    // ------------------------------------------------------------- State

    private fun resetToWaiting() {
        state = State.WAITING
        loser = null
        settleUntil = 0
        siren.stop(); music.stop()
    }

    private fun arm(now: Long) {
        state = State.ARMED
        alarmAt = now + ALARM_MIN + random.nextInt(ALARM_MAX - ALARM_MIN)
        music.start()
    }

    private fun triggerAlarm(now: Long) {
        state = State.ALARM
        alarmStart = now
        music.stop()
        siren.start()
        vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
    }

    private fun declareLoser(f: Finger, reason: Reason) {
        state = State.RESULT
        loser = f
        loserReason = reason
        music.stop(); siren.stop()
        vibrate(longArrayOf(0, 250))
    }

    private fun vibrate(pattern: LongArray) {
        if (frozen) return
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1)
        }
    }

    // ------------------------------------------------- Game loop / drawing

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()

        if (!frozen) {
            when (state) {
                State.WAITING ->
                    if (fingers.size >= 2 && settleUntil > 0 && now >= settleUntil) arm(now)
                State.ARMED ->
                    if (now >= alarmAt) triggerAlarm(now)
                else -> {}
            }
        }

        drawBackground(canvas, now)
        drawFingers(canvas, now)
        drawOverlay(canvas, now)

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (state == State.ALARM) {
            val k = ((sin(now * 0.025) + 1.0) / 2.0).toFloat()
            fill.shader = null
            fill.color = Color.rgb((70 + k * 165).toInt(), (8 + k * 16).toInt(), (10 + k * 14).toInt())
            canvas.drawRect(0f, 0f, w, h, fill)
        } else {
            fill.shader = LinearGradient(
                0f, 0f, 0f, h,
                0xFF0E1116.toInt(), 0xFF05070A.toInt(), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            // soft gold glow behind the centre
            val glow = RadialGradient(
                w / 2f, h * 0.34f, h * 0.45f,
                intArrayOf(0x22F5C542, 0x00000000), null, Shader.TileMode.CLAMP
            )
            fill.shader = glow
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
    }

    private fun drawFingers(canvas: Canvas, now: Long) {
        val pulse = ((sin(now * 0.006) + 1.0) / 2.0).toFloat()
        val base = dp(52f)
        for (f in fingers.values) {
            val r = base + pulse * dp(9f)
            // outer glow
            fill.shader = RadialGradient(
                f.x, f.y, r * 1.9f,
                intArrayOf((f.color and 0x00FFFFFF) or 0x55000000, f.color and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(f.x, f.y, r * 1.9f, fill)
            fill.shader = null
            // disc
            fill.color = (f.color and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(f.x, f.y, r, fill)
            // ring
            ring.color = f.color
            ring.strokeWidth = dp(5f)
            canvas.drawCircle(f.x, f.y, r, ring)
            // player number
            text.color = Color.WHITE
            text.textSize = dp(22f)
            canvas.drawText(f.number.toString(), f.x, f.y + dp(8f), text)
        }
    }

    private fun drawEmblem(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        ring.color = gold
        ring.strokeWidth = dp(4f) * scale
        canvas.drawCircle(cx, cy, dp(34f) * scale, ring)
        ring.color = goldDeep
        ring.strokeWidth = dp(3f) * scale
        canvas.drawCircle(cx, cy, dp(22f) * scale, ring)
        fill.color = gold
        canvas.drawCircle(cx, cy, dp(8f) * scale, fill)
    }

    private fun drawOverlay(canvas: Canvas, now: Long) {
        val cx = width / 2f
        when (state) {
            State.WAITING -> drawWaiting(canvas, now, cx)
            State.ARMED -> {
                wordmark.textSize = dp(20f)
                wordmark.color = gold
                canvas.drawText("HOLD STILL", cx, dp(96f), wordmark)
                subText.textSize = dp(15f)
                canvas.drawText("DON'T LIFT TOO EARLY", cx, dp(124f), subText)
            }
            State.ALARM -> {
                val s = (1.0 + 0.10 * sin(now * 0.03)).toFloat()
                text.color = Color.WHITE
                text.textSize = dp(68f) * s
                canvas.drawText("RELEASE!", cx, height * 0.52f, text)
                subText.color = Color.WHITE
                subText.textSize = dp(20f)
                canvas.drawText("LAST FINGER LOSES", cx, height * 0.52f + dp(46f), subText)
                subText.color = 0xFF8A93A6.toInt()
            }
            State.RESULT -> drawResult(canvas, now, cx)
        }
    }

    private fun drawWaiting(canvas: Canvas, now: Long, cx: Float) {
        if (fingers.isEmpty()) {
            // hero / title screen
            val cy = height * 0.34f
            drawEmblem(canvas, cx, cy, 1.4f)
            wordmark.textSize = dp(40f)
            canvas.drawText("GOLDFINGER", cx, cy + dp(96f), wordmark)
            subText.textSize = dp(15f)
            canvas.drawText("LAST FINGER LOSES", cx, cy + dp(124f), subText)
            val blink = (0.4f + 0.6f * ((sin(now * 0.004) + 1.0) / 2.0)).toFloat()
            text.color = Color.argb((blink * 255).toInt(), 255, 255, 255)
            text.textSize = dp(22f)
            canvas.drawText("Place a finger to begin", cx, height * 0.78f, text)
        } else if (fingers.size < 2) {
            text.color = Color.WHITE
            text.textSize = dp(26f)
            canvas.drawText("Need at least 2 players", cx, height * 0.85f, text)
        } else {
            wordmark.textSize = dp(22f)
            canvas.drawText("ROUND STARTING", cx, height * 0.85f, wordmark)
            subText.textSize = dp(15f)
            canvas.drawText("HOLD STILL", cx, height * 0.85f + dp(28f), subText)
        }
    }

    private fun drawResult(canvas: Canvas, now: Long, cx: Float) {
        fill.shader = null
        fill.color = 0xAA05070A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        loser?.let { l ->
            val pulse = ((sin(now * 0.008) + 1.0) / 2.0).toFloat()
            val r = dp(78f) + pulse * dp(22f)
            fill.shader = RadialGradient(
                l.x, l.y, r * 1.6f,
                intArrayOf((l.color and 0x00FFFFFF) or 0x66000000, l.color and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(l.x, l.y, r * 1.6f, fill)
            fill.shader = null
            ring.color = l.color
            ring.strokeWidth = dp(7f)
            canvas.drawCircle(l.x, l.y, r, ring)
            text.color = Color.WHITE
            text.textSize = dp(26f)
            canvas.drawText(l.number.toString(), l.x, l.y + dp(9f), text)
        }

        wordmark.color = gold
        if (loserReason == Reason.FALSE_START) {
            wordmark.textSize = dp(48f)
            canvas.drawText("TOO SOON!", cx, height * 0.30f, wordmark)
            text.color = Color.WHITE
            text.textSize = dp(22f)
            canvas.drawText("You lifted before the alarm", cx, height * 0.30f + dp(40f), text)
        } else {
            wordmark.textSize = dp(48f)
            canvas.drawText("BUSTED!", cx, height * 0.30f, wordmark)
            text.color = Color.WHITE
            text.textSize = dp(22f)
            loser?.let {
                canvas.drawText("Player ${it.number} was last — you lose", cx, height * 0.30f + dp(40f), text)
            }
        }
        subText.textSize = dp(16f)
        canvas.drawText("TAP ANYWHERE TO PLAY AGAIN", cx, height * 0.9f, subText)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        siren.stop(); music.stop()
    }
}
