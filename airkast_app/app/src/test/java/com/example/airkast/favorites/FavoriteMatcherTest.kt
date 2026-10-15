package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavoriteMatcherTest {

    private fun ts(s: String) = radikoTimestampFormat().parse(s)!!.time

    // 2026-01-25 12:00:00 を「現在」とする
    private val now = ts("20260125120000")

    private fun prog(
        station: String = "TBS",
        title: String = "アフター6",
        start: String = "20260124220000",
        end: String = "20260124230000",
    ) = AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val fav = Favorite("TBS", "アフター6")

    private fun find(
        programs: List<AirkastProgram>,
        favorites: List<Favorite> = listOf(fav),
        downloaded: List<File> = emptyList(),
        failures: Map<String, Int> = emptyMap(),
    ) = FavoriteMatcher.findNewEpisodes(favorites, programs, now, downloaded, failures)

    @Test
    fun matchesByStationAndTitle() {
        val result = find(listOf(prog(), prog(station = "QRR"), prog(title = "別の番組")))

        assertEquals(listOf(prog()), result)
    }

    @Test
    fun normalizesWidthAndWhitespaceInTitles() {
        val favorite = Favorite("TBS", "ＡＢＣ　ラジオ")

        val result = find(listOf(prog(title = "ABC ラジオ")), listOf(favorite))

        assertEquals(1, result.size)
    }

    @Test
    fun ignoresFavoritesWithAutoDownloadOff() {
        val result = find(listOf(prog()), listOf(fav.copy(autoDownload = false)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeStillOnAir() {
        val result = find(listOf(prog(start = "20260125110000", end = "20260125121000")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeThatEndedLessThanMarginAgo() {
        // 終了 11:56 → 現在 12:00 の 5 分前(11:55)より後なのでまだ取得しない
        val result = find(listOf(prog(start = "20260125105600", end = "20260125115600")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun includesEpisodeThatEndedExactlyMarginAgo() {
        val result = find(listOf(prog(start = "20260125105500", end = "20260125115500")))

        assertEquals(1, result.size)
    }

    @Test
    fun excludesEpisodeStartedBeforeTimeFreeWindow() {
        // 現在の 7 日前は 2026-01-18 12:00:00
        val tooOld = prog(start = "20260118115900", end = "20260118125900")
        val boundary = prog(start = "20260118120000", end = "20260118130000")

        val result = find(listOf(tooOld, boundary))

        assertEquals(listOf(boundary), result)
    }

    @Test
    fun skipsAlreadyDownloadedEpisode() {
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")

        val result = find(listOf(prog()), downloaded = listOf(file))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeThatFailedMaxAttempts() {
        val key = "TBS:20260124220000"

        assertEquals(1, find(listOf(prog()), failures = mapOf(key to 2)).size)
        assertTrue(find(listOf(prog()), failures = mapOf(key to 3)).isEmpty())
    }

    @Test
    fun removesDuplicatesAndSortsByStartTime() {
        val late = prog(start = "20260124220000", end = "20260124230000")
        val early = prog(start = "20260123220000", end = "20260123230000")

        val result = find(listOf(late, early, late))

        assertEquals(listOf(early, late), result)
    }

    @Test
    fun ignoresProgramsWithUnparsableTimes() {
        val result = find(listOf(prog(start = "", end = "")))

        assertTrue(result.isEmpty())
    }
}
