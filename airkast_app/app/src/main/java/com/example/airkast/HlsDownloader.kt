package com.example.airkast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

private const val TAG = "HlsDownloader"

/** ADTSヘッダーのsampling_frequency_indexに対応するサンプリングレート (0 は無効値)。 */
private val ADTS_SAMPLING_RATES = intArrayOf(
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
    16000, 12000, 11025, 8000, 7350, 0, 0, 0
)

/** ADTSヘッダー (先頭7バイト) から読み取った値。 */
internal data class AdtsHeader(
    val profile: Int,
    val sampleRateIdx: Int,
    val channelConfig: Int,
    /** ヘッダーを含むフレーム全体の長さ (バイト)。 */
    val frameLength: Int
)

/** ADTSヘッダーのバイト列 (少なくとも6バイト) を解析します。 */
internal fun parseAdtsHeader(header: ByteArray): AdtsHeader {
    val profile = (header[2].toInt() shr 6) and 0x03
    val sampleRateIdx = (header[2].toInt() shr 2) and 0x0F
    val channelConfig = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() shr 6) and 0x03)
    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
        ((header[4].toInt() and 0xFF) shl 3) or
        ((header[5].toInt() and 0xE0) shr 5)
    return AdtsHeader(profile, sampleRateIdx, channelConfig, frameLength)
}

/** AAC AudioSpecificConfig (MediaFormat の "csd-0" に設定する2バイト) を生成します。 */
internal fun buildAudioSpecificConfig(profile: Int, sampleRateIdx: Int, channelConfig: Int): ByteArray = byteArrayOf(
    (((profile + 1) shl 3) or (sampleRateIdx shr 1)).toByte(),
    (((sampleRateIdx and 0x01) shl 7) or (channelConfig shl 3)).toByte()
)

/** radikoのHLS配信に必要なリクエストヘッダーを付与します。 */
private fun HttpRequestBuilder.applyRadikoHlsHeaders(authToken: String) {
    headers.append("X-Radiko-Authtoken", authToken)
    headers.append("X-Radiko-App", "pc_html5")
    headers.append("X-Radiko-App-Version", "0.0.1")
    headers.append("X-Radiko-User", "dummy_user")
    headers.append("X-Radiko-Device", "pc")
    headers.append("Referer", "https://radiko.jp/")
    headers.append("Origin", "https://radiko.jp")
}

/**
 * HLS (HTTP Live Streaming) のダウンローダークラス。
 */
class HlsDownloader(private val client: HttpClient) {

