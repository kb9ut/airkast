package com.example.airkast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirkastProgramTest {

    private fun program(start: String, end: String) =
        AirkastProgram("id", "TBS", start, end, "title", "", "", null)

    @Test
    fun durationMs_isEndMinusStart() {
        assertEquals(3_600_000L, program("20260125090000", "20260125100000").durationMs)
    }

    @Test
    fun durationMs_isZeroForInvalidTimes() {
        assertEquals(0L, program("bad", "worse").durationMs)
    }

    @Test
    fun formattedDisplayTime_andDate() {
        val p = program("20260125090000", "20260125103000")

        assertEquals("09:00 - 10:30", p.formattedDisplayTime)
        assertEquals("01/25", p.formattedDisplayDate)
    }

    @Test
    fun formattedDisplay_fallbacksForInvalidTimes() {
        val p = program("bad", "bad")

        assertEquals("Time N/A", p.formattedDisplayTime)
        assertEquals("", p.formattedDisplayDate)
    }

    @Test
    fun isDownloadable_onlyAfterProgramEnded() {
        assertTrue(program("20000101000000", "20000101010000").isDownloadable)
        assertFalse(program("20990101000000", "20990101010000").isDownloadable)
    }
}
