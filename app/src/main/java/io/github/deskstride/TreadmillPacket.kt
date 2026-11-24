package io.github.deskstride

/** Belt state, byte 26 bits 3-5 of long packets. */
enum class BeltState { STOPPED, RUNNING, PAUSED, COUNTDOWN, OTHER }

/**
 * Status packets of the PitPat-T01 / BA10-B (service 0xFBA0, notifications on 0xFBA2).
 * Protocol from an HCI log and from how the PitPat 4.25 app parses packets, studied for interoperability (full map in docs/PROTOCOL.md).
 *
 * The treadmill sends two formats:
 *
 * 1) "66 35", 20 bytes, unsolicited (even without requests):
 *    [3..4] actual speed km/h x1000, [5..6] target speed, [7..10] u32 distance m, [18..19] u16 kcal
 *
 * 2) "67 3C", 60 bytes, reply to the status request [TreadmillCommands.POLL]
 *    (needs MTU >= 63). Same fields as format 1 plus:
 *    [20..23] u32 BE  elapsed time of the treadmill session, in ms (firmware > 19)
 *    [26]     flags, bits 3-5 = [BeltState]
 *    [27..28] u16 BE  treadmill max speed km/h x1000 (6.0)
 *    [43..44] u16 BE  steps estimated from motor current: on the T01 the "real" steps [14..17] are always 0
 *    [58] checksum (XOR of bytes 1..57), [59] terminator 0x43
 *
 * All values are big-endian. Every treadmill session (belt start) resets
 * distance, time and kcal (steps: see [SessionTracker]).
 */
data class TreadmillPacket(
    val actualKmh: Double,
    val targetKmh: Double,
    val distanceM: Int,
    val calories: Int,
    /** session time according to the treadmill; null in short packets */
    val elapsedMs: Long? = null,
    /** steps measured by the treadmill; null in short packets */
    val steps: Int? = null,
    /** max speed reported by the treadmill; null in short packets */
    val maxKmh: Double? = null,
    /** belt state; null in short packets */
    val state: BeltState? = null,
) {
    companion object {
        const val SIZE = 20
        const val LONG_SIZE = 60

        fun parse(b: ByteArray): TreadmillPacket? {
            if (b.size < SIZE) return null
            fun u8(i: Int) = b[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            return when {
                u8(0) == 0x66 && u8(1) == 0x35 -> TreadmillPacket(
                    actualKmh = u16(3) / 1000.0,
                    targetKmh = u16(5) / 1000.0,
                    distanceM = u32(7).toInt(),
                    calories = u16(18),
                )
                u8(0) == 0x67 && u8(1) == 0x3C && b.size >= LONG_SIZE &&
                    TreadmillCommands.validFrame(b.copyOf(LONG_SIZE)) -> TreadmillPacket(
                    actualKmh = u16(3) / 1000.0,
                    targetKmh = u16(5) / 1000.0,
                    distanceM = u32(7).toInt(),
                    calories = u16(18),
                    elapsedMs = u32(20),
                    steps = u16(43),
                    maxKmh = u16(27).takeIf { it > 0 }?.div(1000.0),
                    state = when (u8(26) and 0x38) {
                        0x00 -> BeltState.STOPPED
                        0x08 -> BeltState.RUNNING
                        0x10 -> BeltState.PAUSED
                        0x18 -> BeltState.COUNTDOWN
                        else -> BeltState.OTHER
                    },
                )
                else -> null
            }
        }
    }
}

/**
 * Frames to 0xFBA1: [0]=0x6A, [1]=length, ..., [n-2]=XOR of bytes 1..n-3, [n-1]=0x43.
 *
 * Control command (23 bytes), as sent by PitPat:
 *   [6..7] speed km/h x1000   [8] acceleration time: 0x05 start/stop, 0x01 speed change
 *   [10] user weight in kg (the treadmill uses it for kcal)   [12] action: 0x04 run, 0x02 pause, 0x00 stop
 *   [13..20] PitPat account user id (0 on stop): see [userId]
 */
object TreadmillCommands {
    /** Status request: the treadmill replies with a 67 3C packet. Read-only. */
    val POLL = frame(byteArrayOf(0x6A, 0x05, 0xFD.toByte()))

    const val MIN_KMH = 1.0
    const val DEFAULT_MAX_KMH = 6.0
    /** 0x4E, the weight the PitPat app was sending */
    const val DEFAULT_WEIGHT_KG = 78

    /**
     * PitPat account id for bytes [13..20], from `pitpat.userId` in local.properties (0 if missing).
     * The treadmill doesn't seem to check it; a non-zero value is the only one tested on hardware.
     */
    internal var userId: Long = BuildConfig.PITPAT_USER_ID

    /** Stops the belt. */
    fun stop(weightKg: Int = DEFAULT_WEIGHT_KG) = control(0.0, mode = 0x05, action = 0x00, weightKg)

    /** Starts (or resumes after a pause) the belt: the treadmill runs its own countdown. */
    fun start(kmh: Double = MIN_KMH, maxKmh: Double = DEFAULT_MAX_KMH, weightKg: Int = DEFAULT_WEIGHT_KG) =
        control(clamp(kmh, maxKmh), mode = 0x05, action = 0x04, weightKg)

    /** Changes speed while the belt is moving. */
    fun setSpeed(kmh: Double, maxKmh: Double = DEFAULT_MAX_KMH, weightKg: Int = DEFAULT_WEIGHT_KG) =
        control(clamp(kmh, maxKmh), mode = 0x01, action = 0x04, weightKg)

    /** Pauses: the treadmill keeps time, distance and steps. */
    fun pause(kmh: Double, maxKmh: Double = DEFAULT_MAX_KMH, weightKg: Int = DEFAULT_WEIGHT_KG) =
        control(clamp(kmh, maxKmh), mode = 0x05, action = 0x02, weightKg)

    fun clamp(kmh: Double, maxKmh: Double = DEFAULT_MAX_KMH) =
        kmh.coerceIn(MIN_KMH, maxKmh.coerceIn(MIN_KMH, DEFAULT_MAX_KMH))

    private fun control(kmh: Double, mode: Int, action: Int, weightKg: Int): ByteArray {
        val v = Math.round(kmh * 1000).toInt()
        val b = ByteArray(21)
        b[0] = 0x6A; b[1] = 0x17
        b[6] = (v shr 8).toByte(); b[7] = v.toByte()
        b[8] = mode.toByte()
        b[10] = weightKg.coerceIn(30, 200).toByte()
        b[12] = action.toByte()
        if (action != 0) for (i in 0 until 8) b[13 + i] = (userId shr (56 - 8 * i)).toByte() // u64 big-endian
        return frame(b)
    }

    fun checksum(b: ByteArray, len: Int = b.size): Byte {
        var x = 0
        for (i in 1 until len - 2) x = x xor (b[i].toInt() and 0xFF)
        return x.toByte()
    }

    fun validFrame(b: ByteArray) = b.size >= 4 && b.last() == 0x43.toByte() && b[b.size - 2] == checksum(b)

    /** Appends checksum and terminator to the body (header + length + payload). */
    private fun frame(body: ByteArray): ByteArray {
        val out = body.copyOf(body.size + 2)
        out[out.size - 1] = 0x43
        out[out.size - 2] = checksum(out)
        return out
    }
}
