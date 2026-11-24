package io.github.deskstride

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.deskstride.widget.LiveState
import io.github.deskstride.widget.Widgets
import io.github.deskstride.widget.WidgetStats
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Treadmill connection, current session and commands. Lives in the process, not in the Activity:
 * it survives the screen turning off and the app being closed, while [WalkService] keeps the process alive.
 * The Activity attaches through [listener] and only draws. Everything runs on the main thread.
 */
@SuppressLint("StaticFieldLeak") // applicationContext only
object Walk {
    interface Listener {
        /** state, buttons or requested speed changed: redraw */
        fun onUpdate() {}
        fun onPacket(p: TreadmillPacket, sessionStarted: Boolean) {}
        /** true if the UI opens the report, which sends to Health Connect; otherwise [Walk] sends in the background */
        fun onSessionFinished(s: Session): Boolean = false
        fun onHistoryChanged() {}
        fun requestBlePermissions() {}
    }

    var listener: Listener? = null

    private const val PREFS = "deskstride"
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var heightCm: Int
        get() = prefs.getInt("height_cm", 175)
        set(v) { prefs.edit().putInt("height_cm", v).apply() }
    var weightKg: Int
        get() = prefs.getInt("weight_kg", TreadmillCommands.DEFAULT_WEIGHT_KG)
        set(v) { prefs.edit().putInt("weight_kg", v).apply() }
    /** treadmill picked in the scanner (null = first PitPat found) */
    val deviceAddress get() = prefs.getString("device_address", null)
    val deviceName get() = prefs.getString("device_name", null)
    /** Steps to Health Connect. Readable without [init]: the report may send from a freshly started process. */
    fun sendSteps(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hc_steps", true)
    fun setSendSteps(v: Boolean) = prefs.edit().putBoolean("hc_steps", v).apply()
    fun saveDevice(address: String?, name: String?) =
        prefs.edit().putString("device_address", address).putString("device_name", name).apply()

    lateinit var ble: TreadmillBle; private set
    lateinit var store: SessionStore; private set
    private val health by lazy { HealthWriter(app) }
    private lateinit var wear: WearBridge
    private val scope = MainScope()
    val tracker = SessionTracker(stepLengthM = { StepModel.stepLengthM(heightCm, it) }) { onSessionFinished(it) }

    var bleState = BleState.IDLE; private set
    var statusText = ""; private set
    var lastPacket: TreadmillPacket? = null; private set
    /** speed requested but not yet confirmed by the treadmill */
    var desiredKmh: Double? = null; private set
    var maxKmh = TreadmillCommands.DEFAULT_MAX_KMH; private set
    /** speed to resume at after a pause */
    var pausedKmh = TreadmillCommands.MIN_KMH; private set

    val ready get() = ble.ready && lastPacket != null
    val beltMoving get() = (lastPacket?.actualKmh ?: 0.0) > 0.0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        statusText = app.getString(R.string.status_disconnected)
        store = SessionStore(app)
        ble = TreadmillBle(app, ::onPacket, ::onStatus, ::onDisconnected)
        wear = WearBridge(app, ::onWatchCommand).also { it.start() }
        // the process died mid-session: the last checkpoint becomes a regular session
        store.takeCurrent()?.let { s ->
            store.save(s, HcStatus.Pending)
            Widgets.updateAll(app)
            scope.launch { health.send(store, s); listener?.onHistoryChanged() }
        }
        publishToday()
    }

    val hasBlePermissions get() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    fun connect() {
        if (ble.active) return
        if (!hasBlePermissions) { listener?.requestBlePermissions(); return }
        ble.start(deviceAddress)
        if (ble.active) WalkService.start(app) // not started if Bluetooth is off
    }

    fun disconnect() {
        ble.stop()
        onDisconnected()
        WalkService.stop(app)
    }

    fun showError(text: String) = onStatus(BleState.ERROR, text)

    // --- data from the treadmill ------------------------------------------------------

    private var lastWidgetUpdate = 0L
    private var lastCheckpoint = 0L
    private const val CHECKPOINT_MS = 30_000L

    private fun onPacket(p: TreadmillPacket) {
        val now = System.currentTimeMillis()
        val wasRunning = tracker.running
        tracker.onPacket(p, now)
        lastPacket = p
        p.maxKmh?.let { maxKmh = it }
        // the treadmill accepted the requested speed (or the belt stopped)
        desiredKmh?.let { if (Math.abs(p.targetKmh - it) < 0.05 || p.actualKmh == 0.0) desiredKmh = null }
        if (tracker.running) {
            if (!wasRunning) lastCheckpoint = now
            if (now - lastCheckpoint >= CHECKPOINT_MS) {
                lastCheckpoint = now
                tracker.snapshot()?.takeIf { it.distanceM > 0 }?.let(store::saveCurrent)
            }
            publishLive(force = !wasRunning)
        } else store.clearCurrent()
        listener?.onPacket(p, tracker.running && !wasRunning)
        publishWear()
    }

    private fun onStatus(state: BleState, text: String) {
        bleState = state; statusText = text
        val connected = state == BleState.CONNECTED
        if (connected != LiveState.connected) {
            LiveState.connected = connected
            if (!connected) LiveState.clear()
            Widgets.updateLive(app)
        }
        WalkService.refresh(app)
        update()
    }

    private fun onDisconnected() {
        tracker.onDisconnect()
        store.clearCurrent()
        lastPacket = null; desiredKmh = null
        update()
    }

