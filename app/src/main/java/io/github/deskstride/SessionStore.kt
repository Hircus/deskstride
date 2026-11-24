package io.github.deskstride

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Health Connect upload status of a saved session. */
sealed class HcStatus {
    object Pending : HcStatus()
    object Sent : HcStatus()
    data class Failed(val reason: String) : HcStatus()

    fun encode() = when (this) { Pending -> "pending"; Sent -> "ok"; is Failed -> "error:$reason" }

    companion object {
        fun decode(s: String) = when {
            s == "ok" -> Sent
            s.startsWith("error:") -> Failed(s.removePrefix("error:"))
            else -> Pending
        }
    }
}

data class StoredSession(val session: Session, val hc: HcStatus) {
    val id get() = session.startMs
}

/** Local history: one JSON file per session in filesDir/sessions. */
class SessionStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "sessions").apply { mkdirs() }

    private fun file(id: Long) = File(dir, "$id.json")

    fun save(s: Session, hc: HcStatus) = file(s.startMs).writeText(encode(s, hc).toString())

    fun setHc(id: Long, hc: HcStatus) { load(id)?.let { save(it.session, hc) } }

    fun load(id: Long): StoredSession? =
        file(id).takeIf { it.exists() }?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }

    /** All sessions, newest first, WITHOUT speed and heart-rate samples (use [load] for those). */
    fun all(): List<StoredSession> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .mapNotNull(::summary)
            .sortedByDescending { it.id }

    // The history is read often (main screen, widgets): summaries stay in memory
    // and a file is re-read only when it changes (timestamp or size).
    private fun summary(f: File): StoredSession? {
        val stamp = f.lastModified() to f.length()
        cache[f.name]?.let { (s, st) -> if (s == stamp) return st }
        val st = runCatching { decode(JSONObject(f.readText())) }.getOrNull() ?: return null
        val light = st.session.copy(speedSamples = emptyList(), hrSamples = emptyList())
        return st.copy(session = light).also { cache[f.name] = stamp to it }
    }

    private companion object {
        val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, StoredSession>>()
    }

    fun delete(id: Long) { file(id).delete() }

    // Session in progress, checkpointed periodically (outside sessions/, so it's not in the history):
    // if the process dies, on restart [takeCurrent] returns it so it can be saved as a regular session.
    private val current = File(ctx.filesDir, "current.json")
    private val currentTmp = File(ctx.filesDir, "current.tmp")

    fun saveCurrent(s: Session) {
        currentTmp.writeText(encode(s, HcStatus.Pending).toString())
        currentTmp.renameTo(current) // a half-written file never replaces the last good one
    }

    fun clearCurrent() { current.delete() }

    fun takeCurrent(): Session? =
        current.takeIf { it.exists() }
            ?.let { runCatching { decode(JSONObject(it.readText())).session }.getOrNull() }
            .also { clearCurrent() }

    private fun encode(s: Session, hc: HcStatus) = JSONObject().apply {
        put("start", s.startMs); put("end", s.endMs)
        put("dist", s.distanceM); put("kcal", s.calories); put("steps", s.steps)
        put("estimated", s.estimatedStart)
        put("stepsMeasured", s.stepsMeasured)
        put("hc", hc.encode())
        put("samples", JSONArray().apply {
            s.speedSamples.forEach { (t, v) -> put(JSONArray().put(t).put(v)) }
        })
        put("hr", JSONArray().apply { s.hrSamples.forEach { (t, v) -> put(JSONArray().put(t).put(v)) } })
    }

    private fun decode(o: JSONObject): StoredSession {
        val arr = o.getJSONArray("samples")
        val samples = (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i); p.getLong(0) to p.getDouble(1)
        }
        val hrArr = o.optJSONArray("hr") ?: JSONArray() // missing in sessions saved before heart-rate support
        val hr = (0 until hrArr.length()).map { i -> hrArr.getJSONArray(i).let { it.getLong(0) to it.getInt(1) } }
        return StoredSession(
            Session(
                o.getLong("start"), o.getLong("end"), o.getInt("dist"), o.getInt("kcal"),
                o.getInt("steps"), samples, o.optBoolean("estimated"), o.optBoolean("stepsMeasured"), hr,
            ),
            HcStatus.decode(o.optString("hc", "pending")),
        )
    }
}
