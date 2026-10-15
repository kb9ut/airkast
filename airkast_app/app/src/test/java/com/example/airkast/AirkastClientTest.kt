package com.example.airkast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirkastClientTest {

    private val program = AirkastProgram(
        "prog1", "TBS", "20260125090000", "20260125100000", "title", "", "", null
    )

    @Test
    fun generateHlsUrl_usesProgramTimesAndComputesDuration() {
        val url = AirkastClient().generateHlsUrl(program, lsid = "abc")

        assertEquals(
            "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8" +
                "?station_id=TBS&l=3600&ft=20260125090000&to=20260125100000" +
                "&start_at=20260125090000&end_at=20260125100000&lsid=abc&type=b",
            url
        )
    }

    @Test
    fun generateHlsUrl_honoursExplicitChunkRange() {
        val url = AirkastClient().generateHlsUrl(
            program, startAt = "20260125090000", endAt = "20260125090500", lsid = "abc"
        )

        assertTrue(url.contains("&l=300&"))
        assertTrue(url.contains("&ft=20260125090000&to=20260125090500&"))
    }

    @Test
    fun generateNewLsid_isHyphenlessUuid() {
        val lsid = AirkastClient().generateNewLsid()

        assertEquals(32, lsid.length)
        assertTrue(lsid.none { it == '-' })
    }
}