    private fun onSessionFinished(s: Session) {
        store.clearCurrent()
        store.save(s, HcStatus.Pending)
        LiveState.clear()
        Widgets.updateAll(app)
        publishToday()
        listener?.onHistoryChanged()
        if (listener?.onSessionFinished(s) != true) {
            scope.launch { health.send(store, s); listener?.onHistoryChanged() }
        }
    }

    private fun update() { listener?.onUpdate(); publishWear() }

    /** State shared with widgets and the notification; [force] for important changes. */
    private fun publishLive(force: Boolean = false) {
        LiveState.running = tracker.running
        LiveState.startMs = tracker.startMs
        LiveState.steps = tracker.steps
        LiveState.distanceM = tracker.distanceM
        LiveState.kmh = lastPacket?.actualKmh ?: 0.0
        val now = System.currentTimeMillis()
        if (force || now - lastWidgetUpdate >= 5000) {
            lastWidgetUpdate = now
            Widgets.updateLive(app)
            WalkService.refresh(app)
        }
        if (now - lastTodayPush >= 30_000) publishToday()
    }

    private var lastTodayPush = 0L

    /** Today's totals for the watch Tile (they stay in the Data Layer even when the phone is off). */
    private fun publishToday() {
        lastTodayPush = System.currentTimeMillis()
        val t = WidgetStats.totalsFor(java.time.LocalDate.now(), store.all())
        wear.publishToday(t.steps, t.distanceM, t.durationS, tracker.running)
    }

    private fun publishWear() {
        if (!::wear.isInitialized) return
        val p = lastPacket
        wear.publish(WearBridge.stateOf(
            connected = ready,
            running = tracker.running, paused = tracker.paused, belt = beltMoving,
            kmh = p?.actualKmh ?: 0.0,
            targetKmh = desiredKmh ?: if (tracker.paused) pausedKmh else p?.targetKmh ?: 0.0,
            elapsedS = if (tracker.running) (System.currentTimeMillis() - tracker.startMs) / 1000 else 0,
            steps = tracker.steps, distanceM = tracker.distanceM, maxKmh = maxKmh,
        ))
    }

    /** Commands from the watch: same rules as the app buttons (the watch asks for start confirmation). */
    private fun onWatchCommand(cmd: String) {
        when {
            cmd == "connect" -> connect()
            cmd == "stop" -> stopAndFinish()
            cmd == "pause" -> if (ready && beltMoving && tracker.running) pause()
            cmd == "start" -> start()
            cmd.startsWith("hr:") -> {
                // data from another device: drop impossible values
                val bpm = cmd.removePrefix("hr:").toIntOrNull()?.takeIf { it in 30..230 } ?: return
                tracker.onHeartRate(bpm, System.currentTimeMillis())
                return
            }
            cmd.startsWith("speed:") -> {
                val v = cmd.removePrefix("speed:").toDoubleOrNull() ?: return
                if (ready && beltMoving) setSpeed(v)
            }
        }
        publishWear()
    }

    // --- commands to the treadmill ----------------------------------------------------

    /** Immediate stop, no confirmation: stopping the belt must always be one tap away. */
    fun stopAndFinish() {
        ble.send(TreadmillCommands.stop(weightKg))
        desiredKmh = null
        if (tracker.running && !tracker.finishNow()) {
            Toast.makeText(app, R.string.session_too_short, Toast.LENGTH_SHORT).show()
        }
        if (!tracker.running) store.clearCurrent()
        update()
    }

    /** Start at minimum speed, or resume after a pause at the previous speed. */
    fun start() {
        if (!ready || beltMoving) return
        ble.send(TreadmillCommands.start(if (tracker.paused) pausedKmh else TreadmillCommands.MIN_KMH, maxKmh, weightKg))
    }

    fun pause() {
        val p = lastPacket ?: return
        pausedKmh = desiredKmh ?: p.targetKmh
        desiredKmh = null
        ble.send(TreadmillCommands.pause(pausedKmh, maxKmh, weightKg))
        update()
    }

    private var lastSentKmh: Double? = null
    private var lastSentAt = 0L

    /** One − / + step (also repeated while held). Sending is throttled to one command every 700 ms. */
    fun stepSpeed(delta: Double) {
        val p = lastPacket ?: return
        if (!beltMoving) return
        val base = desiredKmh ?: p.targetKmh
        val next = TreadmillCommands.clamp(Math.round((base + delta) * 10) / 10.0, maxKmh)
        if (Math.abs(next - base) < 0.05) return
        desiredKmh = next
        update()
        if (System.currentTimeMillis() - lastSentAt >= 700) sendSpeed(next)
    }

    /** On button release: send the final value if it hasn't gone out yet. */
    fun commitSpeed() {
        val d = desiredKmh ?: return
        if (lastSentKmh == null || Math.abs(d - lastSentKmh!!) > 0.05) sendSpeed(d)
    }

    fun setSpeed(kmh: Double) {
        desiredKmh = TreadmillCommands.clamp(Math.round(kmh * 10) / 10.0, maxKmh)
        sendSpeed(desiredKmh!!)
        update()
    }

    private fun sendSpeed(kmh: Double) {
        if (!beltMoving) return
        ble.send(TreadmillCommands.setSpeed(kmh, maxKmh, weightKg))
        lastSentKmh = kmh
        lastSentAt = System.currentTimeMillis()
    }
}
