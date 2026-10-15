package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestEpisodeTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private fun prog(
        station: String = "TBS",
        title: String = "アフター6",
        start: String = "20260124220000",
        end: String = "20260124230000",
    ) = AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val key = normalizeProgramTitle("アフター6")

    @Test
    fun picksTheMostRecentEpisodeThatHasEnded() {
        val older = prog(start = "20260123220000", end = "20260123230000")
        val newer = prog(start = "20260124220000", end = "20260124230000")

        assertEquals(newer, latestEpisode(listOf(older, newer), "TBS", key, now))
    }

    @Test
    fun ignoresEpisodesOfOtherStationsAndTitles() {
        val other = prog(station = "QRR", start = "20260124220000", end = "20260124230000")
        val otherTitle = prog(title = "別の番組", start = "20260124230000", end = "20260125000000")
        val mine = prog(start = "20260123220000", end = "20260123230000")

        assertEquals(mine, latestEpisode(listOf(other, otherTitle, mine), "TBS", key, now))
    }

    @Test
    fun matchesTitlesRegardlessOfWidthAndSpacing() {
        val program = prog(title = "ＡＢＣ　ラジオ")

        assertEquals(program, latestEpisode(listOf(program), "TBS", normalizeProgramTitle("ABC ラジオ"), now))
    }

    @Test
    fun skipsEpisodesStillOnAirOrEndedWithinTheMargin() {
        val onAir = prog(start = "20260125110000", end = "20260125121000")
        val justEnded = prog(start = "20260125105600", end = "20260125115600")
        val settled = prog(start = "20260124220000", end = "20260124230000")

        assertEquals(settled, latestEpisode(listOf(onAir, justEnded, settled), "TBS", key, now))
    }

    @Test
    fun returnsNullWhenThereIsNoMatchingEpisode() {
        assertNull(latestEpisode(emptyList(), "TBS", key, now))
        assertNull(latestEpisode(listOf(prog(station = "QRR")), "TBS", key, now))
    }

    @Test
    fun ignoresProgramsWithUnparsableTimes() {
        assertNull(latestEpisode(listOf(prog(start = "", end = "")), "TBS", key, now))
    }
}
