package com.example.airkast

import android.app.Application
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日付セレクタが日跨ぎ/復帰で更新されない問題の回帰テスト。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelBugfixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var downloadsDir: File
    private lateinit var client: AirkastClient
    private lateinit var viewModel: MainViewModel

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
    private val timeFmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)

    private fun at(s: String): Date = timeFmt.parse(s)!!
    private fun key(d: Date): String = dayFmt.format(d)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        downloadsDir = tempFolder.newFolder("downloads")

        client = mockk(relaxed = true)
        coEvery { client.auth1() } throws RuntimeException("no network in test")

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.getFloat(any(), any()) } answers { secondArg() }
        every { prefs.getInt(any(), any()) } answers { secondArg() }

        val app = mockk<AirkastApplication>(relaxed = true)
        every { app.airkastClient } returns client
        every { app.getSharedPreferences(any(), any()) } returns prefs
        every { app.getExternalFilesDir("downloads") } returns downloadsDir

        // PlayerService への接続 (Media3) は JVM テストでは行えないので何もしない
        viewModel = object : MainViewModel(app as Application) {
            override fun connectToPlayerService() {}
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ───────────── Bug 1: 日付リスト ─────────────

    @Test
    fun buildDateList_isSevenDaysEndingToday_ascending() {
        val list = buildDateList(at("20260301123000"))
        assertEquals(
            listOf("20260223", "20260224", "20260225", "20260226", "20260227", "20260228", "20260301"),
            list.map { key(it) }
        )
    }

    @Test
    fun refreshDateIfChanged_dayRollover_updatesListAndFollowsToday() {
        viewModel.clock = { at("20260110230000").time }
        viewModel.refreshDateIfChanged() // 起動時点の「今日」に合わせる
        viewModel.onDateSelected(at("20260110230000")) // ユーザーは「今日」を選択中
        assertEquals("20260110", key(viewModel.dateList.value.last()))

        viewModel.clock = { at("20260111000500").time } // 日跨ぎ
        viewModel.refreshDateIfChanged()

        assertEquals("20260111", key(viewModel.dateList.value.last()))
        assertEquals("20260105", key(viewModel.dateList.value.first()))
        assertEquals("20260111", key(viewModel.selectedDate.value))
    }

    @Test
    fun refreshDateIfChanged_sameDay_isNoOp() {
        viewModel.clock = { at("20260110100000").time }
        viewModel.refreshDateIfChanged()
        val listBefore = viewModel.dateList.value
        val selectedBefore = viewModel.selectedDate.value

        viewModel.clock = { at("20260110235959").time } // 同じ日の別時刻
        viewModel.refreshDateIfChanged()

        assertSame(listBefore, viewModel.dateList.value)
        assertSame(selectedBefore, viewModel.selectedDate.value)
    }

    @Test
    fun refreshDateIfChanged_keepsPastDaySelection_withoutRefetch() {
        viewModel.clock = { at("20260110100000").time }
        viewModel.refreshDateIfChanged()
        viewModel.onDateSelected(at("20260108100000")) // 過去日を選択 (放送局未選択なので取得はしない)
        val selected = viewModel.selectedDate.value

        viewModel.clock = { at("20260111000500").time }
        viewModel.refreshDateIfChanged()

        assertEquals("20260111", key(viewModel.dateList.value.last()))
        assertSame(selected, viewModel.selectedDate.value) // 選択は維持
    }

    @Test
    fun refreshDateIfChanged_selectionDroppedFromWindow_fallsBackToToday() {
        viewModel.clock = { at("20260110100000").time }
        viewModel.refreshDateIfChanged()
        viewModel.onDateSelected(at("20260104100000")) // 一番古い日 (7日窓の左端)

        viewModel.clock = { at("20260111000500").time } // 窓から外れる
        viewModel.refreshDateIfChanged()

        assertEquals("20260111", key(viewModel.selectedDate.value))
    }

    @Test
    fun refreshDateIfChanged_dayRollover_refetchesGuideForNewToday() {
        coEvery { client.getProgramGuide(any(), any()) } returns emptyList()
        viewModel.clock = { at("20260110230000").time }
        viewModel.refreshDateIfChanged()
        viewModel.onDateSelected(at("20260110230000"))
        viewModel.onStationSelected("TBS") // 取得: 20260110

        viewModel.clock = { at("20260111000500").time }
        viewModel.refreshDateIfChanged()
        viewModel.refreshDateIfChanged() // 2回目は日付が同じなので再取得しない

        coVerify(exactly = 1) { client.getProgramGuide("TBS", "20260110") }
        coVerify(exactly = 1) { client.getProgramGuide("TBS", "20260111") }
    }
}
