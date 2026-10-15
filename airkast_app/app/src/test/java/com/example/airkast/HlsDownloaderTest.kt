package com.example.airkast

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsDownloaderTest {

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
    fun parseMediaPlaylistUrl_picksLineAfterStreamInf() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000
            media.m3u8
        """.trimIndent()
        val downloader = HlsDownloader(HttpClient(MockEngine { respond("") }))

        assertEquals("media.m3u8", downloader.parseMediaPlaylistUrl(master))
    }

    @Test
    fun parseMediaPlaylistUrl_fallsBackToFirstHttpLine() {
        val master = """
            #EXTM3U
            https://example.com/media.m3u8
        """.trimIndent()
        val downloader = HlsDownloader(HttpClient(MockEngine { respond("") }))

        assertEquals("https://example.com/media.m3u8", downloader.parseMediaPlaylistUrl(master))
    }

    @Test
    fun parseMediaPlaylistUrl_returnsNullWhenNoUrl() {
        val downloader = HlsDownloader(HttpClient(MockEngine { respond("") }))

        assertNull(downloader.parseMediaPlaylistUrl("#EXTM3U\n#EXT-X-VERSION:3"))
    }

    @Test
    fun parseAdtsHeader_readsStereo44100Frame() {
        // AAC-LC (profile 1), 44.1kHz (index 4), 2ch, frameLength = 0x123
        // header[2]: profile(2) | idx(4) | private(1) | chan hi(1) = 01 0100 0 0 = 0x50
        // header[3]: chan lo(2) | ... | len hi(2) = 10 0000 00 | (0x123 >> 11) = 0x80
        // header[4]: len mid(8) = (0x123 >> 3) & 0xFF = 0x24
        // header[5]: len lo(3) | fullness hi(5) = (0x123 & 7) << 5 = 0x60
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x24, 0x60, 0x00)

        val adts = parseAdtsHeader(header)

        assertEquals(1, adts.profile)
        assertEquals(4, adts.sampleRateIdx)
        assertEquals(2, adts.channelConfig)
        assertEquals(0x123, adts.frameLength)
    }

    @Test
    fun buildAudioSpecificConfig_matchesAacLc44100Stereo() {
        // AudioSpecificConfig for AAC-LC / 44.1kHz / stereo is the well-known 0x12 0x10
        val csd = buildAudioSpecificConfig(profile = 1, sampleRateIdx = 4, channelConfig = 2)

        assertEquals(listOf<Byte>(0x12, 0x10), csd.toList())
    }
}
