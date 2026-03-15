package com.example.airkast

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.OutputStream

private const val TAG = "HlsDownloader"

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
     * HLSストリームをダウンロードし、単一のファイルに結合します。
     *
     * @param playlistUrl M3U8プレイリストのURL。
     * @param outputFile 出力先のファイル。
     * @param authToken 認証トークン。
     * @param onProgress ダウンロードの進捗状況を通知するコールバック (0.0f から 1.0f)。
     * @throws HlsDownloadException ダウンロードに失敗した場合。
     */
    @Throws(HlsDownloadException::class)
    /**
     * 指定された時間を分割してダウンロードし、単一のファイルに結合します。
     */
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
                val totalDuration = endTime - startTime
                var currentStartTime = startTime
                
                // 重複ダウンロード防止用 (セグメントのファイル名を保持)
                val downloadedSegments = mutableSetOf<String>()
                
                // 一時ファイルパス
                val adtsFile = File(outputFile.absolutePath + ".adts")
                
                // ファイルを初期化
                if (adtsFile.exists()) {
                    adtsFile.delete()
                }
                adtsFile.createNewFile()
                
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
                                  // クエリパラメータを除いたURL全体を識別子として使用
                                  val segmentId = segmentUrl.substringBefore("?")
                                  
                                  if (!downloadedSegments.contains(segmentId)) {
                                      downloadSegmentToStream(segmentUrl, authToken, outputStream)
                                      downloadedSegments.add(segmentId)
                                  }
                              }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Chunk download error: ${e.message}")
                    }
                    
                    currentStartTime = currentEndTime
                    val progress = (currentStartTime - startTime).toFloat() / totalDuration.toFloat()
                    onProgress(progress.coerceIn(0f, 1f))
                }
                
                Log.d(TAG, "=== 分割ダウンロード完了 ===")
                onProgress(1f)

                // M4A muxing
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
                
            } catch (e: Exception) {
                Log.e(TAG, "分割ダウンロードエラー: ${e.message}", e)
                throw HlsDownloadException(cause = e)
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

        val samplingRates = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 
            16000, 12000, 11025, 8000, 7350, 0, 0, 0
        )

        try {
            val header = ByteArray(9)
            while (true) {
                // Find Syncword (0xFFF)
                var firstByte = inputStream.read()
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

                // Parse header values
                val profile = (header[2].toInt() shr 6) and 0x03
                val sampleRateIdx = (header[2].toInt() shr 2) and 0x0F
                val channelConfig = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() shr 6) and 0x03)
                val frameLength = ((header[3].toInt() and 0x03) shl 11) or 
                                  ((header[4].toInt() and 0xFF) shl 3) or 
                                  ((header[5].toInt() and 0xE0) shr 5)

                // Validate sample rate index to prevent divide by zero
                val sampleRate = samplingRates[sampleRateIdx]
                if (sampleRate <= 0) {
                    Log.w(TAG, "Invalid sample rate index: $sampleRateIdx (rate=0) at frame $frameCount, skipping")
                    continue
                }

                val sampleSize = frameLength - headerSize
                if (sampleSize <= 0 || sampleSize > sampleBuffer.capacity()) {
                    Log.w(TAG, "Invalid sample size: $sampleSize at frame $frameCount")
                    continue
                }

                // Initialize muxer on first frame
                if (trackIndex == -1) {
                    Log.d(TAG, "Initializing muxer: rate=$sampleRate, channels=$channelConfig, profile=$profile")
                    
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelConfig)
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    
                    val csd0 = ByteBuffer.allocate(2)
                    csd0.put(0, (((profile + 1) shl 3) or (sampleRateIdx shr 1)).toByte())
                    csd0.put(1, (((sampleRateIdx and 0x01) shl 7) or (channelConfig shl 3)).toByte())
                    format.setByteBuffer("csd-0", csd0)

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
                
                processedBytes += frameLength
                
                if (frameCount % 1000 == 0) {
                    if (totalBytes > 0) {
                        onProgress(processedBytes.toFloat() / totalBytes.toFloat())
                    }
                    Log.d(TAG, "Muxing progress: $frameCount frames ($((presentationTimeUs / 1000000) / 60) mins) processed...")
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
            headers {
                append("X-Radiko-Authtoken", authToken)
                append("X-Radiko-App", "pc_html5")
                append("X-Radiko-App-Version", "0.0.1")
                append("X-Radiko-User", "dummy_user")
                append("X-Radiko-Device", "pc")
                append("Referer", "https://radiko.jp/")
                append("Origin", "https://radiko.jp")
            }
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
            headers {
                append("X-Radiko-Authtoken", authToken)
                append("X-Radiko-App", "pc_html5")
                append("X-Radiko-App-Version", "0.0.1")
                append("X-Radiko-User", "dummy_user")
                append("X-Radiko-Device", "pc")
                append("Referer", "https://radiko.jp/")
                append("Origin", "https://radiko.jp")
            }
        }.execute { response ->
            if (response.status.value != 200) {
                 throw HlsDownloadException("Failed to download segment: ${response.status}")
            }
            val channel = response.bodyAsChannel()
            channel.copyTo(outputStream)
        }
    }

    /**
     * 単一のセグメントをダウンロードしてバイト配列を返します。
     * (互換性のために残しますが、新規コードでは downloadSegmentToStream を推奨)
     */
    private suspend fun downloadSegment(segmentUrl: String, authToken: String): ByteArray {
        val response: HttpResponse = client.get(segmentUrl) {
            headers {
                append("X-Radiko-Authtoken", authToken)
                append("X-Radiko-App", "pc_html5")
                append("X-Radiko-App-Version", "0.0.1")
                append("X-Radiko-User", "dummy_user")
                append("X-Radiko-Device", "pc")
                append("Referer", "https://radiko.jp/")
                append("Origin", "https://radiko.jp")
            }
        }
        if (response.status.value != 200) {
             throw HlsDownloadException("Failed to download segment: ${response.status}")
        }
        return response.readBytes()
    }
    
    // Legacy support for single stream download
    @Throws(HlsDownloadException::class)
    suspend fun downloadStream(
        playlistUrl: String,
        outputFile: File,
        authToken: String,
        onProgress: (Float) -> Unit
    ) {
         // Re-implement or forward to chunked? 
         // Since downloadStream doesn't have loop info, we implement it using fetchSegmentUrls
         withContext(Dispatchers.IO) {
             try {
                 Log.d(TAG, "=== ダウンロード開始 (Legacy) ===")
                 onProgress(0f)
                 val segmentUrls = fetchSegmentUrls(playlistUrl, authToken)
                 val totalSegments = segmentUrls.size
                                  FileOutputStream(outputFile).use { outputStream ->
                      for ((index, segmentUrl) in segmentUrls.withIndex()) {
                          val bytes = downloadSegment(segmentUrl, authToken)
                          outputStream.write(bytes)
                          onProgress((index + 1) / totalSegments.toFloat())
                      }
                  }
                 
                 // M4A muxing for legacy
                 val rawFileLogacy = File(outputFile.absolutePath + ".raw")
                 try {
                     Log.d(TAG, "M4A remuxing started (Legacy)...")
                     if (outputFile.renameTo(rawFileLogacy)) {
                         muxAdtsToM4a(rawFileLogacy, outputFile)
                         rawFileLogacy.delete()
                         Log.d(TAG, "M4A remuxing completed (Legacy).")
                     } else {
                         Log.e(TAG, "Failed to rename output for legacy muxing")
                     }
                 } catch (e: Exception) {
                     Log.e(TAG, "M4A remuxing failed (Legacy), restoring raw ADTS: ${e.message}")
                     if (rawFileLogacy.exists()) {
                         if (outputFile.exists()) outputFile.delete()
                         rawFileLogacy.renameTo(outputFile)
                     }
                 }

                 onProgress(1f)
             } catch (e: Exception) {
                 Log.e(TAG, "ダウンロードエラー: ${e.message}", e)
                 throw HlsDownloadException(cause = e)
             }
         }
    }
}
