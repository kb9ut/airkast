package com.example.airkast

import com.example.airkast.service.HlsDownloadService
import com.example.airkast.service.HlsDownloadService.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsDownloadServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun program(id: String, title: String = "Show $id") = AirkastProgram(
        id = id,
        stationId = "TBS",
        startTime = "20260125090000",
        endTime = "20260125100000",
        title = title,
        description = "",
        performer = "",
        imageUrl = null,
    )

    private fun completed(id: String) = DownloadStatus.Completed(program(id), File("$id.m4a"))

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

    @Test
    fun partialFiles_listsFinalFileAndItsIntermediates() {
        val final = File("/tmp/downloads/TBS_Show_20260125090000_20260125100000.m4a")

        val names = HlsDownloadService.partialFiles(final).map { it.name }

        assertEquals(
            listOf(
                "TBS_Show_20260125090000_20260125100000.m4a",
                "TBS_Show_20260125090000_20260125100000.m4a.adts",
                "TBS_Show_20260125090000_20260125100000.m4a.tmp_mux",
                "TBS_Show_20260125090000_20260125100000.m4a.raw",
            ),
            names,
        )
    }

    // --- 状態の分類 ---

    @Test
    fun isFinished_isTrueOnlyForCompletedErrorAndCanceled() {
        val p = program("a")
        assertTrue(DownloadStatus.Completed(p, File("a.m4a")).isFinished())
        assertTrue(DownloadStatus.Error(p, "boom").isFinished())
        assertTrue(DownloadStatus.Canceled(p).isFinished())
        assertFalse(DownloadStatus.Pending(p).isFinished())
        assertFalse(DownloadStatus.InProgress(p, 10).isFinished())
        assertFalse(DownloadStatus.Muxing(p, 10).isFinished())
    }

    @Test
    fun isActive_isTheComplementOfIsFinished() {
        val p = program("a")
        assertTrue(DownloadStatus.Pending(p).isActive())
        assertTrue(DownloadStatus.InProgress(p, 10).isActive())
        assertTrue(DownloadStatus.Muxing(p, 10).isActive())
        assertFalse(DownloadStatus.Completed(p, File("a.m4a")).isActive())
        assertFalse(DownloadStatus.Error(p, "boom").isActive())
        assertFalse(DownloadStatus.Canceled(p).isActive())
    }

    // --- 状態マップの整理 ---

    @Test
    fun pruneStates_keepsEverythingAtOrBelowTheLimit() {
        val states = (1..HlsDownloadService.MAX_TRACKED_STATES).associate { "id$it" to completed("id$it") }

        assertEquals(states, HlsDownloadService.pruneStates(states))
    }

    @Test
    fun pruneStates_overLimitDropsOldestFinishedAndKeepsNewestFinished() {
        val max = HlsDownloadService.MAX_TRACKED_STATES
        val kept = HlsDownloadService.KEPT_FINISHED_STATES
        // 古い順に max + 1 件の完了済み状態
        val states = (1..max + 1).associate { "id$it" to completed("id$it") }

        val pruned = HlsDownloadService.pruneStates(states)

        assertEquals(kept, pruned.size)
        assertEquals((max + 1 - kept + 1..max + 1).map { "id$it" }, pruned.keys.toList())
    }

    @Test
    fun pruneStates_neverDropsActiveStates() {
        val max = HlsDownloadService.MAX_TRACKED_STATES
        val active = (1..5).associate { "run$it" to DownloadStatus.InProgress(program("run$it"), it) }
        val finished = (1..max).associate { "id$it" to completed("id$it") }

        val pruned = HlsDownloadService.pruneStates(active + finished)

        assertTrue(pruned.keys.containsAll(active.keys))
        assertEquals(5 + HlsDownloadService.KEPT_FINISHED_STATES, pruned.size)
    }

    @Test
    fun pruneStates_doesNotDropWhenFinishedCountIsWithinKeepLimit() {
        val max = HlsDownloadService.MAX_TRACKED_STATES
        // 上限超過だが、ほとんどが進行中で完了済みは KEPT 以下 → 何も消さない
        val active = (1..max).associate { "run$it" to DownloadStatus.Pending(program("run$it")) }
        val finished = (1..3).associate { "id$it" to completed("id$it") }
        val states = active + finished

        assertEquals(states, HlsDownloadService.pruneStates(states))
    }

    @Test
    fun withoutFinished_removesOnlyFinishedStates() {
        val running = DownloadStatus.InProgress(program("run"), 50)
        val states = mapOf(
            "run" to running,
            "done" to completed("done"),
            "err" to DownloadStatus.Error(program("err"), "x"),
            "cancel" to DownloadStatus.Canceled(program("cancel")),
        )

        assertEquals(mapOf("run" to running), HlsDownloadService.withoutFinished(states))
    }

    // --- 通知の概要 ---

    @Test
    fun describeActive_formatsEachActiveStatus() {
        val lines = HlsDownloadService.describeActive(
            listOf(
                DownloadStatus.Pending(program("a", "A")),
                DownloadStatus.InProgress(program("b", "B"), 42),
                DownloadStatus.Muxing(program("c", "C"), 7),
            )
        )

        assertEquals(listOf("A: 待機中", "B: DL 42%", "C: 変換 7%"), lines)
    }

    @Test
    fun describeActive_skipsFinishedStatuses() {
        val lines = HlsDownloadService.describeActive(
            listOf(
                completed("a"),
                DownloadStatus.Error(program("b"), "x"),
                DownloadStatus.Canceled(program("c")),
            )
        )

        assertTrue(lines.isEmpty())
    }

    // --- 失敗・キャンセル時の掃除 ---

    @Test
    fun deleteAll_removesFinalFileAndEveryIntermediate() {
        val final = File(tmp.root, "TBS_Show_20260125090000_20260125100000.m4a")
        val files = HlsDownloadService.partialFiles(final)
        files.forEach { it.writeText("x") }
        val unrelated = File(tmp.root, "other.m4a").apply { writeText("x") }

        HlsDownloadService.deleteAll(files)

        assertTrue(files.none { it.exists() })
        assertTrue(unrelated.exists())
    }

    @Test
    fun deleteAll_toleratesMissingFiles() {
        val final = File(tmp.root, "TBS_Show_20260125090000_20260125100000.m4a")
        // 中間ファイルだけが残っている (最終ファイルは未生成) 状態
        File(final.absolutePath + ".adts").writeText("x")

        HlsDownloadService.deleteAll(HlsDownloadService.partialFiles(final))

        assertTrue(HlsDownloadService.partialFiles(final).none { it.exists() })
    }
}
