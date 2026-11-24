package io.github.deskstride

import kotlin.math.roundToInt

data class Session(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Int,
    val calories: Int,
    val steps: Int,
    val speedSamples: List<Pair<Long, Double>>, // (epoch ms, km/h)
    /** true if the app connected to a belt that was already moving: the start time is reconstructed. */
    val estimatedStart: Boolean = false,
    /** true if steps come from the treadmill's sensor, false if estimated from distance */
    val stepsMeasured: Boolean = false,
    /** heart rate from the watch: (epoch ms, bpm) */
    val hrSamples: List<Pair<Long, Int>> = emptyList(),
) {
    val durationS get() = (endMs - startMs) / 1000
    val avgKmh get() = if (durationS > 0) distanceM / 1000.0 / (durationS / 3600.0) else 0.0
    val maxKmh get() = speedSamples.maxOfOrNull { it.second } ?: 0.0
    /** minutes per km */
    val paceMinKm get() = if (distanceM > 0) durationS / 60.0 / (distanceM / 1000.0) else 0.0
    val avgBpm get() = if (hrSamples.isEmpty()) 0 else hrSamples.sumOf { it.second } / hrSamples.size
    val maxBpm get() = hrSamples.maxOfOrNull { it.second } ?: 0
}

/**
 * Fallback when the treadmill doesn't report steps (short packets only): distance divided by
 * step length, which grows with height and speed.
 * Example at 175 cm: 3 km/h -> ~62 cm, 5 km/h -> ~74 cm, 6 km/h -> ~80 cm.
 */
object StepModel {
    fun stepLengthM(heightCm: Int, kmh: Double): Double {
        val ratio = (0.25 + 0.035 * kmh).coerceIn(0.30, 0.60)
        return heightCm / 100.0 * ratio
    }
}

/**
 * A session starts on the first packet with actual speed > 0 and ends when speed stays at 0
 * for [stopGraceMs] (except while paused), the connection drops or the user ends it ([finishNow]).
 *
 * Distance, time and steps are treadmill counters, reset every time the belt starts.
 * With long packets (67 3C) we use the treadmill's time and steps: the start time is exact
 * even when connecting to a belt that is already moving. With short packets only (66 35)
 * we reconstruct the start as distance / current speed and estimate steps with [StepModel].
 */
class SessionTracker(
    private val stopGraceMs: Long = 3000,
    private val minDurationS: Long = 10,
    private val lateJoinMinM: Int = 20,
    private val stepLengthM: (kmh: Double) -> Double = { StepModel.stepLengthM(175, it) },
    private val onFinished: (Session) -> Unit,
) {
    var running = false; private set
    var startMs = 0L; private set
    var distanceM = 0; private set
    var calories = 0; private set
    var estimatedStart = false; private set
    /** after [finishNow] we ignore the belt until it stops, so a new session doesn't open right away */
    var waitingForStop = false; private set
    /** steps from the treadmill's sensor, when available */
    var measuredSteps: Int? = null; private set
    val steps get() = measuredSteps ?: stepAcc.roundToInt()
    val samples: List<Pair<Long, Double>> get() = _samples
    /** last belt state from long packets */
    var beltState: BeltState? = null; private set
    /** belt paused: the session stays open until the treadmill switches to STOPPED */
    val paused get() = running && beltState == BeltState.PAUSED

    private var stepAcc = 0.0
    private var lastMovingMs = 0L
    private var lastElapsedMs: Long? = null
    private var stepsOffset: Int? = null
    private val freshStartMs = 10_000L
    private val _samples = mutableListOf<Pair<Long, Double>>()
    private val _hr = mutableListOf<Pair<Long, Int>>()

    fun onPacket(p: TreadmillPacket, nowMs: Long) {
        p.state?.let { beltState = it }
        val moving = p.actualKmh > 0.0
        if (waitingForStop) { if (!moving) waitingForStop = false; return }
        if (!running) {
            if (!moving) return
            begin(p, nowMs)
        }
        if (moving) {
            lastMovingMs = nowMs
            val counterReset = p.distanceM + 5 < distanceM ||
                (p.elapsedMs != null && lastElapsedMs != null && p.elapsedMs + 5000 < lastElapsedMs!!)
            if (counterReset) restart(p, nowMs) // belt restarted
            _samples += nowMs to p.actualKmh
            if (p.elapsedMs != null) {
                // treadmill time is the exact source: re-align on every long packet
                startMs = nowMs - p.elapsedMs
                lastElapsedMs = p.elapsedMs
                estimatedStart = false
            }
            if (p.steps != null) {
                // The treadmill's step counter is not reset on start like time and distance are:
                // right after a fresh start any steps already there are leftovers, so we subtract them.
                // When joining a session that's been running for a while we can't tell them apart: offset 0.
                val off = stepsOffset ?: (if ((p.elapsedMs ?: Long.MAX_VALUE) <= freshStartMs) p.steps else 0)
                    .also { stepsOffset = it }
                if (p.steps < off) stepsOffset = 0 // the treadmill reset it late
                measuredSteps = maxOf(measuredSteps ?: 0, p.steps - stepsOffset!!)
            }
            if (p.distanceM > distanceM) {
                stepAcc += (p.distanceM - distanceM) / stepLengthM(p.actualKmh)
                distanceM = p.distanceM
            }
            calories = maxOf(calories, p.calories)
        } else if (!paused && nowMs - lastMovingMs >= stopGraceMs) {
            finish()
        }
    }

    /** Ends the session at the user's request. Returns false if it was too short to be saved. */
    fun finishNow(): Boolean {
        if (!running) return false
        waitingForStop = true
        return finish()
    }

    fun onDisconnect() { waitingForStop = false; if (running) finish() }

    /** Heart rate from the watch: only counted while a session is open and not paused. */
    fun onHeartRate(bpm: Int, nowMs: Long) { if (running && !paused) _hr += nowMs to bpm }

    private fun begin(p: TreadmillPacket, nowMs: Long) {
        running = true
        lastMovingMs = nowMs
        _samples.clear(); _hr.clear()
        distanceM = p.distanceM
        calories = p.calories
        measuredSteps = null
        stepsOffset = null
        lastElapsedMs = null
        if (p.distanceM >= lateJoinMinM) {
            estimatedStart = true
            startMs = nowMs - (p.distanceM / (p.actualKmh / 3.6) * 1000).toLong()
            stepAcc = p.distanceM / stepLengthM(p.actualKmh)
        } else {
            estimatedStart = false
            startMs = nowMs
            stepAcc = 0.0
        }
    }

    private fun restart(p: TreadmillPacket, nowMs: Long) {
        _samples.clear(); _hr.clear()
        estimatedStart = false
        startMs = nowMs
        distanceM = p.distanceM
        calories = p.calories
        stepAcc = 0.0
        measuredSteps = null
        stepsOffset = null
        lastElapsedMs = null
    }

    /** The current session as it is right now (for checkpoints), null if stopped. */
    fun snapshot(): Session? = if (!running) null else Session(
        startMs, lastMovingMs, distanceM, calories, steps, _samples.toList(),
        estimatedStart, stepsMeasured = measuredSteps != null, hrSamples = _hr.toList(),
    )

    private fun finish(): Boolean {
        val s = snapshot()!!
        running = false
        val valid = s.durationS >= minDurationS && s.distanceM > 0
        if (valid) onFinished(s)
        return valid
    }
}
