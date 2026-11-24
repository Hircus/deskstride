package io.github.deskstride

import org.junit.Assert.assertEquals
import org.junit.Test

class WearBridgeTest {
    @Test fun stateFormatMatchesWatchParser() {
        // same string parsed by PhoneStateTest in the :wear module
        assertEquals(
            "conn=1;run=1;pause=0;belt=1;kmh=2.2;target=2.5;time=754;steps=812;dist=430;max=6.0",
            WearBridge.stateOf(true, true, false, true, 2.2, 2.5, 754, 812, 430, 6.0),
        )
    }
}
