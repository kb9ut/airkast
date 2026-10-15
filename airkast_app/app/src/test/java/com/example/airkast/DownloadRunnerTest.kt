package com.example.airkast

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class DownloadRunnerTest {

    private fun program(start: String, end: String) =
        AirkastProgram("id", "TBS", start, end, "title", "", "", null)

    @Test
    fun run_rejectsProgramWhoseEndIsNotAfterStart() {
        val runner = DownloadRunner(AirkastClient())

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                runner.run(
                    program("20260125100000", "20260125100000"),
                    File("unused.m4a"),
                )
            }
        }
    }

    @Test
    fun run_rejectsUnparsableTimes() {
        val runner = DownloadRunner(AirkastClient())

        assertFailsWith<IllegalArgumentException> {
            runBlocking { runner.run(program("", ""), File("unused.m4a")) }
        }
    }
}
