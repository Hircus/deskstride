package io.github.deskstride.widget

import io.github.deskstride.Session
import io.github.deskstride.StoredSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** State of the workout in progress, written by Walk and read by the widgets (same process). */
object LiveState {
    @Volatile var connected = false
    @Volatile var running = false
    @Volatile var startMs = 0L
    @Volatile var steps = 0
    @Volatile var distanceM = 0
    @Volatile var kmh = 0.0

    fun clear() { running = false; steps = 0; distanceM = 0; kmh = 0.0 }
}

data class DayTotals(val steps: Int, val distanceM: Int, val durationS: Long, val sessions: Int)

/** Aggregates for the widgets from the local history (plus the session in progress). */
object WidgetStats {
    private val zone get() = ZoneId.systemDefault()

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun totalsFor(date: LocalDate, all: List<StoredSession>, includeLive: Boolean = true): DayTotals {
        val list = all.map { it.session }.filter { day(it.startMs) == date }
        var steps = list.sumOf { it.steps }
        var dist = list.sumOf { it.distanceM }
        var dur = list.sumOf { it.durationS }
        var n = list.size
        if (includeLive && LiveState.running && day(LiveState.startMs) == date) {
            steps += LiveState.steps; dist += LiveState.distanceM
            dur += (System.currentTimeMillis() - LiveState.startMs) / 1000; n++
        }
        return DayTotals(steps, dist, dur, n)
    }

    /** Last 7 days, oldest first, ending today. */
    fun lastWeek(all: List<StoredSession>): List<Pair<LocalDate, DayTotals>> {
        val today = LocalDate.now(zone)
        return (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to totalsFor(it, all) } }
    }

    fun last(all: List<StoredSession>): Session? = all.firstOrNull()?.session
}
