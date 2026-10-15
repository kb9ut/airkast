package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class FavoriteSyncTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private class MapStorage : TextStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private fun prog(station: String = "TBS", start: String = "20260124220000", end: String = "20260124230000") =
        AirkastProgram("$station-$start", station, start, end, "アフター6", "", "", null)

    private class FakeGuides(
        private val onRequest: (String, String) -> List<AirkastProgram>,
    ) : GuideSource {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun programs(stationId: String, date: String): List<AirkastProgram> {
            requests += stationId to date
            return onRequest(stationId, date)
        }
    }

    private class FakeDownloader(private val onDownload: (AirkastProgram) -> Unit = {}) : EpisodeDownloader {
        val downloaded = mutableListOf<AirkastProgram>()
        override suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit) {
            onDownload(program)
            onProgress(100)
            downloaded += program
        }
    }

    private val repo = FavoriteRepository(MapStorage()).also { it.add("TBS", "アフター6") }

    private fun sync(
        guides: GuideSource,
        downloader: EpisodeDownloader,
        downloaded: List<File> = emptyList(),
        claim: (AirkastProgram) -> Boolean = { true },
        release: (AirkastProgram) -> Unit = {},
    ) = FavoriteSync(repo, guides, downloader, { downloaded }, claim, release, { now })

    @Test
    fun recentDatesListsToday_andSevenPreviousDays() {
        assertEquals(
            listOf(
                "20260125", "20260124", "20260123", "20260122",
                "20260121", "20260120", "20260119", "20260118",
            ),
            recentDates(now),
        )
    }

    @Test
    fun downloadsNewEpisodeFoundInGuide() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        val report = sync(guides, downloader).run()

        assertEquals(listOf(prog()), downloader.downloaded)
        assertEquals(listOf(prog()), report.downloaded)
        assertTrue(report.failed.isEmpty())
        assertEquals(8, guides.requests.size)
        assertEquals(setOf("TBS"), guides.requests.map { it.first }.toSet())
    }

    @Test
    fun doesNothingWhenThereAreNoAutoDownloadFavorites() = runBlocking {
        repo.setAutoDownload("TBS", "アフター6", false)
        val guides = FakeGuides { _, _ -> listOf(prog()) }
        val downloader = FakeDownloader()

        val report = sync(guides, downloader).run()

        assertTrue(guides.requests.isEmpty())
        assertTrue(downloader.downloaded.isEmpty())
        assertFalse(report.allStationsFailed)
    }

    @Test
    fun skipsAlreadyDownloadedEpisode() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")

        sync(guides, downloader, downloaded = listOf(file)).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun skipsEpisodeThatIsBeingDownloadedManually() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        sync(guides, downloader, claim = { it.id != prog().id }).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun recordsFailureAndContinuesWithNextEpisode() = runBlocking {
        val first = prog(start = "20260123220000", end = "20260123230000")
        val second = prog(start = "20260124220000", end = "20260124230000")
        val guides = FakeGuides { _, date ->
            when (date) {
                "20260123" -> listOf(first)
                "20260124" -> listOf(second)
                else -> emptyList()
            }
        }
        val downloader = FakeDownloader { if (it == first) throw java.io.IOException("boom") }

        val report = sync(guides, downloader).run()

        assertEquals(listOf(first), report.failed)
        assertEquals(listOf(second), report.downloaded)
        assertEquals(mapOf(first.episodeKey to 1), repo.failures())
    }

    @Test
    fun successClearsEarlierFailureCount() = runBlocking {
        repo.recordFailure(prog().episodeKey)
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }

        sync(guides, FakeDownloader()).run()

        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun doesNotRetryEpisodeThatReachedMaxAttempts() = runBlocking {
        repeat(FavoriteMatcher.MAX_ATTEMPTS) { repo.recordFailure(prog().episodeKey) }
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        sync(guides, downloader).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun guideFailureForEveryStationIsReported_withoutCountingEpisodeFailures() = runBlocking {
        val guides = FakeGuides { _, _ -> throw java.io.IOException("offline") }

        val report = sync(guides, FakeDownloader()).run()

        assertTrue(report.allStationsFailed)
        assertEquals(1, report.stationsTotal)
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun partialGuideFailureIsNotAllStationsFailed() = runBlocking {
        val guides = FakeGuides { _, date ->
            if (date == "20260125") throw java.io.IOException("one day failed") else emptyList()
        }

        val report = sync(guides, FakeDownloader()).run()

        assertFalse(report.allStationsFailed)
    }

    @Test
    fun cancellationPropagatesAndIsNotCountedAsFailure() {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader { throw CancellationException("stopped") }

        assertFailsWith<CancellationException> { runBlocking { sync(guides, downloader).run() } }
        // 中断された試行も 1 回として数える
        assertEquals(mapOf(prog().episodeKey to 1), repo.failures())
    }

    @Test
    fun episodeInterruptedOnThreeConsecutiveRunsIsNotAttemptedOnTheFourth() {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val interrupting = FakeDownloader { throw CancellationException("stopped") }

        repeat(FavoriteMatcher.MAX_ATTEMPTS) {
            assertFailsWith<CancellationException> { runBlocking { sync(guides, interrupting).run() } }
        }
        val fourth = FakeDownloader()
        runBlocking { sync(guides, fourth).run() }

        assertTrue(fourth.downloaded.isEmpty())
        assertEquals(mapOf(prog().episodeKey to FavoriteMatcher.MAX_ATTEMPTS), repo.failures())
    }

    private val first = prog(start = "20260123220000", end = "20260123230000")
    private val second = prog(start = "20260124220000", end = "20260124230000")
    private val twoDayGuides = FakeGuides { _, date ->
        when (date) {
            "20260123" -> listOf(first)
            "20260124" -> listOf(second)
            else -> emptyList()
        }
    }

    @Test
    fun skipsCandidateThatStartsDownloadingManuallyDuringSync() = runBlocking {
        val busy = mutableSetOf<String>()
        // 1 件目のダウンロード中に、もう 1 件が手動でダウンロード開始された状況
        val downloader = FakeDownloader { program ->
            busy += listOf(first, second).first { it != program }.id
        }

        val report = sync(twoDayGuides, downloader, claim = { it.id !in busy }).run()

        assertEquals(1, downloader.downloaded.size)
        assertEquals(downloader.downloaded, report.downloaded)
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun skipsCandidateWhoseFileAppearedDuringSync() = runBlocking {
        val files = mutableListOf<File>()
        // 1 件目のダウンロード中に、もう 1 件のファイルが別経路で保存された状況
        val downloader = FakeDownloader { program ->
            val other = listOf(first, second).first { it != program }
            files += File("TBS_アフター6_${other.startTime}_${other.endTime}.m4a")
        }

        val report = sync(twoDayGuides, downloader, downloaded = files).run()

        assertEquals(1, downloader.downloaded.size)
        assertEquals(downloader.downloaded, report.downloaded)
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun releasesTheClaimAfterSuccessFailureAndCancellation() = runBlocking {
        val first = prog(start = "20260122220000", end = "20260122230000")
        val second = prog(start = "20260123220000", end = "20260123230000")
        val third = prog(start = "20260124220000", end = "20260124230000")
        val guides = FakeGuides { _, date ->
            when (date) {
                "20260122" -> listOf(first)
                "20260123" -> listOf(second)
                "20260124" -> listOf(third)
                else -> emptyList()
            }
        }
        val claimed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val downloader = FakeDownloader {
            when (it) {
                second -> throw java.io.IOException("boom")
                third -> throw CancellationException("stopped")
            }
        }

        assertFailsWith<CancellationException> {
            sync(
                guides, downloader,
                claim = { claimed += it.id; true },
                release = { released += it.id },
            ).run()
        }

        // 成功(first)・失敗(second)・キャンセル(third)のどれでも、予約した回は必ず解放される
        assertEquals(listOf(first.id, second.id, third.id), claimed)
        assertEquals(claimed, released)
    }

    @Test
    fun unclaimedEpisodeIsSkippedWithoutFailureOrRelease() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val released = mutableListOf<String>()

        val report = sync(guides, downloader, claim = { false }, release = { released += it.id }).run()

        assertTrue(downloader.downloaded.isEmpty())
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
        assertTrue(released.isEmpty())
    }

    @Test
    fun episodeThatBecameDownloadedAfterClaimingIsReleasedAndSkipped() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val released = mutableListOf<String>()
        // 予約を取るまでの間に別の処理が保存を終えた状況(予約後の再確認で見つかる)
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")
        var calls = 0
        val sync = FavoriteSync(
            repo, guides, downloader,
            downloadedFiles = { if (calls++ == 0) emptyList() else listOf(file) },
            claim = { true },
            release = { released += it.id },
            now = { now },
        )

        sync.run()

        assertTrue(downloader.downloaded.isEmpty())
        assertEquals(listOf(prog().id), released)
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun skipsRemainingEpisodesWhenAutoDownloadIsTurnedOffDuringSync() = runBlocking {
        // 1 件目のダウンロード中に、ユーザーが自動DL を OFF にした
        val downloader = FakeDownloader { repo.setAutoDownload("TBS", "アフター6", false) }
        val claimed = mutableListOf<String>()

        val report = sync(twoDayGuides, downloader, claim = { claimed += it.id; true }).run()

        // 実行中の 1 件目は完走し、2 件目は予約も失敗の記録もせずにスキップする
        assertEquals(listOf(first), downloader.downloaded)
        assertEquals(listOf(first.id), claimed)
        assertEquals(listOf(first), report.downloaded)
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun skipsRemainingEpisodesWhenTheFavoriteIsRemovedDuringSync() = runBlocking {
        val downloader = FakeDownloader { repo.remove("TBS", "アフター6") }
        val claimed = mutableListOf<String>()

        val report = sync(twoDayGuides, downloader, claim = { claimed += it.id; true }).run()

        assertEquals(listOf(first), downloader.downloaded)
        assertEquals(listOf(first.id), claimed)
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
    }
}
