package io.github.deskstride

import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    private fun pkt(hex: String) = TreadmillPacket.parse(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())!!

    @Test fun parsesCapturedPackets() {
        // 2 km/h, 10 m (captured)
        val p = pkt("66350007D007D00000000A0000000000000000000000".take(40))
        assertEquals(2.0, p.actualKmh, 0.0); assertEquals(2.0, p.targetKmh, 0.0); assertEquals(10, p.distanceM)
    }

    @Test fun rejectsBadHeader() {
        assertNull(TreadmillPacket.parse(ByteArray(20)))
    }

    @Test fun sessionFromIdleToStop() {
        var done: Session? = null
        val t = SessionTracker { done = it }
        // idle with a previous total of 1874 m, then 20 s at 1 km/h, then stop
        val idle = TreadmillPacket(0.0, 0.0, 1874, 0)
        t.onPacket(idle, 0)
        t.onPacket(TreadmillPacket(1.0, 1.0, 0, 0), 3_000)
        for (s in 4..25) t.onPacket(TreadmillPacket(1.0, 1.0, (s - 3) * 28 / 100, 0), s * 1000L)
        for (s in 26..30) t.onPacket(TreadmillPacket(0.0, 0.0, 6, 0), s * 1000L)
        val r = done!!
        assertEquals(22, r.durationS); assertEquals(6, r.distanceM)
        // 6 m at 1 km/h, 175 cm -> 52.5 cm step -> 11 steps
        assertEquals(11, r.steps)
    }

    @Test fun joinsSessionAlreadyRunning() {
        // real packet captured with the belt running for ~30 min: 2.2 km/h, 1046 m, byte19 = 75
        val p = pkt("663500089808980000041600000000000000004b")
        assertEquals(2.2, p.actualKmh, 1e-9); assertEquals(1046, p.distanceM); assertEquals(75, p.calories)

        var done: Session? = null
        val t = SessionTracker { done = it }
        val now = 10_000_000L
        t.onPacket(p, now)
        assertTrue(t.running); assertTrue(t.estimatedStart)
        assertEquals(1046, t.distanceM)
        // 1046 m at 2.2 km/h = ~1711.6 s already elapsed
        assertEquals(1711, (now - t.startMs) / 1000)
        assertTrue(t.steps > 1500)

        t.onPacket(p.copy(distanceM = 1047), now + 1000)
        assertEquals(1047, t.distanceM)
        assertTrue(t.finishNow())
        assertTrue(done!!.estimatedStart)
        assertEquals(1047, done!!.distanceM)

        // after "Finish" a belt that keeps running does not reopen a session
        t.onPacket(p.copy(distanceM = 1050), now + 2000)
        assertFalse(t.running)
        // until it stops
        t.onPacket(p.copy(actualKmh = 0.0), now + 3000)
        t.onPacket(p.copy(distanceM = 0), now + 4000)
        assertTrue(t.running); assertFalse(t.estimatedStart)
    }

    @Test fun counterResetRestartsSession() {
        val t = SessionTracker { }
        // first moving packet still shows the old total, then the counter resets
        t.onPacket(TreadmillPacket(1.0, 1.0, 1874, 0), 1_000)
        assertTrue(t.estimatedStart)
        t.onPacket(TreadmillPacket(1.0, 1.0, 0, 0), 2_000)
        assertFalse(t.estimatedStart); assertEquals(0, t.distanceM); assertEquals(2_000, t.startMs)
    }

    // --- protocol derived from the PitPat HCI log ---

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun pollCommandMatchesPitPat() {
        assertEquals("6a05fdf843", TreadmillCommands.POLL.hex())
    }

    @Test fun controlCommandsMatchPitPatByteForByte() {
        // captured from the PitPat HCI log, with the account id (bytes 13-20) zeroed and the checksum refitted
        TreadmillCommands.userId = 0
        assertEquals("6a1700000000000005004e000000000000000000005c43", TreadmillCommands.stop().hex())
        assertEquals("6a170000000003e805004e00040000000000000000b343", TreadmillCommands.start(1.0).hex())
        assertEquals("6a170000000003e801004e00040000000000000000b743", TreadmillCommands.setSpeed(1.0).hex())
        assertEquals("6a1700000000044c01004e000400000000000000001443", TreadmillCommands.setSpeed(1.1).hex())
        assertEquals("6a170000000004b001004e00040000000000000000e843", TreadmillCommands.setSpeed(1.2).hex())
        assertEquals("6a1700000000051401004e000400000000000000004d43", TreadmillCommands.setSpeed(1.3).hex())
        assertEquals("6a1700000000057801004e000400000000000000002143", TreadmillCommands.setSpeed(1.4).hex())
        assertEquals("6a170000000005dc01004e000400000000000000008543", TreadmillCommands.setSpeed(1.5).hex())
    }

    @Test fun userIdIsBigEndianInBytes13to20() {
        TreadmillCommands.userId = 0x0102030405060708
        val c = TreadmillCommands.start(1.0)
        assertTrue(TreadmillCommands.validFrame(c))
        assertEquals("0102030405060708", c.copyOfRange(13, 21).hex())
        assertEquals("0000000000000000", TreadmillCommands.stop().copyOfRange(13, 21).hex()) // never sent on stop
        TreadmillCommands.userId = BuildConfig.PITPAT_USER_ID
    }

    @Test fun speedIsClampedToSafeRange() {
        assertEquals(TreadmillCommands.setSpeed(6.0).hex(), TreadmillCommands.setSpeed(12.0).hex())
        assertEquals(TreadmillCommands.setSpeed(1.0).hex(), TreadmillCommands.setSpeed(0.2).hex())
        assertEquals(TreadmillCommands.setSpeed(4.0, maxKmh = 4.0).hex(), TreadmillCommands.setSpeed(5.0, maxKmh = 4.0).hex())
        // even if the treadmill reported a higher max, 6 km/h is never exceeded
        assertEquals(TreadmillCommands.setSpeed(6.0).hex(), TreadmillCommands.setSpeed(8.0, maxKmh = 10.0).hex())
    }

    @Test fun parsesLongStatusPacket() {
        // real reply at 17:33:51, belt running for ~54 min
        val p = pkt("673c00089808980000069f00000000000000007a0031a6f058250c177000050500000000000000002604220efe000005000000000000000000005b43")
        assertEquals(2.2, p.actualKmh, 1e-9); assertEquals(1695, p.distanceM); assertEquals(122, p.calories)
        assertEquals(3_254_000L, p.elapsedMs)
        assertEquals(3838, p.steps)
    }

    // --- fields from how the PitPat app parses packets ---

    private val captured = "673c00089808980000069f00000000000000007a0031a6f058250c177000050500000000000000002604220efe000005000000000000000000005b43"

    /** Rimette checksum e terminatore validi dopo aver modificato un pacchetto. */
    private fun refit(hex: String): String {
        val b = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        b[b.size - 2] = TreadmillCommands.checksum(b)
        return b.hex()
    }

    @Test fun beltStateFromByte26() {
        assertEquals(BeltState.RUNNING, pkt(captured).state) // 0x0C
        assertEquals(BeltState.PAUSED, pkt(refit(captured.replaceRange(52, 54, "14"))).state)
        assertEquals(BeltState.STOPPED, pkt(refit(captured.replaceRange(52, 54, "04"))).state)
        assertNull(pkt("663500089808980000069e00000000000000007a").state)
    }

    @Test fun caloriesAndDistanceAreWide() {
        // 300 kcal = 0x012C in bytes 18-19, 70 000 m = 0x00011170 in bytes 7-10
        val p = pkt("663500089808980001117000000000000000012c")
        assertEquals(300, p.calories); assertEquals(70_000, p.distanceM)
    }

    @Test fun pauseAndWeightCommands() {
        val c = TreadmillCommands.pause(2.2, weightKg = 90)
        assertTrue(TreadmillCommands.validFrame(c))
        assertEquals(0x08, c[6].toInt()); assertEquals(0x98.toByte(), c[7]) // 2200
        assertEquals(5, c[8].toInt()); assertEquals(90, c[10].toInt()); assertEquals(2, c[12].toInt())
        assertEquals(90, TreadmillCommands.start(1.0, weightKg = 90)[10].toInt())
        assertEquals(90, TreadmillCommands.stop(90)[10].toInt())
    }

    @Test fun pauseKeepsSessionOpen() {
        var done: Session? = null
        val t = SessionTracker { done = it }
        val run = pkt(captured)
        val paused = pkt(refit(captured.replaceRange(6, 10, "0000").replaceRange(52, 54, "14")))
        val stopped = pkt(refit(captured.replaceRange(6, 10, "0000").replaceRange(52, 54, "04")))
        t.onPacket(run, 0)
        for (s in 1..60) t.onPacket(paused, s * 1000L)
        assertTrue(t.running); assertTrue(t.paused); assertNull(done)
        t.onPacket(run, 61_000)
        assertFalse(t.paused)
        t.onPacket(stopped, 62_000)
        t.onPacket(stopped, 65_000)
        assertFalse(t.running); assertNotNull(done)
    }

    @Test fun rejectsLongPacketWithBadChecksum() {
        val good = "673c00089808980000069f00000000000000007a0031a6f058250c177000050500000000000000002604220efe000005000000000000000000005b43"
        assertNull(TreadmillPacket.parse(good.replaceRange(116, 118, "00").chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
    }

    @Test fun longPacketsGiveExactStartAndMeasuredSteps() {
        var done: Session? = null
        val t = SessionTracker { done = it }
        val now = 100_000_000L
        // first a short packet (estimate), then the long one with the real time
        t.onPacket(pkt("663500089808980000069e00000000000000007a"), now)
        assertTrue(t.estimatedStart)
        t.onPacket(pkt("673c00089808980000069f00000000000000007a0031a6f058250c177000050500000000000000002604220efe000005000000000000000000005b43"), now + 500)
        assertFalse(t.estimatedStart)
        assertEquals(now + 500 - 3_254_000L, t.startMs)
        assertEquals(3838, t.steps)
        // a later short packet does not roll back the measured steps
        t.onPacket(pkt("663500089808980000069f00000000000000007a"), now + 1000)
        assertEquals(3838, t.steps)
        assertTrue(t.finishNow())
        assertTrue(done!!.stepsMeasured); assertFalse(done!!.estimatedStart)
        assertEquals(3254, done!!.durationS)
    }

    private fun long(kmh: Double, dist: Int, elapsedMs: Long, steps: Int) =
        TreadmillPacket(kmh, kmh, dist, 0, elapsedMs, steps)

    @Test fun leftoverStepsAreIgnoredOnFreshStart() {
        val t = SessionTracker { }
        // belt just started (2 s) but the step counter is still at 50
        t.onPacket(long(1.0, 0, 2_000, 50), 1_000_000)
        assertEquals(0, t.steps)
        t.onPacket(long(1.0, 10, 30_000, 72), 1_028_000)
        assertEquals(22, t.steps)
    }

    @Test fun delayedCounterResetIsHandled() {
        val t = SessionTracker { }
        t.onPacket(long(1.0, 0, 1_000, 50), 1_000_000)
        t.onPacket(long(1.0, 1, 2_000, 0), 1_001_000)   // the treadmill resets it later
        t.onPacket(long(1.0, 5, 10_000, 8), 1_009_000)
        assertEquals(8, t.steps)
    }

    @Test fun lateJoinKeepsTreadmillSteps() {
        val t = SessionTracker { }
        t.onPacket(long(2.2, 1695, 3_254_000, 3838), 10_000_000)
        assertEquals(3838, t.steps)
    }

    @Test fun stepLengthGrowsWithSpeed() {
        assertEquals(0.62, StepModel.stepLengthM(175, 3.0), 0.01)
        assertEquals(0.74, StepModel.stepLengthM(175, 5.0), 0.01)
        assertTrue(StepModel.stepLengthM(190, 4.0) > StepModel.stepLengthM(160, 4.0))
    }

    @Test fun heartRateOnlyDuringOpenSession() {
        var done: Session? = null
        val t = SessionTracker { done = it }
        t.onHeartRate(70, 0) // belt stopped: dropped
        for (s in 1..30) {
            t.onPacket(TreadmillPacket(2.0, 2.0, s * 55 / 100, 0), s * 1000L)
            if (s % 3 == 0) t.onHeartRate(90 + s, s * 1000L)
        }
        t.onDisconnect()
        t.onHeartRate(150, 40_000) // session closed: dropped
        val r = done!!
        assertEquals(10, r.hrSamples.size)
        assertEquals(120, r.maxBpm); assertEquals(106, r.avgBpm) // average of 93..120 in steps of 3
    }

    @Test fun snapshotMatchesSessionSoFar() {
        var done: Session? = null
        val t = SessionTracker { done = it }
        assertNull(t.snapshot())
        for (s in 0..40) t.onPacket(TreadmillPacket(2.0, 2.0, s * 55 / 100, 0), s * 1000L)
        val snap = t.snapshot()!!
        assertEquals(40, snap.durationS); assertEquals(22, snap.distanceM); assertEquals(41, snap.speedSamples.size)
        assertTrue(t.running) // a checkpoint does not close the session
        t.onDisconnect()
        assertEquals(snap.copy(speedSamples = done!!.speedSamples), done)
        assertNull(t.snapshot())
    }
}
