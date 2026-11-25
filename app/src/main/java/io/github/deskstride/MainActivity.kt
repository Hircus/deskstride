package io.github.deskstride

import android.Manifest
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** UI only: connection, session and commands live in [Walk], which outlives the Activity. */
class MainActivity : AppCompatActivity(), Walk.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val tracker get() = Walk.tracker
    private val health by lazy { HealthWriter(this) }

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var phase: TextView
    private lateinit var speed: TextView
    private lateinit var target: TextView
    private lateinit var chart: SpeedChart
    private lateinit var time: TextView
    private lateinit var distance: TextView
    private lateinit var steps: TextView
    private lateinit var kcal: TextView
    private lateinit var lastCard: View
    private lateinit var last: TextView
    private lateinit var btnFinish: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnMinus: MaterialButton
    private lateinit var btnPlus: MaterialButton
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnHealth: MaterialButton
    private lateinit var btnHeight: MaterialButton
    private lateinit var btnPause: MaterialButton
    private lateinit var btnScan: MaterialButton

    private val bluetoothPerms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    /** on connect we also ask for notifications (the service's one); if denied we carry on anyway */
    private val connectPerms = bluetoothPerms +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private val blePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Walk.hasBlePermissions) Walk.connect() else Walk.showError(getString(R.string.error_bt_denied))
    }
    private val scanPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Walk.hasBlePermissions) showScanner() else Walk.showError(getString(R.string.error_bt_denied))
    }
    private val hcPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refreshHealthButton()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (tracker.running && !tracker.paused) time.text = Fmt.duration((System.currentTimeMillis() - tracker.startMs) / 1000)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // handy to glance at while walking
        setContentView(R.layout.activity_main)
        Walk.init(this)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        phase = findViewById(R.id.phase)
        speed = findViewById(R.id.speed)
        target = findViewById(R.id.target)
        chart = findViewById(R.id.chart)
        time = findViewById<View>(R.id.statTime).bindStat(getString(R.string.stat_time), "", R.drawable.ic_time, R.color.accent2)
        distance = findViewById<View>(R.id.statDistance).bindStat(getString(R.string.stat_distance), "km", R.drawable.ic_distance, R.color.accent)
        steps = findViewById<View>(R.id.statSteps).bindStat(getString(R.string.stat_steps), "", R.drawable.ic_steps, R.color.accent)
        kcal = findViewById<View>(R.id.statKcal).bindStat(getString(R.string.stat_calories), "kcal", R.drawable.ic_kcal, R.color.warn)
        lastCard = findViewById(R.id.lastCard)
        last = findViewById(R.id.last)
        btnFinish = findViewById(R.id.btnFinish)
        btnStart = findViewById(R.id.btnStart)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)
        btnConnect = findViewById(R.id.btnConnect)
        btnHealth = findViewById(R.id.btnHealth)
        btnHeight = findViewById(R.id.btnHeight)
        btnPause = findViewById(R.id.btnPause)
        btnScan = findViewById(R.id.btnScan)
        btnPause.onTap { Walk.pause() }
        btnScan.onTap { scanPerms.launch(bluetoothPerms) }

        findViewById<View>(R.id.btnHistory).onTap {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnFinish.onTap { Walk.stopAndFinish() }
        btnStart.onTap { confirmStart() }
        btnMinus.setRepeatListener({ Walk.stepSpeed(-0.1) }, Walk::commitSpeed)
        btnPlus.setRepeatListener({ Walk.stepSpeed(+0.1) }, Walk::commitSpeed)
        target.onTap { askSpeed() }
        btnConnect.onTap {
            if (Walk.ble.active) Walk.disconnect() else blePerms.launch(connectPerms)
        }
        btnHealth.onTap {
            when {
                !HealthWriter.available(this) -> Walk.showError(getString(R.string.error_hc_missing))
                hcGranted -> HealthWriter.openAppPage(this)
                else -> hcPerms.launch(HealthWriter.PERMISSIONS)
            }
        }
        btnHeight.onTap { askProfile() }

        Walk.listener = this
        // reopened mid-session: redraw the chart from what Walk has collected
        if (tracker.running) chart.setAll(tracker.samples.map { it.second })
        Walk.lastPacket?.let { animateSpeed(it.actualKmh) }
        render()
        refreshHeight()
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    /** From the "Walk" widget: connect to the treadmill automatically. */
    private fun handleWidgetIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTOCONNECT, false) != true) return
        i.removeExtra(EXTRA_AUTOCONNECT) // don't repeat after a rotation
        if (!Walk.ble.active) blePerms.launch(connectPerms)
    }

    companion object { const val EXTRA_AUTOCONNECT = "autoconnect" }

    private var hcGranted = false

    override fun onResume() {
        super.onResume()
        refreshHealthButton()
        refreshLast()
        main.post(ticker)
    }
    override fun onPause() { main.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() {
        // the connection stays alive: only "Disconnect" closes it
        if (Walk.listener === this) Walk.listener = null
        super.onDestroy()
    }

    // --- events from Walk -------------------------------------------------------------

    override fun onUpdate() = render()

    override fun onPacket(p: TreadmillPacket, sessionStarted: Boolean) {
        animateSpeed(p.actualKmh)
        if (tracker.running) {
            if (sessionStarted) chart.clear()
            chart.add(p.actualKmh)
        }
        render()
    }

    override fun onSessionFinished(s: Session): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        // the report sends to Health Connect and shows the result
        startActivity(ReportActivity.intent(this, s.startMs, send = true))
        return true
    }

    override fun onHistoryChanged() = refreshLast()

    override fun requestBlePermissions() = blePerms.launch(connectPerms)

    // --- commands ---------------------------------------------------------------------

    private fun confirmStart() {
        // after a pause it resumes at the previous speed: the user is already on the belt
        if (tracker.paused) { Walk.start(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_title)
            .setMessage(getString(R.string.start_message, Fmt.kmh(TreadmillCommands.MIN_KMH)))
            .setPositiveButton(R.string.start_confirm) { _, _ -> Walk.start() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var shownKmh = 0.0
    private var speedAnim: ValueAnimator? = null

    /** The treadmill updates twice a second: interpolate so the number glides instead of jumping. */
    private fun animateSpeed(kmh: Double) {
        if (Math.abs(kmh - shownKmh) < 0.001) return
        speedAnim?.cancel()
        speedAnim = ValueAnimator.ofFloat(shownKmh.toFloat(), kmh.toFloat()).apply {
            duration = 480
            interpolator = LinearInterpolator()
            addUpdateListener {
                shownKmh = (it.animatedValue as Float).toDouble()
                speed.text = Fmt.kmh(shownKmh)
            }
            start()
        }
    }

    private fun askSpeed() {
        val p = Walk.lastPacket
        if (p == null || !Walk.beltMoving) {
            Toast.makeText(this, R.string.start_belt_first, Toast.LENGTH_SHORT).show(); return
        }
        val max = TreadmillCommands.clamp(Walk.maxKmh)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Fmt.kmh(Walk.desiredKmh ?: p.targetKmh))
            selectAll()
            textSize = 28f
            gravity = android.view.Gravity.CENTER
        }
        val box = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_title)
            .setMessage(getString(R.string.speed_range, Fmt.kmh(TreadmillCommands.MIN_KMH), Fmt.kmh(max)))
            .setView(box)
            .setPositiveButton(R.string.speed_set) { _, _ ->
                input.text.toString().replace(',', '.').toDoubleOrNull()?.let(Walk::setSpeed)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    // --- UI ---------------------------------------------------------------------------

    private fun render() {
        status.text = Walk.statusText
        val c = when (Walk.bleState) {
            BleState.IDLE -> R.color.text_dim
            BleState.SEARCHING, BleState.CONNECTING -> R.color.warn
            BleState.CONNECTED -> R.color.accent
            BleState.ERROR -> R.color.error
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color(c))
        showTarget()
        refreshButtons()
        val p = Walk.lastPacket
        if (tracker.running && p != null) showRunning(p) else showIdle()
    }

    private fun showRunning(p: TreadmillPacket) {
        val ramp = p.targetKmh - p.actualKmh
        phase.text = when {
            tracker.paused -> getString(R.string.phase_paused)
            ramp > 0.05 -> getString(R.string.phase_speeding_up, Fmt.kmh(p.targetKmh))
            ramp < -0.05 -> getString(R.string.phase_slowing_down, Fmt.kmh(p.targetKmh))
            tracker.estimatedStart -> getString(R.string.phase_moving_joined)
            else -> getString(R.string.phase_moving)
        }
        phase.setTextColor(color(if (Math.abs(ramp) > 0.05) R.color.warn else R.color.accent))
        if (!tracker.paused) time.text = Fmt.duration((System.currentTimeMillis() - tracker.startMs) / 1000)
        distance.text = Fmt.km(tracker.distanceM)
        steps.text = Fmt.int(tracker.steps)
        kcal.text = if (tracker.calories > 0) tracker.calories.toString() else "—"
    }

    private fun showTarget() {
        val p = Walk.lastPacket
        val d = Walk.desiredKmh
        target.text = when {
            d != null -> "→ ${Fmt.kmh(d)} km/h"
            p != null -> getString(R.string.target_set, Fmt.kmh(p.targetKmh))
            else -> getString(R.string.target_none)
        }
    }

    private fun showIdle() {
        phase.text = getString(when {
            tracker.waitingForStop -> R.string.phase_saved_stop_belt
            Walk.ble.active -> R.string.phase_ready
            else -> R.string.phase_waiting
        })
        phase.setTextColor(color(R.color.text_dim))
        if (!tracker.waitingForStop) animateSpeed(0.0)
    }

    private fun refreshLast() {
        val st = Walk.store.all().firstOrNull()
        if (st == null) { lastCard.visibility = View.GONE; return }
        val s = st.session
        val hc = when (st.hc) {
            HcStatus.Sent -> getString(R.string.hc_sent_short)
            is HcStatus.Failed -> getString(R.string.hc_failed_short)
            HcStatus.Pending -> getString(R.string.hc_pending_short)
        }
        last.text = "${Fmt.shortDay(s.startMs)} · ${Fmt.duration(s.durationS)} · ${Fmt.km(s.distanceM)} km\n" +
            getString(R.string.steps_count, Fmt.int(s.steps)) + " · $hc"
        lastCard.visibility = View.VISIBLE
        lastCard.onTap { startActivity(ReportActivity.intent(this, st.id)) }
    }

    private fun refreshButtons() {
        val ready = Walk.ready
        val moving = Walk.beltMoving
        btnFinish.visibility = if (tracker.running || (ready && moving)) View.VISIBLE else View.GONE
        btnFinish.setText(if (tracker.running) R.string.btn_stop_finish else R.string.btn_stop_belt)
        btnStart.visibility = if (ready && !moving && (!tracker.running || tracker.paused)) View.VISIBLE else View.GONE
        btnStart.text = if (tracker.paused) getString(R.string.btn_resume_at, Fmt.kmh(Walk.pausedKmh)) else getString(R.string.btn_start_belt)
        btnPause.visibility = if (ready && moving && tracker.running && !tracker.paused) View.VISIBLE else View.GONE
        val canChange = ready && moving
        val set = Walk.desiredKmh ?: Walk.lastPacket?.targetKmh ?: 0.0
        btnMinus.isEnabled = canChange && set > TreadmillCommands.MIN_KMH + 0.05
        btnPlus.isEnabled = canChange && set < Walk.maxKmh - 0.05
        btnMinus.alpha = if (btnMinus.isEnabled) 1f else 0.35f
        btnPlus.alpha = if (btnPlus.isEnabled) 1f else 0.35f
        if (Walk.ble.active) {
            btnConnect.setText(R.string.btn_disconnect)
            btnConnect.backgroundTintList = ColorStateList.valueOf(color(R.color.card))
            btnConnect.setTextColor(color(R.color.text))
        } else {
            btnConnect.text = Walk.deviceName?.let { getString(R.string.btn_connect_to, it) } ?: getString(R.string.btn_connect)
            btnConnect.backgroundTintList = ColorStateList.valueOf(color(R.color.accent))
            btnConnect.setTextColor(color(R.color.on_accent))
        }
    }

    private fun refreshHealthButton() {
        if (!HealthWriter.available(this)) { btnHealth.setText(R.string.hc_unavailable); return }
        lifecycleScope.launch {
            val ok = runCatching { health.hasPermissions() }.getOrDefault(false)
            hcGranted = ok
            btnHealth.setText(if (ok) R.string.btn_hc_open else R.string.btn_hc_permissions)
            btnHealth.setTextColor(color(if (ok) R.color.accent else R.color.text))
        }
    }

    private fun refreshHeight() {
        btnHeight.text = getString(R.string.btn_profile, Walk.heightCm, Walk.weightKg)
    }

    private fun askProfile() {
        fun field(hint: String, value: Int) = com.google.android.material.textfield.TextInputLayout(this).apply {
            this.hint = hint
            addView(com.google.android.material.textfield.TextInputEditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            })
        }
        val h = field(getString(R.string.profile_height), Walk.heightCm)
        val w = field(getString(R.string.profile_weight), Walk.weightKg)
        val stepsBox = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            setText(R.string.profile_send_steps)
            isChecked = Walk.sendSteps(context)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(h); addView(w); addView(stepsBox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_title)
            .setMessage(R.string.profile_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                h.editText?.text.toString().toIntOrNull()?.takeIf { it in 100..230 }?.let { Walk.heightCm = it }
                w.editText?.text.toString().toIntOrNull()?.takeIf { it in 30..200 }?.let { Walk.weightKg = it }
                Walk.setSendSteps(stepsBox.isChecked)
                refreshHeight()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- scanner: nearby treadmills ----------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission") // requested by scanPerms
    private fun showScanner() {
        // a connected treadmill stops advertising: disconnect so it shows up in the list
        if (Walk.ble.active) Walk.disconnect()
        val found = linkedMapOf<String, android.bluetooth.le.ScanResult>()
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        fun label(r: android.bluetooth.le.ScanResult): String {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: getString(R.string.scan_unnamed)
            val saved = if (r.device.address == Walk.deviceAddress) getString(R.string.scan_saved) else ""
            return "$name$saved\n" + getString(R.string.scan_signal, r.device.address, r.rssi)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_searching)
            .setAdapter(adapter) { _, which ->
                val r = found.values.elementAt(which)
                Walk.saveDevice(r.device.address, r.scanRecord?.deviceName ?: r.device.name)
                blePerms.launch(connectPerms)
            }
            .setNeutralButton(R.string.scan_any) { _, _ ->
                Walk.saveDevice(null, null)
                refreshButtons()
            }
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { Walk.ble.stopDiscover() }
            .show()
        Walk.ble.discover { r ->
            found[r.device.address] = r
            adapter.clear(); adapter.addAll(found.values.map(::label))
            dlg.setTitle(getString(R.string.scan_found, found.size))
        }
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)
}
