package com.example.airkast

import java.io.File
import java.util.Date

/**
 * 1 番組を m4a にダウンロードする共通処理。
 * フォアグラウンドサービス([com.example.airkast.service.HlsDownloadService])と
 * お気に入りの自動ダウンロード Worker の両方から使う。状態や通知は持たない。
 */
class DownloadRunner(private val client: AirkastClient) {

    companion object {
        const val DEFAULT_CHUNK_SIZE_SECONDS = 60
    }

    /**
     * 番組を [outputFile] にダウンロードします。進捗は 0〜100 の整数で通知します。
     *
     * @throws IllegalArgumentException 番組の開始/終了時刻が不正な場合。
     */
    suspend fun run(
        program: AirkastProgram,
        outputFile: File,
        chunkSizeSeconds: Int = DEFAULT_CHUNK_SIZE_SECONDS,
        onProgress: (Int) -> Unit = {},
        onMuxingProgress: (Int) -> Unit = {},
    ) {
        val format = radikoTimestampFormat()
        val startTime = runCatching { format.parse(program.startTime)?.time }.getOrNull() ?: 0L
        val endTime = runCatching { format.parse(program.endTime)?.time }.getOrNull() ?: 0L

        require(startTime != 0L && endTime != 0L && startTime < endTime) {
            "Invalid program duration: start=$startTime, end=$endTime"
        }

        val downloadLsid = client.generateNewLsid()

        HlsDownloader(client.client).downloadChunked(
            outputFile = outputFile,
            startTime = startTime,
            endTime = endTime,
            chunkDurationSeconds = chunkSizeSeconds,
            authToken = client.authToken ?: "",
            urlGenerator = { s, e ->
                client.generateHlsUrl(program, format.format(Date(s)), format.format(Date(e)), downloadLsid)
            },
            onProgress = { onProgress((it * 100).toInt()) },
            onMuxingProgress = { onMuxingProgress((it * 100).toInt()) },
        )
    }
}
