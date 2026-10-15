package com.example.airkast

import com.example.airkast.service.HlsDownloadService.DownloadStatus
import com.example.airkast.ui.ProgramDownloadState
import com.example.airkast.ui.resolveProgramDownloadState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProgramDownloadStateTest {
    private val program = AirkastProgram(
        id = "p1", stationId = "TBS", startTime = "20250101050000", endTime = "20250101060000",
        title = "t", description = "", performer = "", imageUrl = null,
    )

    @Test
    fun noStatusAndNoFile_isIdle() {
        assertEquals(ProgramDownloadState.Idle, resolveProgramDownloadState(null, false))
    }

    @Test
    fun noStatusButFile_isCompleted() {
        assertEquals(ProgramDownloadState.Completed, resolveProgramDownloadState(null, true))
    }

    @Test
    fun activeStatuses_mapToProgress() {
        assertEquals(ProgramDownloadState.Queued, resolveProgramDownloadState(DownloadStatus.Pending(program), false))
        assertEquals(
            ProgramDownloadState.Downloading(42),
            resolveProgramDownloadState(DownloadStatus.InProgress(program, 42), false),
        )
        assertEquals(
            ProgramDownloadState.Converting(80),
            resolveProgramDownloadState(DownloadStatus.Muxing(program, 80), false),
        )
    }

    @Test
    fun completedStatusLeftInMap_isNotASpinner() {
        assertEquals(
            ProgramDownloadState.Completed,
            resolveProgramDownloadState(DownloadStatus.Completed(program, File("x.m4a")), false),
        )
    }

    @Test
    fun errorAndCanceled() {
        assertEquals(
            ProgramDownloadState.Failed("boom"),
            resolveProgramDownloadState(DownloadStatus.Error(program, "boom"), false),
        )
        assertEquals(ProgramDownloadState.Idle, resolveProgramDownloadState(DownloadStatus.Canceled(program), false))
        assertEquals(ProgramDownloadState.Completed, resolveProgramDownloadState(DownloadStatus.Canceled(program), true))
    }
}
