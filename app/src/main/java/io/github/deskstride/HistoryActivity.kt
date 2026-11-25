package io.github.deskstride

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** History of saved activities, with totals. */
class HistoryActivity : AppCompatActivity() {

    private val store by lazy { SessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.btnBack).onTap { finish() }
    }

    override fun onResume() {
        super.onResume()
        render() // refresh after a delete or a send from the report
    }

    private fun render() {
        val all = store.all()
        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        findViewById<View>(R.id.empty).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.totKm).text = Fmt.km(all.sumOf { it.session.distanceM })
        findViewById<TextView>(R.id.totSteps).text = Fmt.int(all.sumOf { it.session.steps })
        val totS = all.sumOf { it.session.durationS }
        findViewById<TextView>(R.id.totTime).text =
            if (totS >= 3600) String.format(Fmt.locale, "%dh %02dm", totS / 3600, totS / 60 % 60)
            else "${totS / 60}m"

        val inflater = LayoutInflater.from(this)
        for (st in all) {
            val s = st.session
            val row = inflater.inflate(R.layout.item_session, list, false)
            row.findViewById<TextView>(R.id.title).text = "${Fmt.shortDay(s.startMs)} · ${Fmt.time(s.startMs)}"
            row.findViewById<TextView>(R.id.subtitle).text =
                "${Fmt.duration(s.durationS)} · ${Fmt.km(s.distanceM)} km · " + getString(R.string.steps_count, Fmt.int(s.steps))
            val c = when (st.hc) {
                HcStatus.Sent -> R.color.accent
                is HcStatus.Failed -> R.color.error
                HcStatus.Pending -> R.color.warn
            }
            row.findViewById<View>(R.id.hcDot).backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, c))
            row.onTap { startActivity(ReportActivity.intent(this, st.id)) }
            list.addView(row)
        }
    }
}
