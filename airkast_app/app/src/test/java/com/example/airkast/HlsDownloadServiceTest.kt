package com.example.airkast

import com.example.airkast.service.HlsDownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsDownloadServiceTest {

    @Test
    fun parseFileName_currentFormat() {
        val parsed = HlsDownloadService.parseFileName("TBS_Some_Show_20260125090000_20260125100000.m4a")!!

        assertEquals("TBS", parsed.stationId)
        assertEquals("Some_Show", parsed.title)
        assertEquals("20260125090000", parsed.startTime)
        assertEquals("20260125100000", parsed.endTime)
    }

    @Test
    fun parseFileName_legacyFormatWithoutStation() {
        val parsed = HlsDownloadService.parseFileName("Show_20260125090000_20260125100000.m4a")!!

        assertEquals("", parsed.stationId)
        assertEquals("Show", parsed.title)
        assertEquals("20260125090000", parsed.startTime)
    }

    @Test
    fun parseFileName_oldestFormatHasNoTimes() {
        val parsed = HlsDownloadService.parseFileName("prog1_Title.m4a")!!

        assertEquals("prog1", parsed.stationId)
        assertEquals("Title", parsed.title)
        assertEquals("", parsed.startTime)
        assertEquals("", parsed.endTime)
    }

    @Test
    fun parseFileName_returnsNullForUnparsableName() {
        assertNull(HlsDownloadService.parseFileName("single.m4a"))
    }
}
