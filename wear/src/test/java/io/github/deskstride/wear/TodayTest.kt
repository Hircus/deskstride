package io.github.deskstride.wear

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TodayTest {
    private val day = LocalDate.of(2026, 11, 24)

    @Test fun showsTodayTotals() {
        val t = Today.of("2026-11-24", 3120, 2345, 35 * 60 + 20, false, day)
        assertEquals("2.35", t.km(Locale.US))
        assertEquals("2,35", t.km(Locale.ITALY))
        assertEquals(3120, t.steps)
    }

    @Test fun yesterdaysDataMeansNothingYet() {
        assertEquals(Today.EMPTY, Today.of("2026-11-23", 3120, 2345, 600, true, day))
        assertEquals(Today.EMPTY, Today.of(null, 1, 1, 1, false, day))
    }
}
