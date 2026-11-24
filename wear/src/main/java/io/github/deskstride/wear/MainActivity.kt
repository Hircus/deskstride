package io.github.deskstride.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.WindowManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** State sent by the phone (WearBridge.stateOf in the phone app). */
data class PhoneState(
    val connected: Boolean, val running: Boolean, val paused: Boolean, val belt: Boolean,
    val kmh: Double, val targetKmh: Double, val elapsedS: Long, val steps: Int, val maxKmh: Double,
) {
    companion object {
        fun parse(s: String): PhoneState {
            val m = s.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
            fun b(k: String) = m[k] == "1"
            fun d(k: String) = m[k]?.toDoubleOrNull() ?: 0.0
            return PhoneState(
                b("conn"), b("run"), b("pause"), b("belt"),
                d("kmh"), d("target"), d("time").toLong(), d("steps").toInt(), d("max").takeIf { it > 0 } ?: 6.0,
            )
        }
    }
}

/**
 * Treadmill remote: the phone stays connected to the treadmill, the watch sends commands and shows the state.
 * Speed: −/+ or the rotary bezel, sent 400 ms after the last click so the treadmill isn't flooded.
 */
class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private val main = Handler(Looper.getMainLooper())
    private val messages by lazy { Wearable.getMessageClient(this) }
    private val nodes by lazy { Wearable.getNodeClient(this) }

    private lateinit var status: TextView
    private lateinit var speed: TextView
    private lateinit var stats: TextView
    private lateinit var target: TextView
    private lateinit var minus: Button
    private lateinit var plus: Button
    private lateinit var primary: Button
    private lateinit var stop: Button

    private var state: PhoneState? = null
    private var stateAt = 0L
    /** speed picked with −/+/bezel and not yet sent */
    private var pendingKmh: Double? = null
    /** the first tap on "Start" arms, the second (within 3 s) starts: the belt never starts by accident */
    private var armedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        speed = findViewById(R.id.speed)
        stats = findViewById(R.id.stats)
        target = findViewById(R.id.target)
        minus = findViewById(R.id.minus)
        plus = findViewById(R.id.plus)
        primary = findViewById(R.id.primary)
        stop = findViewById(R.id.stop)

        minus.setOnClickListener { nudge(-0.1) }
        plus.setOnClickListener { nudge(+0.1) }
        stop.setOnClickListener { send("stop") }
        primary.setOnClickListener { onPrimary() }
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) send("connect") // from the Tile button
        render()
    }

    override fun onResume() {
        super.onResume()
        messages.addListener(this)
        main.post(watchdog)
    }

    override fun onPause() {
        messages.removeListener(this)
        setHeartRate(false)
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** Every second: if the phone has been silent for 3 s, say hello again (app reopened, Bluetooth back). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - stateAt > 3000) { send("hello"); render() }
            main.postDelayed(this, 1000)
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != STATE) return
        state = PhoneState.parse(String(e.data))
        stateAt = SystemClock.elapsedRealtime()
        render()
    }

    private fun send(cmd: String) {
        nodes.connectedNodes.addOnSuccessListener { list ->
            list.forEach { messages.sendMessage(it.id, CMD, cmd.toByteArray()) }
        }
    }

    private fun onPrimary() {
        val s = state ?: return
        when {
            !s.connected -> send("connect")
            s.belt && s.running -> send("pause")
            SystemClock.elapsedRealtime() < armedUntil -> { armedUntil = 0; send("start") }
            else -> { armedUntil = SystemClock.elapsedRealtime() + 3000; main.postDelayed(::render, 3000) }
        }
        render()
    }

    // --- speed ---------------------------------------------------------------------------

    private fun nudge(delta: Double) {
        val s = state ?: return
        if (!s.belt) return
        val base = pendingKmh ?: s.targetKmh
        pendingKmh = (Math.round((base + delta) * 10) / 10.0).coerceIn(1.0, s.maxKmh.coerceAtMost(6.0))
        main.removeCallbacks(commit)
        main.postDelayed(commit, 400)
        render()
    }

    private val commit = Runnable {
        pendingKmh?.let { send("speed:$it") }
        // keep the value on screen until the phone confirms the new target
        main.postDelayed({ pendingKmh = null; render() }, 1500)
    }

    /** Bezel (physical or touch): one click = 0.1 km/h. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val v = -e.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (v != 0f) nudge(if (v > 0) 0.1 else -0.1)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // --- heart rate --------------------------------------------------------------------

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var hrOn = false
    private var askedHr = false
    private var bpm = 0
    private var lastHrSent = 0L

    private val hrListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0].toInt()
            // 0 while the sensor warms up, NO_CONTACT when the watch is off the wrist
            if (v <= 0 || e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return
            bpm = v
            val now = SystemClock.elapsedRealtime()
            if (now - lastHrSent >= 3000) { lastHrSent = now; send("hr:$v"); render() }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    /** Sensor on only during a walk: otherwise it drains the battery for nothing. */
    private fun setHeartRate(on: Boolean) {
        if (on == hrOn) return
        if (!on) { sensors.unregisterListener(hrListener); hrOn = false; bpm = 0; return }
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            if (!askedHr) { askedHr = true; requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1) }
            return
        }
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        hrOn = sensors.registerListener(hrListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render() // if granted, render turns the sensor back on
    }

    // --- UI ------------------------------------------------------------------------------

    private fun render() {
        val s = state
        val fresh = s != null && SystemClock.elapsedRealtime() - stateAt <= 3000
        val armed = SystemClock.elapsedRealtime() < armedUntil
        status.text = when {
            !fresh -> getString(R.string.status_open_phone)
            !s!!.connected -> getString(R.string.status_not_connected)
            s.paused -> getString(R.string.status_paused)
            s.belt -> getString(R.string.status_moving)
            else -> getString(R.string.status_ready)
        }
        val walking = fresh && s!!.running
        // while walking: screen on (the app stays in the foreground) and heart rate to the phone
        if (walking) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setHeartRate(walking && !s!!.paused)
        speed.text = kmh(if (fresh) s!!.kmh else 0.0)
        val heart = if (bpm > 0) " · ♥ $bpm" else ""
        stats.text = if (walking) "${duration(s!!.elapsedS)} · " + getString(R.string.steps_count, s.steps) + heart else "—"
        target.text = if (fresh && s!!.belt) "→ ${kmh(pendingKmh ?: s.targetKmh)}" else "—"

        val canSpeed = fresh && s!!.belt
        minus.isEnabled = canSpeed; plus.isEnabled = canSpeed
        minus.alpha = if (canSpeed) 1f else 0.35f; plus.alpha = minus.alpha

        primary.isEnabled = fresh
        primary.alpha = if (fresh) 1f else 0.35f
        primary.text = when {
            !fresh -> "—"
            !s!!.connected -> getString(R.string.btn_connect)
            s.belt && s.running -> getString(R.string.btn_pause)
            armed -> getString(R.string.btn_confirm)
            s.paused -> getString(R.string.btn_resume)
            else -> getString(R.string.btn_start)
        }
        stop.visibility = if (fresh && (s!!.belt || s.running)) View.VISIBLE else View.GONE
    }

    private fun kmh(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
    private fun duration(s: Long) = if (s >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)

    companion object {
        const val CMD = "/deskstride/cmd"
        const val STATE = "/deskstride/state"
        const val EXTRA_CONNECT = "connect"
    }
}
