package com.example.airkast

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ExperimentalCoroutinesApi
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private val mockApplication: Application = mockk(relaxed = true)
    private val mockDownloadManager: DownloadManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var mockCacheDir: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockCacheDir = tempFolder.newFolder()
        every { mockApplication.externalCacheDir } returns mockCacheDir
    }

    @Test
    fun authenticateAndFetchStations_success() = runTest {
        coEvery { mockDownloadManager.performAuthentication(any()) } returns Unit
        coEvery { mockDownloadManager.getStations(any()) } returns listOf(AirkastStation("JP13", "Tokyo FM"))
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.authenticateAndFetchStations()
        advanceUntilIdle()

        val stationUiState = viewModel.stationUiState.first()
        assertTrue(stationUiState is UiState.Success)
        assertEquals(1, (stationUiState as UiState.Success).data.size)
        assertEquals("Tokyo FM", stationUiState.data[0].name)
        assertNotNull(viewModel.userMessage.first())
    }

    @Test
    fun authenticateAndFetchStations_failure() = runTest {
        coEvery { mockDownloadManager.performAuthentication(any()) } throws Auth1Exception("Auth failed")
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.authenticateAndFetchStations()
        advanceUntilIdle()

        val stationUiState = viewModel.stationUiState.first()
        assertTrue(stationUiState is UiState.Error)
        assertTrue((stationUiState as UiState.Error).error is Auth1Exception)
        assertEquals("認証または放送局の取得に失敗: Auth failed", viewModel.userMessage.first())
    }

    @Test
    fun onStationSelected_fetchesProgramGuide() = runTest {
        coEvery { mockDownloadManager.getProgramGuide(any(), any()) } returns listOf(
            AirkastProgram("prog1", "20260125090000", "20260125100000", "Title1", "Desc1", "Pfm1", null)
        )
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.onStationSelected("JP13")
        advanceUntilIdle()

        val programGuideUiState = viewModel.programGuideUiState.first()
        assertTrue(programGuideUiState is UiState.Success)
        assertEquals(1, (programGuideUiState as UiState.Success).data.size)
        assertEquals("Title1", programGuideUiState.data[0].title)
    }

    @Test
    fun fetchProgramGuide_failure() = runTest {
        coEvery { mockDownloadManager.getProgramGuide(any(), any()) } throws ProgramGuideException("Guide failed")
        viewModel = MainViewModel(mockApplication, mockDownloadManager)
        
        viewModel.onStationSelected("JP13") // This will trigger fetchProgramGuide
        advanceUntilIdle()

        val programGuideUiState = viewModel.programGuideUiState.first()
        assertTrue(programGuideUiState is UiState.Error)
        assertTrue((programGuideUiState as UiState.Error).error is ProgramGuideException)
        assertEquals("番組表の取得に失敗: Guide failed", viewModel.userMessage.first())
    }

    @Test
    fun onDateSelected_updatesSelectedDateAndFetchesProgramGuide() = runTest {
        coEvery { mockDownloadManager.getProgramGuide(any(), any()) } returns emptyList()
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.onStationSelected("JP13") // Select a station first
        advanceUntilIdle() // Let program guide fetch
        
        val newDate = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).parse("20260120")!!
        viewModel.onDateSelected(newDate)
        advanceUntilIdle()

        assertEquals(newDate, viewModel.selectedDate.first())
        // Verify fetchProgramGuide was called with the new date
        coEvery { mockDownloadManager.getProgramGuide(eq("JP13"), eq("20260120")) } // Check interaction
    }

    @Test
    fun onDownloadProgram_queuesDownload() = runTest {
        viewModel = MainViewModel(mockApplication, mockDownloadManager)
        val program = AirkastProgram("prog1", "ft", "to", "title", "desc", "pfm", null)
        viewModel.onDownloadProgram(program)
        advanceUntilIdle()

        verify { mockDownloadManager.queueDownload(program) } // Verify interaction
        assertEquals("ダウンロードキューに追加しました: title", viewModel.userMessage.first())
    }

    @Test
    fun onScanForDownloads_updatesDownloadedFiles() = runTest {
        val file1 = tempFolder.newFile("file1.ts")
        file1.setLastModified(2000)
        val file2 = tempFolder.newFile("file2.ts")
        file2.setLastModified(1000)
        
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.onScanForDownloads()
        advanceUntilIdle()

        val downloadedFiles = viewModel.downloadedFiles.first()
        assertEquals(2, downloadedFiles.size)
        assertEquals("file1.ts", downloadedFiles[0].name) // Sorted by lastModified descending
        assertEquals("file2.ts", downloadedFiles[1].name)
    }

    @Test
    fun onDeleteFile_success() = runTest {
        val file = tempFolder.newFile("test_file.ts")
        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.onDeleteFile(file)
        advanceUntilIdle()

        assertEquals("削除しました: test_file.ts", viewModel.userMessage.first())
        assertTrue(viewModel.downloadedFiles.first().isEmpty())
    }

    @Test
    fun onDeleteFile_failure() = runTest {
        val file = tempFolder.newFile("test_file.ts")
        file.setReadOnly()

        viewModel = MainViewModel(mockApplication, mockDownloadManager)

        viewModel.onDeleteFile(file)
        advanceUntilIdle()

        assertEquals("削除に失敗: test_file.ts", viewModel.userMessage.first())
    }
}