    /**
     * マスタープレイリストの内容から、メディアプレイリストのURLを抽出します。
     * 見つからない場合は null を返します。
     */
    fun parseMediaPlaylistUrl(masterPlaylistContent: String): String? {
        val lines = masterPlaylistContent.lines()
        // #EXT-X-STREAM-INFタグを探す
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                if (i + 1 < lines.size && lines[i+1].isNotBlank()) {
                    return lines[i+1].trim()
                }
            }
        }
        // なければ最初のURLと思わしき行を返す
        return lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") && it.startsWith("http") }
    }

    /**
     * M3U8プレイリストのコンテンツを解析し、セグメントのURLリストを抽出します。
     *
     * @param playlistContent M3U8プレイリストの文字列コンテンツ。
     * @param playlistUrl プレイリスト自体のURL。相対URLを解決するために使用されます。
     * @return セグメントURLのリスト。
     */
    fun parsePlaylist(playlistContent: String, playlistUrl: String): List<String> {
        return playlistContent.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { segmentUrl ->
                resolveUrl(playlistUrl, segmentUrl.trim())
            }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl

        return try {
            // クエリパラメータを除いたベースURLを取得
            val baseWithoutQuery = baseUrl.substringBefore("?")
            val lastSlashIndex = baseWithoutQuery.lastIndexOf('/')
            val baseDir = if (lastSlashIndex != -1) {
                baseWithoutQuery.substring(0, lastSlashIndex + 1)
            } else {
                "/"
            }

            if (relativeUrl.startsWith("/")) {
                // 絶対パス風
                val uri = java.net.URI(baseUrl)
                "${uri.scheme}://${uri.host}${relativeUrl}"
            } else {
                // 相対パス
                baseDir + relativeUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "URL resolution failed: $baseUrl + $relativeUrl", e)
            relativeUrl // Fallback
        }
    }

    /**
     * 指定された期間を [chunkDurationSeconds] ごとに分割してダウンロードし、単一のM4Aファイルに結合します。
     *
     * @param outputFile 出力先のファイル。
     * @param startTime 開始時刻 (エポックミリ秒)。
     * @param endTime 終了時刻 (エポックミリ秒)。
     * @param chunkDurationSeconds 1回のプレイリスト取得で扱う長さ (秒)。
     * @param authToken 認証トークン。
     * @param urlGenerator チャンクの開始・終了時刻(ミリ秒)からプレイリストURLを生成する関数。
     * @param onProgress ダウンロードの進捗状況を通知するコールバック (0.0f から 1.0f)。
     * @param onMuxingProgress M4A変換の進捗状況を通知するコールバック (0.0f から 1.0f)。
     * @throws HlsDownloadException ダウンロードに失敗した場合。
     */
    @Throws(HlsDownloadException::class)
    suspend fun downloadChunked(
        outputFile: File,
        startTime: Long,
        endTime: Long,
        chunkDurationSeconds: Int = 300,
        authToken: String,
        urlGenerator: (Long, Long) -> String,
        onProgress: (Float) -> Unit,
        onMuxingProgress: (Float) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== 分割ダウンロード開始 ===")

                // 一時ファイル (生のADTS) を初期化
                val adtsFile = File(outputFile.absolutePath + ".adts")
                if (adtsFile.exists()) {
                    adtsFile.delete()
                }
                adtsFile.createNewFile()

                downloadChunksToAdts(
                    adtsFile, startTime, endTime, chunkDurationSeconds, authToken, urlGenerator, onProgress
                )
                Log.d(TAG, "=== 分割ダウンロード完了 ===")
                onProgress(1f)

                convertAdtsToOutput(adtsFile, outputFile, onMuxingProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "分割ダウンロードエラー: ${e.message}", e)
                throw HlsDownloadException(cause = e)
            }
        }
    }

    /**
     * 期間をチャンクに分けてセグメントをダウンロードし、[adtsFile] に追記していきます。
     * チャンク単位の失敗は警告ログのみで、次のチャンクへ進みます。
     */
    private suspend fun downloadChunksToAdts(
        adtsFile: File,
        startTime: Long,
        endTime: Long,
        chunkDurationSeconds: Int,
        authToken: String,
        urlGenerator: (Long, Long) -> String,
        onProgress: (Float) -> Unit
    ) {
        val totalDuration = endTime - startTime
        var currentStartTime = startTime

        // 重複ダウンロード防止用 (クエリパラメータを除いたセグメントURLを保持)
        val downloadedSegments = mutableSetOf<String>()

        while (currentStartTime < endTime) {
            val currentEndTime = minOf(currentStartTime + chunkDurationSeconds * 1000, endTime)
            if (currentStartTime >= currentEndTime) break

            val playlistUrl = urlGenerator(currentStartTime, currentEndTime)
            Log.d(TAG, "Downloading chunk: ${currentStartTime / 1000} to ${currentEndTime / 1000}")

            try {
                val segmentUrls = fetchSegmentUrls(playlistUrl, authToken)
                Log.d(TAG, "Found ${segmentUrls.size} segments in chunk")

                FileOutputStream(adtsFile, true).use { outputStream ->
                    for (segmentUrl in segmentUrls) {
                        val segmentId = segmentUrl.substringBefore("?")
                        if (segmentId !in downloadedSegments) {
                            downloadSegmentToStream(segmentUrl, authToken, outputStream)
                            downloadedSegments.add(segmentId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chunk download error: ${e.message}")
            }

            currentStartTime = currentEndTime
            val progress = (currentStartTime - startTime).toFloat() / totalDuration.toFloat()
            onProgress(progress.coerceIn(0f, 1f))
        }
    }

    /**
     * 生のADTSファイルをM4Aに変換して [outputFile] にします。
     * 変換に失敗した場合は、ADTSのまま [outputFile] として残します。
     */
    private fun convertAdtsToOutput(adtsFile: File, outputFile: File, onMuxingProgress: (Float) -> Unit) {
        try {
            Log.d(TAG, "M4A remuxing started...")
            muxAdtsToM4a(adtsFile, outputFile, onMuxingProgress)
            adtsFile.delete()
            Log.d(TAG, "M4A remuxing completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "M4A remuxing failed, keeping raw ADTS as fallback: ${e.message}")
            if (adtsFile.exists()) {
                if (outputFile.exists()) outputFile.delete()
                adtsFile.renameTo(outputFile)
            }
        }
    }

    /**
     * Raw ADTS (AAC) ファイルを MediaMuxer を使用して M4A コンテナに変換します。
     * ストリーミング方式で読み込むことで、メモリ消費を抑えつつ長時間のファイルに対応します。
     */
    private fun muxAdtsToM4a(adtsFile: File, m4aFile: File, onProgress: (Float) -> Unit = {}) {
        val totalBytes = adtsFile.length()
        var processedBytes = 0L
        Log.d(TAG, "muxAdtsToM4a (Streaming): input=$totalBytes bytes")

        val inputStream = adtsFile.inputStream().buffered()

        // Mux into a temporary file first
        val tempMuxFile = File(m4aFile.absolutePath + ".tmp_mux")
        if (tempMuxFile.exists()) tempMuxFile.delete()

        val muxer = MediaMuxer(tempMuxFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var trackIndex = -1
        var frameCount = 0
        var presentationTimeUs = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        val sampleBuffer = ByteBuffer.allocateDirect(1024 * 1024)

        try {
            val header = ByteArray(9)
            while (true) {
                // Find Syncword (0xFFF)
                val firstByte = inputStream.read()
                if (firstByte == -1) break
                if (firstByte != 0xFF) continue

                val secondByte = inputStream.read()
                if (secondByte == -1) break
                if ((secondByte and 0xF0) != 0xF0) continue

                // Read rest of the header (at least 5 more bytes for 7-byte header)
                header[0] = firstByte.toByte()
                header[1] = secondByte.toByte()
                val read = inputStream.read(header, 2, 5)
                if (read < 5) break

                // protection_absent (1 bit) - bit 15
                val protectionAbsent = header[1].toInt() and 0x01
                val headerSize = if (protectionAbsent == 1) 7 else 9

                if (headerSize == 9) {
                    val crcRead = inputStream.read(header, 7, 2)
                    if (crcRead < 2) break
                }

                val adts = parseAdtsHeader(header)

                // Validate sample rate index to prevent divide by zero
                val sampleRate = ADTS_SAMPLING_RATES[adts.sampleRateIdx]
                if (sampleRate <= 0) {
                    Log.w(TAG, "Invalid sample rate index: ${adts.sampleRateIdx} (rate=0) at frame $frameCount, skipping")
                    continue
                }

                val sampleSize = adts.frameLength - headerSize
                if (sampleSize <= 0 || sampleSize > sampleBuffer.capacity()) {
                    Log.w(TAG, "Invalid sample size: $sampleSize at frame $frameCount")
                    continue
                }

                // Initialize muxer on first frame
                if (trackIndex == -1) {
                    Log.d(TAG, "Initializing muxer: rate=$sampleRate, channels=${adts.channelConfig}, profile=${adts.profile}")

                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, adts.channelConfig)
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    format.setByteBuffer(
                        "csd-0",
                        ByteBuffer.wrap(buildAudioSpecificConfig(adts.profile, adts.sampleRateIdx, adts.channelConfig))
                    )

                    trackIndex = muxer.addTrack(format)
                    muxer.start()
                }

                // Read sample data
                sampleBuffer.clear()
                val sampleData = ByteArray(sampleSize)
                val dataRead = inputStream.read(sampleData)
                if (dataRead < sampleSize) {
                    Log.w(TAG, "Unexpected EOF while reading sample data")
                    break
                }
                sampleBuffer.put(sampleData)
                sampleBuffer.flip()

                bufferInfo.set(0, sampleSize, presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(trackIndex, sampleBuffer, bufferInfo)

                // Increment PTS (AAC normally 1024 samples/frame)
                presentationTimeUs += (1024 * 1000000L / sampleRate)
                frameCount++

                processedBytes += adts.frameLength

                if (frameCount % 1000 == 0) {
                    if (totalBytes > 0) {
                        onProgress(processedBytes.toFloat() / totalBytes.toFloat())
                    }
                    Log.d(TAG, "Muxing progress: $frameCount frames (${presentationTimeUs / 1000000 / 60} mins) processed...")
                }
            }

            Log.d(TAG, "Muxing finished: total $frameCount frames, duration=${presentationTimeUs / 1000000}s")

            if (trackIndex != -1) {
                muxer.stop()
                muxer.release()

                if (m4aFile.exists()) m4aFile.delete()
                if (tempMuxFile.renameTo(m4aFile)) {
                    Log.d(TAG, "Successfully replaced file with streaming-muxed version.")
                } else {
                    Log.e(TAG, "Failed to rename muxed file to target!")
                    throw Exception("Rename failed")
                }
            } else {
                Log.w(TAG, "No valid AAC tracks found during streaming mux.")
                tempMuxFile.delete()
            }
        } finally {
            inputStream.close()
            if (tempMuxFile.exists()) tempMuxFile.delete()
        }
    }

    /**
     * プレイリストURLからセグメントURLのリストを取得します（再帰的なマスタープレイリスト処理を含む）。
     */
    private suspend fun fetchSegmentUrls(playlistUrl: String, authToken: String): List<String> {
        val playlistResponse: HttpResponse = client.get(playlistUrl) {
            applyRadikoHlsHeaders(authToken)
        }

        if (playlistResponse.status.value != 200) {
            throw HlsDownloadException("Failed to fetch playlist: ${playlistResponse.status}")
        }

        val playlistContent = playlistResponse.bodyAsText()

        if (playlistContent.contains("#EXT-X-STREAM-INF")) {
            val mediaRelativeUrl = parseMediaPlaylistUrl(playlistContent)
                ?: throw HlsDownloadException("Failed to parse media playlist URL")
            val mediaPlaylistUrl = resolveUrl(playlistUrl, mediaRelativeUrl)
            return fetchSegmentUrls(mediaPlaylistUrl, authToken)
        }

        return parsePlaylist(playlistContent, playlistUrl)
    }

    /**
     * 単一のセグメントをダウンロードして指定された出力ストリームに直接書き込みます。
     */
    private suspend fun downloadSegmentToStream(segmentUrl: String, authToken: String, outputStream: OutputStream) {
        client.prepareGet(segmentUrl) {
            applyRadikoHlsHeaders(authToken)
        }.execute { response ->
            if (response.status.value != 200) {
                throw HlsDownloadException("Failed to download segment: ${response.status}")
            }
            response.bodyAsChannel().copyTo(outputStream)
        }
    }
}
