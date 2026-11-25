package io.github.deskstride

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting shared across screens; follows the device locale. */
object Fmt {
    val locale: Locale get() = Locale.getDefault()
    fun kmh(v: Double) = String.format(locale, "%.1f", v)
    fun km(m: Int) = String.format(locale, "%.2f", m / 1000.0)
    fun int(v: Int): String = NumberFormat.getIntegerInstance(locale).format(v)
    fun duration(s: Long) =
        if (s >= 3600) String.format(locale, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(locale, "%d:%02d", s / 60, s % 60)
    fun pace(minKm: Double): String {
        if (minKm <= 0 || minKm > 99) return "—"
        val total = (minKm * 60).toInt()
        return String.format(locale, "%d:%02d", total / 60, total % 60)
    }
    fun day(ms: Long): String =
        SimpleDateFormat("EEEE d MMMM", locale).format(Date(ms)).replaceFirstChar { it.uppercase() }
    fun shortDay(ms: Long): String = SimpleDateFormat("EEE d MMM", locale).format(Date(ms))
        .replaceFirstChar { it.uppercase() }
    fun time(ms: Long): String = SimpleDateFormat("HH:mm", locale).format(Date(ms))
}

/** Click with a haptic tap (respects the system "touch feedback" setting). */
fun View.onTap(action: () -> Unit) = setOnClickListener {
    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    action()
}

/**
 * Tap = one step; holding repeats, speeding up. [onRelease] is called at the end
 * (release, cancel, or the button getting disabled while pressed).
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setRepeatListener(onStep: () -> Unit, onRelease: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    var interval = 0L
    var active = false
    fun end() { if (!active) return; active = false; isPressed = false; h.removeCallbacksAndMessages(null); onRelease() }
    val repeat = object : Runnable {
        override fun run() {
            if (!isEnabled) { end(); return }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onStep()
            interval = maxOf(60L, (interval * 0.85).toLong())
            h.postDelayed(this, interval)
        }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; v.isPressed = true
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onStep()
                interval = 180L
                h.postDelayed(repeat, 450L)
            }
            MotionEvent.ACTION_UP -> { end(); v.performClick() }
            MotionEvent.ACTION_CANCEL -> end()
        }
        true
    }
}

/** Sets up an `item_stat` card and returns the value TextView. */
fun View.bindStat(label: String, unit: String, @DrawableRes icon: Int, @ColorRes tint: Int): TextView {
    findViewById<TextView>(R.id.label).text = label
    findViewById<TextView>(R.id.unit).text = unit
    findViewById<ImageView>(R.id.icon).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tint))
    }
    return findViewById(R.id.value)
}
