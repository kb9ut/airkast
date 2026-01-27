package com.example.airkast

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertTrue

class HlsDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsePlaylist_relativeUrls() {
        val playlistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10.0,
            segment1.ts
            #EXTINF:10.0,
            segment2.ts
        """.trimIndent()
        val playlistUrl = "http://example.com/stream/master.m3u8"
        val downloader = HlsDownloader(HttpClient(MockEngine { respond("") }))

        val segmentUrls = downloader.parsePlaylist(playlistContent, playlistUrl)

        assertEquals(2, segmentUrls.size)
        assertEquals("http://example.com/stream/segment1.ts", segmentUrls[0])
        assertEquals("http://example.com/stream/segment2.ts", segmentUrls[1])
    }

    @Test
    fun parsePlaylist_absoluteUrls() {
        val playlistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10.0,
            http://cdn.example.com/segment1.ts
            #EXTINF:10.0,
            http://cdn.example.com/segment2.ts
        """.trimIndent()
        val playlistUrl = "http://example.com/stream/master.m3u8"
        val downloader = HlsDownloader(HttpClient(MockEngine { respond("") }))

        val segmentUrls = downloader.parsePlaylist(playlistContent, playlistUrl)

        assertEquals(2, segmentUrls.size)
        assertEquals("http://cdn.example.com/segment1.ts", segmentUrls[0])
        assertEquals("http://cdn.example.com/segment2.ts", segmentUrls[1])
    }

    @Test
    fun downloadStream_success() = runTest {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=640x360
            media.m3u8
        """.trimIndent()

        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            segment1.ts
            #EXTINF:10,
            segment2.ts
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/stream/master.m3u8" -> respond(
                    content = masterPlaylist,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")
                )
                "http://example.com/stream/media.m3u8" -> respond(
                    content = mediaPlaylist,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")
                )
                "http://example.com/stream/segment1.ts" -> respond(
                    content = "segment1_data".toByteArray(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "video/mp2t")
                )
                "http://example.com/stream/segment2.ts" -> respond(
                    content = "segment2_data".toByteArray(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "video/mp2t")
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val downloader = HlsDownloader(HttpClient(mockEngine))
        val outputFile = tempFolder.newFile("output.ts")
        var lastProgress = 0f

        downloader.downloadStream("http://example.com/stream/media.m3u8", outputFile, "token") { progress ->
            lastProgress = progress
        }

        assertTrue(outputFile.exists())
        assertEquals("segment1_datasegment2_data", outputFile.readText())
        assertEquals(1f, lastProgress)
    }

    @Test
    fun downloadStream_failure_playlist_fetch_error() = runTest {
        val mockEngine = MockEngine {
            respond("", HttpStatusCode.InternalServerError)
        }
        val downloader = HlsDownloader(HttpClient(mockEngine))
        val outputFile = tempFolder.newFile("output.ts")

        try {
            downloader.downloadStream("http://example.com/stream/media.m3u8", outputFile, "token") { /* do nothing */ }
            Assert.fail("HlsDownloadException was not thrown")
        } catch (e: HlsDownloadException) {
            // success
        }
    }

    @Test
    fun downloadStream_failure_segment_fetch_error() = runTest {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            segment1.ts
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/stream/media.m3u8" -> respond(
                    content = mediaPlaylist,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")
                )
                "http://example.com/stream/segment1.ts" -> respond(
                    content = "",
                    status = HttpStatusCode.InternalServerError
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val downloader = HlsDownloader(HttpClient(mockEngine))
        val outputFile = tempFolder.newFile("output.ts")

        try {
            downloader.downloadStream("http://example.com/stream/media.m3u8", outputFile, "token") { /* do nothing */ }
            Assert.fail("HlsDownloadException was not thrown")
        } catch (e: HlsDownloadException) {
            // success
        }
    }

    @Test
    fun downloadStream_failure_no_segments() = runTest {
        val emptyPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/stream/media.m3u8" -> respond(
                    content = emptyPlaylist,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val downloader = HlsDownloader(HttpClient(mockEngine))
        val outputFile = tempFolder.newFile("output.ts")

        try {
            downloader.downloadStream("http://example.com/stream/media.m3u8", outputFile, "token") { /* do nothing */ }
            Assert.fail("HlsDownloadException was not thrown")
        } catch (e: HlsDownloadException) {
            // success
        }
    }
}
