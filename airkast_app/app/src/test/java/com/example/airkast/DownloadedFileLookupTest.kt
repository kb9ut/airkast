package com.example.airkast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadedFileLookupTest {

    private val program = AirkastProgram(
        "p", "TBS", "20260124220000", "20260124230000", "アフター6", "", "", null
    )

    @Test
    fun returnsTheMatchingFileForStationStartAndEnd() {
        val match = File("TBS_アフター6_20260124220000_20260124230000.m4a")
        val files = listOf(File("QRR_アフター6_20260124220000_20260124230000.m4a"), match)

        assertEquals(match, findDownloadedFile(files, program))
        assertTrue(isProgramDownloaded(files, program))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        val files = listOf(File("TBS_アフター6_20260123220000_20260123230000.m4a"))

        assertNull(findDownloadedFile(files, program))
        assertFalse(isProgramDownloaded(files, program))
    }
}
