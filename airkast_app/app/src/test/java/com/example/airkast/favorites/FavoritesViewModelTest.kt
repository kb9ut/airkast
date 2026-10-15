package com.example.airkast.favorites

import android.app.Application
import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private class MapStorage : TextStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakeControl : FavoriteSyncControl {
        val updates = mutableListOf<Boolean>()
        var syncNowCount = 0
        override fun update(enabled: Boolean) {
            updates += enabled
        }

        override fun syncNow() {
            syncNowCount++
        }
    }

    private class FakeGuides(
        private val onRequest: (String, String) -> List<AirkastProgram>,
    ) : GuideSource {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun programs(stationId: String, date: String): List<AirkastProgram> {
            requests += stationId to date
            return onRequest(stationId, date)
        }
    }

    /** 番組表の取得を [gate] が完了するまで止めるフェイク(取得中の状態を作る)。 */
    private class GatedGuides : GuideSource {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun programs(stationId: String, date: String): List<AirkastProgram> {
            requests += stationId to date
            gate.await()
            return emptyList()
        }
    }

    private fun prog(station: String = "TBS", title: String = "アフター6", start: String = "20260124220000", end: String = "20260124230000") =
        AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val storage = MapStorage()
    private val repository = FavoriteRepository(storage)
    private val control = FakeControl()

    private fun viewModel(guides: GuideSource) =
        FavoritesViewModel(mockk<Application>(relaxed = true), repository, control, guides) { now }

    private val defaultGuides = FakeGuides { station, date ->
        if (date == "20260124") listOf(prog(station = station)) else emptyList()
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun addingAFavoriteSavesItSchedulesSyncAndTriggersAnImmediateRun() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "アフター6")

        assertTrue(repository.isFavorite("TBS", "アフター6"))
        assertTrue(vm.isFavorite("TBS", "アフター6"))
        assertEquals(listOf(true), control.updates)
        assertEquals(1, control.syncNowCount)
    }

    @Test
    fun addingTheSameShowAgainWithDifferentWidthDoesNothing() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "ＡＢＣ　ラジオ")

        vm.addFavorite("TBS", "ABC ラジオ")

        assertEquals(1, repository.favorites().size)
        assertEquals(1, control.syncNowCount)
        assertEquals(setOf(favoriteKeyOf("TBS", "ABC ラジオ")), vm.favoriteKeys.value)
    }

    @Test
    fun blankTitlesAreIgnored() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "  ")

        assertTrue(repository.favorites().isEmpty())
        assertTrue(control.updates.isEmpty())
    }

    @Test
    fun removingTheLastFavoriteUnschedulesWithoutSyncingNow() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "アフター6")
        control.updates.clear()

        vm.removeFavorite("TBS", "アフター6")

        assertEquals(listOf(false), control.updates)
        assertEquals(1, control.syncNowCount)
        assertTrue(vm.rows.value.isEmpty())
        assertTrue(vm.favoriteKeys.value.isEmpty())
    }

    @Test
    fun togglingAutoDownloadUpdatesTheScheduleAndOnlySyncsWhenTurnedOn() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "アフター6")
        control.updates.clear()
        val syncsAfterAdd = control.syncNowCount

        vm.setAutoDownload("TBS", "アフター6", false)
        assertEquals(listOf(false), control.updates)
        assertEquals(syncsAfterAdd, control.syncNowCount)

        vm.setAutoDownload("TBS", "アフター6", true)
        assertEquals(listOf(false, true), control.updates)
        assertEquals(syncsAfterAdd + 1, control.syncNowCount)
    }

    @Test
    fun toggleFavoriteAddsThenRemoves() {
        val vm = viewModel(defaultGuides)

        vm.toggleFavorite("TBS", "アフター6")
        assertTrue(vm.isFavorite("TBS", "アフター6"))

        vm.toggleFavorite("TBS", "アフター6")
        assertFalse(vm.isFavorite("TBS", "アフター6"))
    }

    @Test
    fun rowsShowTheLatestEpisode() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "アフター6")

        val row = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.LOADED, row.status)
        assertEquals(prog().id, row.latest?.id)
    }

    @Test
    fun rowShowsNoRecentEpisodeWhenTheGuideHasNone() {
        val vm = viewModel(FakeGuides { _, _ -> emptyList() })

        vm.addFavorite("TBS", "アフター6")

        val row = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.NO_RECENT_EPISODE, row.status)
        assertNull(row.latest)
    }

    @Test
    fun guideFailureOfOneStationOnlyAffectsItsOwnRows() {
        val guides = FakeGuides { station, date ->
            if (station == "QRR") throw java.io.IOException("offline")
            if (date == "20260124") listOf(prog(station = station)) else emptyList()
        }
        val vm = viewModel(guides)

        vm.addFavorite("TBS", "アフター6")
        vm.addFavorite("QRR", "アフター6")

        val byStation = vm.rows.value.associateBy { it.favorite.stationId }
        assertEquals(FavoriteRowStatus.LOADED, byStation.getValue("TBS").status)
        assertEquals(FavoriteRowStatus.ERROR, byStation.getValue("QRR").status)
    }

    @Test
    fun addingAnotherFavoriteOnlyFetchesTheGuideOfThatStation() {
        val guides = FakeGuides { _, _ -> emptyList() }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "A")
        val tbsRequests = guides.requests.count { it.first == "TBS" }
        assertEquals(8, tbsRequests)

        vm.addFavorite("QRR", "B")

        assertEquals(8, guides.requests.count { it.first == "TBS" })
        assertEquals(8, guides.requests.count { it.first == "QRR" })
    }

    @Test
    fun forcedRefreshFetchesAgain() {
        val guides = FakeGuides { _, _ -> emptyList() }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "A")

        vm.refresh(force = true)

        assertEquals(16, guides.requests.count { it.first == "TBS" })
    }

    @Test
    fun forcedRefreshWhileAnotherForcedRefreshIsRunningIsIgnored() {
        val guides = GatedGuides()
        val vm = viewModel(guides)
        repository.add("TBS", "A") // VM を通さずに追加(取得は起動しない)

        vm.refresh(force = true) // 最初のリクエストで止まる
        vm.refresh(force = true) // 実行中なので無視される
        guides.gate.complete(Unit)

        assertEquals(8, guides.requests.count { it.first == "TBS" })
    }

    @Test
    fun forcedRefreshAfterTheFirstOneFinishesFetchesAgain() {
        val guides = GatedGuides()
        val vm = viewModel(guides)
        repository.add("TBS", "A")
        vm.refresh(force = true)
        guides.gate.complete(Unit)
        assertEquals(8, guides.requests.count { it.first == "TBS" })

        vm.refresh(force = true)

        assertEquals(16, guides.requests.count { it.first == "TBS" })
    }

    @Test
    fun addingAFavoriteDuringAForcedRefreshStillFetchesItsStation() {
        val guides = GatedGuides()
        val vm = viewModel(guides)
        repository.add("TBS", "A")
        vm.refresh(force = true) // TBS の最初のリクエストで止まる

        vm.addFavorite("QRR", "B") // 強制更新中でも、追加分の取得(非強制)は走る
        guides.gate.complete(Unit)

        assertEquals(8, guides.requests.count { it.first == "QRR" })
        assertEquals(
            FavoriteRowStatus.NO_RECENT_EPISODE,
            vm.rows.value.first { it.favorite.stationId == "QRR" }.status,
        )
    }

    @Test
    fun forcedRefreshThatFailsEverywhereKeepsTheCachedRow() {
        var offline = false
        val guides = FakeGuides { station, date ->
            if (offline) throw java.io.IOException("offline")
            if (date == "20260124") listOf(prog(station = station)) else emptyList()
        }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "アフター6")
        val before = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.LOADED, before.status)

        offline = true
        vm.refresh(force = true)

        val after = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.LOADED, after.status)
        assertEquals(before.latest, after.latest)
    }

    @Test
    fun partialFailureThatWouldYieldAnOlderEpisodeKeepsTheCachedNewerOne() {
        var degraded = false
        val older = prog(start = "20260123220000", end = "20260123230000")
        val guides = FakeGuides { station, date ->
            when {
                // 2 回目は最新回の日(24 日)だけ取得に失敗し、23 日の古い回しか見つからない
                degraded && date == "20260124" -> throw java.io.IOException("flaky")
                degraded && date == "20260123" -> listOf(older)
                !degraded && date == "20260124" -> listOf(prog(station = station))
                else -> emptyList()
            }
        }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "アフター6")
        assertEquals(prog().id, vm.rows.value.single().latest?.id)

        degraded = true
        vm.refresh(force = true)

        assertEquals(prog().id, vm.rows.value.single().latest?.id)
    }

    @Test
    fun partialFailureThatFindsANewerEpisodeUpdatesTheRow() {
        var second = false
        val newer = prog(start = "20260125000000", end = "20260125010000")
        val guides = FakeGuides { station, date ->
            when {
                second && date == "20260123" -> throw java.io.IOException("flaky")
                second && date == "20260125" -> listOf(newer)
                date == "20260124" -> listOf(prog(station = station))
                else -> emptyList()
            }
        }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "アフター6")

        second = true
        vm.refresh(force = true)

        assertEquals(newer.id, vm.rows.value.single().latest?.id)
    }
}
