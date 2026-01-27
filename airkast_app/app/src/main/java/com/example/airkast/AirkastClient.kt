package com.example.airkast

import android.util.Base64
import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.plugin
import java.io.IOException
import java.util.UUID

/**
 * Radiko APIと通信するためのクライアントクラス。
 * PC版（html5）認証を使用します。
 * 認証、放送局情報、番組表の取得などを行います。
 */
class AirkastClient {
    private val cookiesStorage = AcceptAllCookiesStorage()
    val client: HttpClient = HttpClient(Android) {
        install(HttpCookies) {
            storage = cookiesStorage
        }
        install(UserAgent) {
            agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    var authToken: String? = null
    var areaId: String? = null

    private suspend fun copyRadikoCookiesToSmartStream() {
        val radikoUrl = Url("https://radiko.jp")
        val cookies = cookiesStorage.get(radikoUrl)
        if (cookies.isEmpty()) {
            Log.w(TAG, "Radiko cookies not found. Authentication might be incomplete.")
        } else {
             Log.d(TAG, "Copying ${cookies.size} cookies from radiko.jp to smartstream.ne.jp")
        }
        
        cookies.forEach { cookie ->
             // SmartStreamのサブドメインに対してCookieを設定
             // ライブ用
             cookiesStorage.addCookie(
                 Url("https://si-f-radiko.smartstream.ne.jp"),
                 cookie.copy(domain = "smartstream.ne.jp")
             )
             // タイムフリー用 (これが不足していた可能性大)
             cookiesStorage.addCookie(
                 Url("https://tf-f-rpaa-radiko.smartstream.ne.jp"),
                 cookie.copy(domain = "smartstream.ne.jp")
             )
             cookiesStorage.addCookie(
                 Url("https://tf-rpaa.smartstream.ne.jp"),
                 cookie.copy(domain = "smartstream.ne.jp")
             )
        }
    }

    companion object {
        private const val TAG = "AirkastClient"
        private const val AUTH1_URL = "https://radiko.jp/v2/api/auth1"
        private const val AUTH2_URL = "https://radiko.jp/v2/api/auth2"
        
        // PC版radikoの公開キー（JavaScriptから取得）
        private const val PC_FULL_KEY = "bcd151073c03b352e1ef2fd66c32209da9ca0afa"

        // PC版用のヘッダー
        internal val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "X-Radiko-App" to "pc_html5",
            "X-Radiko-App-Version" to "0.0.1",
            "X-Radiko-User" to "dummy_user",
            "X-Radiko-Device" to "pc"
        )
    }

    /**
     * Radiko認証のステップ1を実行します（PC版）。
     * @return キーの長さとキーのオフセットを含むPair。
     * @throws Auth1Exception 認証に失敗した場合。
     */
    suspend fun auth1(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== auth1 開始 ===")
        Log.d(TAG, "URL: $AUTH1_URL")
        try {
            val response: HttpResponse = client.get(AUTH1_URL) {
                defaultHeaders.forEach { (key, value) -> headers.append(key, value) }
            }
            Log.d(TAG, "auth1 レスポンス: ${response.status.value}")
            
            if (response.status.value != 200) {
                Log.e(TAG, "auth1 失敗: HTTP ${response.status.value}")
                throw Auth1Exception(message = "HTTP Error: ${response.status.value}")
            }
            
            val token = response.headers["X-Radiko-Authtoken"]
            val keyLength = response.headers["X-Radiko-KeyLength"]?.toIntOrNull()
            val keyOffset = response.headers["X-Radiko-KeyOffset"]?.toIntOrNull()
            
            Log.d(TAG, "auth1 トークン取得: ${token?.take(10)}...")
            Log.d(TAG, "auth1 KeyLength: $keyLength, KeyOffset: $keyOffset")
            
            if (token == null) throw Auth1Exception(message = "Missing X-Radiko-Authtoken header")
            authToken = token
            if (keyLength == null) throw Auth1Exception(message = "Missing X-Radiko-KeyLength header")
            if (keyOffset == null) throw Auth1Exception(message = "Missing X-Radiko-KeyOffset header")
            
            Log.d(TAG, "=== auth1 成功 ===")
            Pair(keyLength, keyOffset)
        } catch (e: Auth1Exception) {
            Log.e(TAG, "auth1 エラー: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "auth1 エラー (その他): ${e.message}", e)
            throw Auth1Exception(cause = e)
        }
    }

    /**
     * PC版公開キーから部分キーを生成します。
     * @param offset キー内のオフセット。
     * @param length 読み込むキーの長さ。
     * @return Base64エンコードされた部分キー。
     * @throws PartialKeyException 部分キーの取得に失敗した場合。
     */
    fun getPartialKey(offset: Int, length: Int): String {
        Log.d(TAG, "getPartialKey: offset=$offset, length=$length")
        try {
            val keyBytes = PC_FULL_KEY.toByteArray(Charsets.UTF_8)
            val partialBytes = keyBytes.copyOfRange(offset, minOf(offset + length, keyBytes.size))
            val result = Base64.encodeToString(partialBytes, Base64.NO_WRAP)
            Log.d(TAG, "getPartialKey 成功: ${result.take(10)}...")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "getPartialKey エラー: ${e.message}", e)
            throw PartialKeyException(cause = e)
        }
    }

    /**
     * Radiko認証のステップ2を実行します（PC版）。
     * PC版はIPアドレスで位置を判定するため、X-Radiko-Locationヘッダーは不要です。
     * @param partialKey getPartialKeyで取得した部分キー。
     * @return エリアID（例: "JP13"）。
     * @throws Auth2Exception 認証に失敗した場合。
     */
    suspend fun auth2(partialKey: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== auth2 開始 ===")
        val token = authToken
        if (token == null) {
            Log.e(TAG, "auth2 エラー: トークンがありません")
            throw Auth2Exception("Auth token is not available. Run auth1 first.")
        }
        Log.d(TAG, "auth2 使用トークン: ${token.take(10)}...")
        
        try {
            val response: HttpResponse = client.get(AUTH2_URL) {
                defaultHeaders.forEach { (key, value) -> headers.append(key, value) }
                headers.append("X-Radiko-Authtoken", token)
                headers.append("X-Radiko-Partialkey", partialKey)
            }
            Log.d(TAG, "auth2 レスポンス: ${response.status.value}")
            
            if (response.status.value != 200) {
                Log.e(TAG, "auth2 失敗: HTTP ${response.status.value}")
                throw Auth2Exception(message = "HTTP Error: ${response.status.value}")
            }
            
            val responseBody = response.bodyAsText().trim()
            Log.d(TAG, "auth2 レスポンスボディ: $responseBody")
            
            if (responseBody == "OUT") {
                Log.e(TAG, "auth2 エラー: エリア外 (海外IPの可能性)")
                throw Auth2Exception(message = "エリア外です。日本国内からアクセスしてください。")
            }
            
            val detectedAreaId = responseBody.split(",").firstOrNull()
                ?: throw Auth2Exception(message = "エリアIDの取得に失敗しました: $responseBody")
            areaId = detectedAreaId
            copyRadikoCookiesToSmartStream() // 追加：CookieをSmartStreamに伝播
            Log.d(TAG, "=== auth2 成功: エリアID=$detectedAreaId ===")
            detectedAreaId
        } catch (e: Auth2Exception) {
            Log.e(TAG, "auth2 エラー: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "auth2 エラー (その他): ${e.message}", e)
            throw Auth2Exception(cause = e)
        }
    }

    /**
     * 指定されたエリアの放送局リストを取得します。
     * @param areaId エリアID。
     * @return 放送局のリスト。
     * @throws StationListException 放送局リストの取得に失敗した場合。
     */
    suspend fun getStations(areaId: String): List<AirkastStation> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== getStations 開始: areaId=$areaId ===")
        val url = "https://radiko.jp/v3/station/list/$areaId.xml"
        Log.d(TAG, "getStations URL: $url")
        
        try {
            val response: HttpResponse = client.get(url) {
                applyDefaultHeaders()
            }
            Log.d(TAG, "getStations レスポンス: ${response.status.value}")
            
            if (response.status.value != 200) {
                Log.e(TAG, "getStations 失敗: HTTP ${response.status.value}")
                throw StationListException(message = "HTTP Error: ${response.status.value}")
            }
            
            val stations = parseStationsXml(response.bodyAsText())
            Log.d(TAG, "=== getStations 成功: ${stations.size}局取得 ===")
            stations
        } catch (e: StationListException) {
            Log.e(TAG, "getStations エラー: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getStations エラー (その他): ${e.message}", e)
            throw StationListException(cause = e)
        }
    }

    /**
     * 指定された放送局と日付の番組表を取得します。
     * @param stationId 放送局ID。
     * @param date 日付 (yyyyMMdd)。
     * @return 番組のリスト。
     * @throws ProgramGuideException 番組表の取得に失敗した場合。
     */
    suspend fun getProgramGuide(stationId: String, date: String): List<AirkastProgram> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get("https://radiko.jp/v3/program/station/date/$date/$stationId.xml") {
                defaultHeaders.forEach { (key, value) -> headers.append(key, value) }
            }
            if (response.status.value != 200) {
                throw ProgramGuideException(message = "HTTP Error: ${response.status.value}")
            }
            // XMLパース時にstationIdを各Programにセットする
            parseProgramGuideXml(response.bodyAsText(), stationId)
        } catch (e: Exception) {
            throw ProgramGuideException(cause = e)
        }
    }

    fun generateNewLsid(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    /**
     * タイムフリーHLSストリームのURLを取得します。
     * @param program 対象の番組。
     * @param startAt 開始時間 (yyyyMMddHHmmss)。デフォルトはprogram.startTime。
     * @param endAt 終了時間 (yyyyMMddHHmmss)。デフォルトはprogram.endTime。
     * @param lsid セッションID。指定されない場合は新規生成されます。
     * @return HLSストリームのURL。
     * @throws HlsStreamException HLSストリームURLの取得に失敗した場合。
     */
    fun generateHlsUrl(program: AirkastProgram, startAt: String? = null, endAt: String? = null, lsid: String? = null): String {
        val finalLsid = lsid ?: UUID.randomUUID().toString().replace("-", "")
        
        val ft = startAt ?: program.startTime
        val to = endAt ?: program.endTime
        
        // Calculate duration in seconds
        val durationSeconds = try {
            val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.JAPAN)
            val s = format.parse(ft)?.time ?: 0L
            val e = format.parse(to)?.time ?: 0L
            (e - s) / 1000
        } catch (e: Exception) {
            15 // Fallback
        }

        // Correct hostname: tf-f-rpaa-radiko.smartstream.ne.jp
        return "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8" +
            "?station_id=${program.stationId}" +
            "&l=$durationSeconds" +
            "&ft=$ft" +
            "&to=$to" +
            "&start_at=$ft" +
            "&end_at=$to" +
            "&lsid=$finalLsid" +
            "&type=b"
    }
    
    // resolveRedirects is no longer needed as we are using the direct URL.
    
    suspend fun getHlsStreamUrl(program: AirkastProgram): String = withContext(Dispatchers.IO) {
        val token = authToken ?: throw HlsStreamException("Auth token is not available. Run auth1 first.")
        
        // Return the correctly formatted URL directly.
        // Authentication headers (X-Radiko-Authtoken) will be added by the caller (HlsDownloader, PlayerService).
        generateHlsUrl(program)
    }

    @Throws(XmlParseException::class)
    private fun parseStationsXml(xmlString: String): List<AirkastStation> {
        try {
            val stations = mutableListOf<AirkastStation>()
            val parser = Xml.newPullParser()
            parser.setInput(xmlString.reader())
            var eventType = parser.eventType
            var currentStationId: String? = null
            var currentStationName: String? = null
            var text: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.TEXT -> text = parser.text
                    XmlPullParser.END_TAG -> when (tagName) {
                        "id" -> currentStationId = text
                        "name" -> currentStationName = text
                        "station" -> {
                            currentStationId?.let { id ->
                                currentStationName?.let { name ->
                                    stations.add(AirkastStation(id, name))
                                }
                            }
                            currentStationId = null
                            currentStationName = null
                        }
                    }
                }
                eventType = parser.next()
            }
            return stations
        } catch (e: XmlPullParserException) {
            throw XmlParseException(cause = e)
        } catch (e: IOException) {
            throw XmlParseException(cause = e)
        }
    }

        @Throws(XmlParseException::class)
        private fun parseProgramGuideXml(xmlString: String, stationId: String): List<AirkastProgram> {
            try {
                val programs = mutableListOf<AirkastProgram>()
                val parser = Xml.newPullParser()
                parser.setInput(xmlString.reader())
                var eventType = parser.eventType
                var currentProgram: AirkastProgram? = null
                var text: String? = null

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName.equals("prog", ignoreCase = true)) {
                                val id = parser.getAttributeValue(null, "id") ?: throw XmlParseException("Missing 'id' attribute in program XML")
                                val ft = parser.getAttributeValue(null, "ft") ?: throw XmlParseException("Missing 'ft' attribute in program XML")
                                val to = parser.getAttributeValue(null, "to") ?: throw XmlParseException("Missing 'to' attribute in program XML")
                                currentProgram = AirkastProgram(id, stationId, ft, to, "", "", "", null)
                            }
                        }
                        XmlPullParser.TEXT -> text = parser.text
                        XmlPullParser.END_TAG -> {
                            currentProgram?.let { prog ->
                                when (tagName) {
                                    "title" -> prog.title = text ?: ""
                                    "desc" -> prog.description = text ?: ""
                                    "pfm" -> prog.performer = text ?: ""
                                    "img" -> prog.imageUrl = text
                                    "prog" -> {
                                        programs.add(prog)
                                        currentProgram = null
                                    }
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
                return programs
            } catch (e: XmlPullParserException) {
                throw XmlParseException(cause = e)
            } catch (e: IOException) {
                throw XmlParseException(cause = e)
            }
        }}

fun HttpRequestBuilder.applyDefaultHeaders() {
    AirkastClient.defaultHeaders.forEach { (key, value) -> headers.append(key, value) }
}
    