package io.github.deskstride.wear

import org.junit.Assert.*
import org.junit.Test

class PhoneStateTest {
    // same string checked in WearBridgeTest of the phone app
    private val sample = "conn=1;run=1;pause=0;belt=1;kmh=2.2;target=2.5;time=754;steps=812;dist=430;max=6.0"

    @Test fun parsesPhoneState() {
        val s = PhoneState.parse(sample)
        assertTrue(s.connected && s.running && s.belt); assertFalse(s.paused)
        assertEquals(2.2, s.kmh, 1e-9); assertEquals(2.5, s.targetKmh, 1e-9)
        assertEquals(754, s.elapsedS); assertEquals(812, s.steps); assertEquals(6.0, s.maxKmh, 1e-9)
    }

    @Test fun toleratesGarbage() {
        val s = PhoneState.parse("conn=1;broken;kmh=x")
        assertTrue(s.connected); assertEquals(0.0, s.kmh, 0.0); assertEquals(6.0, s.maxKmh, 0.0)
    }
}
