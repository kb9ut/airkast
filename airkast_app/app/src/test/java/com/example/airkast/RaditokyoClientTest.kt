package com.example.airkast

import android.content.Context
import android.content.res.AssetManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest=org.robolectric.annotation.Config.NONE, sdk = [34])
class AirkastClientTest {

    private val mockContext = mockk<Context>()
    private val mockAssetManager = mockk<AssetManager>()

    @Before
    fun setup() {
        every { mockContext.assets } returns mockAssetManager
        mockkStatic(android.util.Base64::class)
    }

    @Test
    fun auth1_success() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "dummy_content",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-Airkast-Authtoken" to listOf("test_token"),
                    "X-Airkast-KeyLength" to listOf("100"),
                    "X-Airkast-KeyOffset" to listOf("50")
                )
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        val (token, keyLength, keyOffset) = client.auth1()

        assertEquals("test_token", token)
        assertEquals(100, keyLength)
        assertEquals(50, keyOffset)
    }

    @Test
    fun auth1_failure_http_error() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.auth1()
            org.junit.Assert.fail("Auth1Exception was not thrown")
        } catch (e: Auth1Exception) {
            // success
        }
    }

    @Test
    fun auth1_failure_missing_headers() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "dummy",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-Airkast-Authtoken" to listOf("test_token")
                    // Missing KeyLength and KeyOffset
                )
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.auth1()
            org.junit.Assert.fail("Auth1Exception was not thrown")
        } catch (e: Auth1Exception) {
            // success
        }
    }

    @Test
    fun getPartialKey_success() = runTest {
        val testKeyData = "HELLO".toByteArray()
        every { mockAssetManager.open(any()) } returns ByteArrayInputStream(testKeyData)
        every { android.util.Base64.encodeToString(any(), any()) } returns "SELLO" // Mock Base64

        val client = AirkastClient()
        val partialKey = client.getPartialKey(mockContext, 0, testKeyData.size)

        assertEquals("SELLO", partialKey)
    }

    @Test
    fun getPartialKey_failure_io_exception() = runTest {
        every { mockAssetManager.open(any()) } throws IOException("Asset not found")

        val client = AirkastClient()

        try {
            client.getPartialKey(mockContext, 0, 10)
            org.junit.Assert.fail("PartialKeyException was not thrown")
        } catch (e: PartialKeyException) {
            // success
        }
    }

    @Test
    fun auth2_success() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "dummy_content",
                status = HttpStatusCode.OK
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        client.auth2("token", "partialKey", "areaId")
        // No exception means success
    }

    @Test
    fun auth2_failure_http_error() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.auth2("token", "partialKey", "areaId")
            org.junit.Assert.fail("Auth2Exception was not thrown")
        } catch (e: Auth2Exception) {
            // success
        }
    }

    @Test
    fun getStations_success() = runTest {
        val mockXml = """
            <stations>
                <station id="JOEX">
                    <name>文化放送</name>
                </station>
                <station id="JOLF">
                    <name>ニッポン放送</name>
                </station>
            </stations>
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = mockXml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        val stations = client.getStations("JP13")
        assertEquals(2, stations.size)
        assertEquals("JOEX", stations[0].id)
        assertEquals("文化放送", stations[0].name)
        assertEquals("JOLF", stations[1].id)
        assertEquals("ニッポン放送", stations[1].name)
    }

    @Test
    fun getStations_failure_http_error() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.getStations("JP13")
            org.junit.Assert.fail("StationListException was not thrown")
        } catch (e: StationListException) {
            // success
        }
    }

    @Test
    fun getStations_failure_xml_parse_error() = runTest {
        val malformedXml = "<stations><station><name>テスト</name></stations>" // Missing ID

        val mockEngine = MockEngine {
            respond(
                content = malformedXml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.getStations("JP13")
            org.junit.Assert.fail("XmlParseException was not thrown")
        } catch (e: XmlParseException) {
            // success
        }
    }

    @Test
    fun getProgramGuide_success() = runTest {
        val mockXml = """
            <radiko>
                <stations>
                    <station id="TBS">
                        <name>TBSラジオ</name>
                        <progs>
                            <date>20260125</date>
                            <prog id="123" ft="20260125100000" to="20260125110000">
                                <title>番組タイトル1</title>
                                <desc>番組説明1</desc>
                                <pfm>出演者1</pfm>
                                <img>http://example.com/img1.png</img>
                            </prog>
                            <prog id="456" ft="20260125110000" to="20260125120000">
                                <title>番組タイトル2</title>
                                <desc>番組説明2</desc>
                                <pfm>出演者2</pfm>
                            </prog>
                        </progs>
                    </station>
                </stations>
            </radiko>
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = mockXml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        val programs = client.getProgramGuide("TBS", "20260125")
        assertEquals(2, programs.size)
        assertEquals("123", programs[0].id)
        assertEquals("番組タイトル1", programs[0].title)
        assertEquals("番組説明1", programs[0].description)
        assertEquals("出演者1", programs[0].performer)
        assertEquals("http://example.com/img1.png", programs[0].imageUrl)

        assertEquals("456", programs[1].id)
        assertEquals("番組タイトル2", programs[1].title)
        assertEquals("番組説明2", programs[1].description)
        assertEquals("出演者2", programs[1].performer)
        assertEquals(null, programs[1].imageUrl)
    }

    @Test
    fun getProgramGuide_failure_http_error() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.getProgramGuide("TBS", "20260125")
            org.junit.Assert.fail("ProgramGuideException was not thrown")
        } catch (e: ProgramGuideException) {
            // success
        }
    }

    @Test
    fun getProgramGuide_failure_xml_parse_error() = runTest {
        val malformedXml = "<radiko><stations><station><name>テスト</name></station></stations></radiko>"

        val mockEngine = MockEngine {
            respond(
                content = malformedXml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml")
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))

        try {
            client.getProgramGuide("TBS", "20260125")
            org.junit.Assert.fail("XmlParseException was not thrown")
        } catch (e: XmlParseException) {
            // success
        }
    }

    @Test
    fun getHlsStreamUrl_success() = runTest {
        val mockHlsUrl = "http://example.com/hls/master.m3u8"
        val mockEngine = MockEngine {
            respond(
                content = mockHlsUrl,
                status = HttpStatusCode.OK
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))
        val program = AirkastProgram("id", "ft", "to", "title", "desc", "pfm", "img")

        val hlsUrl = client.getHlsStreamUrl(program, "authToken")
        assertEquals(mockHlsUrl, hlsUrl)
    }

    @Test
    fun getHlsStreamUrl_failure_http_error() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = AirkastClient(HttpClient(mockEngine))
        val program = AirkastProgram("id", "ft", "to", "title", "desc", "pfm", "img")

        try {
            client.getHlsStreamUrl(program, "authToken")
            org.junit.Assert.fail("HlsStreamException was not thrown")
        } catch (e: HlsStreamException) {
            // success
        }
    }
}
