package io.github.deskstride

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Report of a saved session. With EXTRA_SEND it sends it to Health Connect right away. */
class ReportActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_SEND = "send"
        fun intent(ctx: Context, id: Long, send: Boolean = false) =
            Intent(ctx, ReportActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SEND, send)
    }

    private val store by lazy { SessionStore(this) }
    private val health by lazy { HealthWriter(this) }
    private lateinit var stored: StoredSession

    private lateinit var hcDot: View
    private lateinit var hcStatus: TextView
    private lateinit var btnSend: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        stored = store.load(intent.getLongExtra(EXTRA_ID, -1)) ?: run { finish(); return }
        val s = stored.session

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.day).text = Fmt.day(s.startMs)
        findViewById<TextView>(R.id.hours).text = "${Fmt.time(s.startMs)} – ${Fmt.time(s.endMs)}"
        findViewById<TextView>(R.id.duration).text = Fmt.duration(s.durationS)
        findViewById<View>(R.id.estimated).visibility = if (s.estimatedStart) View.VISIBLE else View.GONE
        findViewById<SpeedChart>(R.id.chart).setAll(s.speedSamples.map { it.second })

        stat(R.id.statDistance, R.string.stat_distance, "km", R.drawable.ic_distance, R.color.accent).text = Fmt.km(s.distanceM)
        stat(R.id.statSteps, R.string.stat_steps, getString(if (s.stepsMeasured) R.string.steps_measured else R.string.steps_estimated),
            R.drawable.ic_steps, R.color.accent).text = Fmt.int(s.steps)
        stat(R.id.statKcal, R.string.stat_calories, "kcal", R.drawable.ic_kcal, R.color.warn).text =
            if (s.calories > 0) s.calories.toString() else "—"
        stat(R.id.statPace, R.string.stat_pace, "min/km", R.drawable.ic_time, R.color.accent2).text = Fmt.pace(s.paceMinKm)
        stat(R.id.statAvg, R.string.stat_avg_speed, "km/h", R.drawable.ic_speed, R.color.accent2).text = Fmt.kmh(s.avgKmh)
        stat(R.id.statMax, R.string.stat_max_speed, "km/h", R.drawable.ic_speed, R.color.error).text = Fmt.kmh(s.maxKmh)
        if (s.hrSamples.isNotEmpty()) {
            findViewById<View>(R.id.hrRow).visibility = View.VISIBLE
            stat(R.id.statHrAvg, R.string.stat_avg_hr, "bpm", R.drawable.ic_heart, R.color.error).text = s.avgBpm.toString()
            stat(R.id.statHrMax, R.string.stat_max_hr, "bpm", R.drawable.ic_heart, R.color.error).text = s.maxBpm.toString()
        }

        hcDot = findViewById(R.id.hcDot)
        hcStatus = findViewById(R.id.hcStatus)
        btnSend = findViewById(R.id.btnSend)
        btnSend.setOnClickListener { send() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        showHc(stored.hc)
        if (intent.getBooleanExtra(EXTRA_SEND, false) && savedInstanceState == null) send()
    }

    private fun stat(id: Int, label: Int, unit: String, icon: Int, tint: Int) =
        findViewById<View>(id).bindStat(getString(label), unit, icon, tint)

    private fun send() {
        showHc(HcStatus.Pending, sending = true)
        lifecycleScope.launch {
            val st = health.send(store, stored.session)
            stored = stored.copy(hc = st)
            showHc(st)
        }
    }

    private fun showHc(st: HcStatus, sending: Boolean = false) {
        val (color, text) = when (st) {
            HcStatus.Sent -> R.color.accent to getString(R.string.hc_sent)
            is HcStatus.Failed -> R.color.error to getString(R.string.hc_failed, st.reason)
            HcStatus.Pending -> R.color.warn to getString(if (sending) R.string.hc_sending else R.string.hc_not_sent)
        }
        hcDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        hcStatus.text = text
        btnSend.visibility = if (st == HcStatus.Sent || sending) View.GONE else View.VISIBLE
        btnSend.setText(if (st is HcStatus.Failed) R.string.retry else R.string.send)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(stored.id); io.github.deskstride.widget.Widgets.updateAll(this); finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
