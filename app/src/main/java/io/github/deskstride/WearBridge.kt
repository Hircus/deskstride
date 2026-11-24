package io.github.deskstride

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Remote control from the watch (:wear module) over the Wearable Data Layer.
 * The watch sends commands on [CMD] ("hello", "connect", "start", "pause", "stop", "speed:2.3", "hr:98");
 * the phone replies with its state on [STATE] ("key=value;..." format from [stateOf]).
 * State only goes to watches that said hello: they open the app and send "hello".
 */
class WearBridge(ctx: Context, private val onCommand: (String) -> Unit) : MessageClient.OnMessageReceivedListener {
    private val messages = Wearable.getMessageClient(ctx)
    private var watches = emptySet<String>()
    private var last = ""

    fun start() = messages.addListener(this)
    fun stop() = messages.removeListener(this)

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != CMD) return
        watches = watches + e.sourceNodeId
        val cmd = String(e.data)
        if (cmd == "hello") publish(last) else onCommand(cmd)
    }

    fun publish(state: String) {
        last = state
        if (state.isEmpty()) return
        watches.forEach { messages.sendMessage(it, STATE, state.toByteArray()) }
    }

    private val data = Wearable.getDataClient(ctx)

    /** Today's totals for the Tile: a DataItem stays available to the watch even when the phone is silent. */
    fun publishToday(steps: Int, distanceM: Int, durationS: Long, running: Boolean) {
        val req = PutDataMapRequest.create(TODAY).apply {
            dataMap.putString("date", java.time.LocalDate.now().toString())
            dataMap.putInt("steps", steps)
            dataMap.putInt("dist", distanceM)
            dataMap.putLong("dur", durationS)
            dataMap.putBoolean("run", running)
        }
        data.putDataItem(req.asPutDataRequest().setUrgent())
    }

    companion object {
        const val CMD = "/deskstride/cmd"
        const val STATE = "/deskstride/state"
        const val TODAY = "/deskstride/today"

        fun stateOf(
            connected: Boolean, running: Boolean, paused: Boolean, belt: Boolean,
            kmh: Double, targetKmh: Double, elapsedS: Long, steps: Int, distanceM: Int, maxKmh: Double,
        ) = listOf(
            "conn" to connected.bit(), "run" to running.bit(), "pause" to paused.bit(), "belt" to belt.bit(),
            "kmh" to kmh, "target" to targetKmh, "time" to elapsedS, "steps" to steps, "dist" to distanceM, "max" to maxKmh,
        ).joinToString(";") { (k, v) -> "$k=$v" }

        private fun Boolean.bit() = if (this) 1 else 0
    }
}
