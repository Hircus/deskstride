package io.github.deskstride.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import io.github.deskstride.*
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.reflect.KClass

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.render(ctx, mgr, ids, this::class)
}
class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.render(ctx, mgr, ids, this::class)
    // the chart is drawn at the widget size: redraw it when the user resizes the widget
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) =
        Widgets.render(ctx, mgr, intArrayOf(id), this::class)
}
class LastWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.render(ctx, mgr, ids, this::class)
}
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.render(ctx, mgr, ids, this::class)
}

object Widgets {
    private val all = listOf(TodayWidget::class, WeekWidget::class, LastWidget::class, QuickWidget::class)

    /** After a session is saved or the state changes. */
    fun updateAll(ctx: Context) = all.forEach { update(ctx, it) }

    /** During a workout: only the widgets with live data. */
    fun updateLive(ctx: Context) { update(ctx, QuickWidget::class); update(ctx, TodayWidget::class) }

    private fun update(ctx: Context, cls: KClass<out AppWidgetProvider>) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls.java))
        if (ids.isNotEmpty()) render(ctx, mgr, ids, cls)
    }

    fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray, cls: KClass<out AppWidgetProvider>) {
        val sessions = SessionStore(ctx).all()
        if (cls == WeekWidget::class) {
            // one render per widget: each has its own size
            for (id in ids) mgr.updateAppWidget(id, week(ctx, sessions, sizeDp(mgr, id)))
            return
        }
        val views = when (cls) {
            TodayWidget::class -> today(ctx, sessions)
            LastWidget::class -> last(ctx, sessions)
            else -> quick(ctx)
        }
        mgr.updateAppWidget(ids, views)
    }

    /** Widget size in dp in portrait (the launcher passes it in the options). */
    private fun sizeDp(mgr: AppWidgetManager, id: Int): SizeF {
        val o = mgr.getAppWidgetOptions(id)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.minByOrNull { it.width } // the narrowest one is portrait
                ?.let { return it }
        }
        return SizeF(
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat(),
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat(),
        )
    }

    // --- individual widgets ---------------------------------------------------------

    private fun today(ctx: Context, sessions: List<StoredSession>): RemoteViews {
        val t = WidgetStats.totalsFor(LocalDate.now(), sessions)
        fun build(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            setTextViewText(R.id.steps, Fmt.int(t.steps))
            setTextViewText(R.id.km, "${Fmt.km(t.distanceM)} km")
            setTextViewText(R.id.minutes, "${t.durationS / 60} min")
            setTextViewText(R.id.live, if (LiveState.running) ctx.getString(R.string.live_in_progress) else "")
            setOnClickPendingIntent(R.id.root, openMain(ctx))
        }
        // Responsive layouts: the launcher picks the largest one that fits the widget.
        return RemoteViews(
            mapOf(
                SizeF(100f, 40f) to build(R.layout.widget_today_compact),
                SizeF(100f, 140f) to build(R.layout.widget_today),
            ),
        )
    }

    private fun week(ctx: Context, sessions: List<StoredSession>, size: SizeF): RemoteViews {
        val days = WidgetStats.lastWeek(sessions)
        val total = days.sumOf { it.second.steps }
        val km = days.sumOf { it.second.distanceM }
        val compact = size.height < 130
        // chart area = widget minus padding, title and (compact) left column, matching the layouts
        val chartW = if (compact) size.width - 32 - 110 - 12 else size.width - 32
        val chartH = if (compact) size.height - 20 else size.height - 32 - 8 - 34
        return RemoteViews(ctx.packageName, if (compact) R.layout.widget_week_compact else R.layout.widget_week).apply {
            setTextViewText(R.id.total, Fmt.int(total))
            setTextViewText(R.id.sub, ctx.getString(if (compact) R.string.widget_week_sub_compact else R.string.widget_week_sub, Fmt.km(km)))
            setImageViewBitmap(R.id.chart, weekChart(ctx, days, chartW.coerceAtLeast(120f), chartH.coerceAtLeast(40f)))
            setOnClickPendingIntent(R.id.root, openHistory(ctx))
        }
    }

    private fun last(ctx: Context, sessions: List<StoredSession>): RemoteViews =
        // narrow (half screen): the Health Connect status becomes just a symbol, otherwise the text gets cut
        RemoteViews(mapOf(SizeF(100f, 40f) to last(ctx, sessions, narrow = true), SizeF(250f, 40f) to last(ctx, sessions, narrow = false)))

    private fun last(ctx: Context, sessions: List<StoredSession>, narrow: Boolean): RemoteViews {
        val st = sessions.firstOrNull()
        return RemoteViews(ctx.packageName, R.layout.widget_last).apply {
            if (st == null) {
                setTextViewText(R.id.title, ctx.getString(R.string.widget_last_empty_title))
                setTextViewText(R.id.subtitle, ctx.getString(R.string.widget_last_empty_sub))
                setViewVisibility(R.id.hc, View.GONE)
                setOnClickPendingIntent(R.id.root, openMain(ctx))
            } else {
                val s = st.session
                // narrow: no time and duration, otherwise both lines end in "…"
                setTextViewText(R.id.title, if (narrow) Fmt.shortDay(s.startMs) else "${Fmt.shortDay(s.startMs)} · ${Fmt.time(s.startMs)}")
                setTextViewText(
                    R.id.subtitle,
                    if (narrow) "${Fmt.km(s.distanceM)} km\n" + ctx.getString(R.string.steps_count, Fmt.int(s.steps))
                    else "${Fmt.duration(s.durationS)} · ${Fmt.km(s.distanceM)} km · " + ctx.getString(R.string.steps_count, Fmt.int(s.steps)),
                )
                setInt(R.id.subtitle, "setMaxLines", if (narrow) 2 else 1)
                setViewVisibility(R.id.hc, View.VISIBLE)
                val sent = st.hc == HcStatus.Sent
                setTextViewText(R.id.hc, when {
                    narrow -> if (sent) "✓" else "!"
                    sent -> ctx.getString(R.string.hc_sent_short)
                    else -> ctx.getString(R.string.hc_to_send)
                })
                setTextColor(R.id.hc, ContextCompat.getColor(ctx, if (st.hc == HcStatus.Sent) R.color.accent else R.color.warn))
                setOnClickPendingIntent(R.id.root, openReport(ctx, st.id))
            }
        }
    }

    private fun quick(ctx: Context): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_quick).apply {
            if (LiveState.running) {
                val s = (System.currentTimeMillis() - LiveState.startMs) / 1000
                setTextViewText(R.id.title, "${Fmt.duration(s)} · ${Fmt.kmh(LiveState.kmh)} km/h")
                setTextViewText(R.id.subtitle, ctx.getString(R.string.steps_count, Fmt.int(LiveState.steps)) + " · ${Fmt.km(LiveState.distanceM)} km")
            } else if (LiveState.connected) {
                setTextViewText(R.id.title, ctx.getString(R.string.status_connected))
                setTextViewText(R.id.subtitle, ctx.getString(R.string.widget_quick_ready))
            } else {
                setTextViewText(R.id.title, ctx.getString(R.string.widget_quick_title))
                setTextViewText(R.id.subtitle, ctx.getString(R.string.widget_quick_tap))
            }
            setOnClickPendingIntent(R.id.root, openMain(ctx, autoConnect = true))
        }

    // --- intent -----------------------------------------------------------------------

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun mainIntent(ctx: Context, autoConnect: Boolean = false) =
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOCONNECT, autoConnect)

    private fun openMain(ctx: Context, autoConnect: Boolean = false): PendingIntent =
        PendingIntent.getActivity(ctx, if (autoConnect) 1 else 0, mainIntent(ctx, autoConnect), FLAGS)

    private fun openHistory(ctx: Context): PendingIntent = TaskStackBuilder.create(ctx)
        .addNextIntent(mainIntent(ctx))
        .addNextIntent(Intent(ctx, HistoryActivity::class.java))
        .getPendingIntent(2, FLAGS)!!

    private fun openReport(ctx: Context, id: Long): PendingIntent = TaskStackBuilder.create(ctx)
        .addNextIntent(mainIntent(ctx))
        .addNextIntent(ReportActivity.intent(ctx, id))
        .getPendingIntent(3, FLAGS)!!

    // --- weekly chart ---------------------------------------------------------------

    /** Drawn at the real size ([wDp] x [hDp]) so the launcher doesn't scale it down. */
    private fun weekChart(ctx: Context, days: List<Pair<LocalDate, DayTotals>>, wDp: Float, hDp: Float): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val accent2 = ContextCompat.getColor(ctx, R.color.accent2)
        val dim = ContextCompat.getColor(ctx, R.color.text_dim)
        val empty = ContextCompat.getColor(ctx, R.color.card_stroke)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dim; textSize = 12 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val labelH = 20 * d
        val max = maxOf(days.maxOf { it.second.steps }, 1)
        val slot = w / days.size.toFloat()
        val bw = minOf(slot * 0.55f, 26 * d)
        val r = bw / 2
        days.forEachIndexed { i, (date, t) ->
            val cx = slot * i + slot / 2
            val bottom = h - labelH
            val top = if (t.steps == 0) bottom - 4 * d else bottom - (bottom - 4 * d) * t.steps / max
            val isToday = i == days.lastIndex
            bar.shader = if (t.steps == 0) null else LinearGradient(0f, top, 0f, bottom, if (isToday) accent else accent2, accent, Shader.TileMode.CLAMP)
            bar.color = if (t.steps == 0) empty else Color.WHITE
            if (!isToday && t.steps > 0) bar.alpha = 170 else bar.alpha = 255
            c.drawRoundRect(cx - bw / 2, top, cx + bw / 2, bottom, r, r, bar)
            label.color = if (isToday) accent else dim
            val name = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Fmt.locale).uppercase()
            c.drawText(name, cx, h - 4 * d, label)
        }
        return bmp
    }
}
