package com.example.airkast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastClient
import com.example.airkast.HlsDownloader
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.File

private const val TAG = "HlsDownloadService"

class HlsDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var notificationManager: NotificationManager
    private lateinit var client: AirkastClient

    // 各ダウンロードのJobを保持する (programId -> Job)
    private val downloadJobs = mutableMapOf<String, Job>()

    // 同時ダウンロード数の上限（ネットワーク・CPU負荷を考慮）
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    companion object {
        const val ACTION_START_DOWNLOAD = "ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_PROGRAM = "EXTRA_PROGRAM"
        const val EXTRA_PROGRAM_ID = "EXTRA_PROGRAM_ID"
        const val EXTRA_CHUNK_SIZE = "EXTRA_CHUNK_SIZE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "HlsDownloadServiceChannel"
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        // ダウンロード状態の管理 (Static access for ViewModel)
        private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
        val downloadStates = _downloadStates.asStateFlow()

        fun getDownloadFile(context: Context, program: AirkastProgram): File {
            val safeTitle = program.title.replace(Regex("[/:\\\\*?\"<>|]"), "_")
            val stationId = program.stationId.ifEmpty { "unknown" }
            val fileName = "${stationId}_${safeTitle}_${program.startTime}_${program.endTime}.m4a"
            return File(context.getExternalFilesDir("downloads"), fileName)
        }

        data class ParsedFileName(
            val stationId: String,
            val title: String,
            val startTime: String,
            val endTime: String
        )

        fun parseFileName(fileName: String): ParsedFileName? {
            val nameWithoutExt = fileName.substringBeforeLast(".")
            val parts = nameWithoutExt.split("_")

            // 新形式 (4つ以上のパーツ): stationId_title_startTime_endTime
            if (parts.size >= 4) {
                val endTime = parts.last()
                val startTime = parts[parts.size - 2]
                if (startTime.length == 14 && endTime.length == 14) {
                    val stationId = parts.first()
                    val title = parts.subList(1, parts.size - 2).joinToString("_")
                    return ParsedFileName(stationId, title, startTime, endTime)
                }
            }

            // 旧形式 (3つのパーツ): title_startTime_endTime
            if (parts.size >= 3) {
                val endTime = parts.last()
                val startTime = parts[parts.size - 2]
                if (startTime.length == 14 && endTime.length == 14) {
                    val title = parts.subList(0, parts.size - 2).joinToString("_")
                    return ParsedFileName("", title, startTime, endTime)
                }
            }

            // それ以前の形式 (ID_title.m4a)
            if (parts.size >= 2) {
                val stationOrId = parts.first()
                val title = parts.subList(1, parts.size).joinToString("_")
                return ParsedFileName(stationOrId, title, "", "")
            }

            return null
        }

        fun parseTimesFromFileName(fileName: String): Pair<String, String>? {
            val parsed = parseFileName(fileName)
            if (parsed != null && parsed.startTime.isNotEmpty() && parsed.endTime.isNotEmpty()) {
                return Pair(parsed.startTime, parsed.endTime)
            }
            return null
        }
    }

    sealed class DownloadStatus(open val program: AirkastProgram) {
        data class Pending(override val program: AirkastProgram) : DownloadStatus(program)
        data class InProgress(override val program: AirkastProgram, val progress: Int) : DownloadStatus(program)
        data class Muxing(override val program: AirkastProgram, val progress: Int) : DownloadStatus(program)
        data class Completed(override val program: AirkastProgram, val file: File) : DownloadStatus(program)
        data class Error(override val program: AirkastProgram, val message: String) : DownloadStatus(program)
        data class Canceled(override val program: AirkastProgram) : DownloadStatus(program)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        client = (application as AirkastApplication).airkastClient
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createInitialNotification())
        pruneDownloadStates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val program = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROGRAM, AirkastProgram::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROGRAM)
                }
                val chunkSize = intent.getIntExtra(EXTRA_CHUNK_SIZE, 60)
                program?.let { startDownload(it, chunkSize) }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val programId = intent.getStringExtra(EXTRA_PROGRAM_ID)
                programId?.let { cancelDownload(it) }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * ダウンロードを開始する。すでにダウンロード中の場合はスキップ。
     * Semaphoreで最大同時実行数を制御し、各ダウンロードは独立したCoroutineで実行。
     */
    private fun startDownload(program: AirkastProgram, chunkSizeSeconds: Int) {
        // 重複チェック
        if (downloadJobs.containsKey(program.id)) {
            Log.d(TAG, "Already downloading: ${program.id}")
            return
        }

        // 状態をPendingに（Semaphore取得待ち）
        updateState(program.id, DownloadStatus.Pending(program))
        updateNotification()

        val outputFile = getDownloadFile(this, program)
        val job = serviceScope.launch {
            try {
                // Semaphore取得を待つ（最大同時実行数に達している場合はここでサスペンド）
                Log.d(TAG, "Waiting for semaphore: ${program.title}")
                downloadSemaphore.acquire()
                Log.d(TAG, "Semaphore acquired, starting: ${program.title}")

                // ダウンロード開始
                updateState(program.id, DownloadStatus.InProgress(program, 0))
                updateNotification()

                val hlsDownloader = HlsDownloader(client.client)

                val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.JAPAN)
                val startTime = format.parse(program.startTime)?.time ?: 0L
                val endTime = format.parse(program.endTime)?.time ?: 0L

                if (startTime == 0L || endTime == 0L || startTime >= endTime) {
                    throw Exception("Invalid program duration: start=$startTime, end=$endTime")
                }

                val downloadLsid = client.generateNewLsid()

                hlsDownloader.downloadChunked(
                    outputFile = outputFile,
                    startTime = startTime,
                    endTime = endTime,
                    chunkDurationSeconds = chunkSizeSeconds,
                    authToken = client.authToken ?: "",
                    urlGenerator = { s, e ->
                        val sStr = format.format(java.util.Date(s))
                        val eStr = format.format(java.util.Date(e))
                        client.generateHlsUrl(program, sStr, eStr, downloadLsid)
                    },
                    onProgress = { floatProgress ->
                        val intProgress = (floatProgress * 100).toInt()
                        updateState(program.id, DownloadStatus.InProgress(program, intProgress))
                        updateNotification()
                    },
                    onMuxingProgress = { floatProgress ->
                        val intProgress = (floatProgress * 100).toInt()
                        updateState(program.id, DownloadStatus.Muxing(program, intProgress))
                        updateNotification()
                    }
                )

                updateState(program.id, DownloadStatus.Completed(program, outputFile))
            } catch (e: CancellationException) {
                if (outputFile.exists()) outputFile.delete()
                updateState(program.id, DownloadStatus.Canceled(program))
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${program.title}: ${e.message}", e)
                if (outputFile.exists()) outputFile.delete()
                updateState(program.id, DownloadStatus.Error(program, e.message ?: "Unknown error"))
            } finally {
                downloadSemaphore.release()
                downloadJobs.remove(program.id)
                updateNotification()
                // 全ジョブ完了ならサービス停止
                if (downloadJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }
        downloadJobs[program.id] = job
    }

    private fun cancelDownload(programId: String) {
        downloadJobs[programId]?.cancel()
        downloadJobs.remove(programId)
        val currentState = _downloadStates.value[programId]
        val program = currentState?.program ?: AirkastProgram(
            "unknown", "Unknown", "Unknown",
            "20000101000000", "20000101010000", "", "", ""
        )

        updateState(programId, DownloadStatus.Canceled(program))

        // 関連ファイルのクリーンアップ
        val finalFile = getDownloadFile(this, program)
        listOf(
            finalFile,
            File(finalFile.absolutePath + ".adts"),
            File(finalFile.absolutePath + ".tmp_mux"),
            File(finalFile.absolutePath + ".raw"),
        ).forEach { f -> if (f.exists()) f.delete() }

        updateNotification()
        if (downloadJobs.isEmpty()) {
            stopSelf()
        }
    }

    private fun updateState(programId: String, status: DownloadStatus) {
        val currentMap = _downloadStates.value.toMutableMap()
        currentMap[programId] = status

        // Prune if map exceeds threshold
        if (currentMap.size > 50) {
            val finishedStates = currentMap.filterValues {
                it is DownloadStatus.Completed || it is DownloadStatus.Error || it is DownloadStatus.Canceled
            }.keys.toList()
            if (finishedStates.size > 20) {
                finishedStates.take(finishedStates.size - 20).forEach { currentMap.remove(it) }
            }
        }

        _downloadStates.value = currentMap
    }

    private fun pruneDownloadStates() {
        if (downloadJobs.isEmpty()) {
            val currentMap = _downloadStates.value.toMutableMap()
            val finishedKeys = currentMap.filterValues {
                it is DownloadStatus.Completed || it is DownloadStatus.Error || it is DownloadStatus.Canceled
            }.keys
            if (finishedKeys.isNotEmpty()) {
                finishedKeys.forEach { currentMap.remove(it) }
                _downloadStates.value = currentMap
            }
        }
    }

    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Radiko Downloader")
            .setContentText("Ready to download.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    private fun updateNotification() {
        val activeCount = downloadJobs.size
        val notification = if (activeCount > 0) {
            val progressStates = _downloadStates.value.filter { it.key in downloadJobs.keys }
            // アクティブなダウンロードの概要を構築
            val activeDescriptions = progressStates.entries.mapNotNull { (_, status) ->
                when (status) {
                    is DownloadStatus.Pending -> "${status.program.title}: 待機中"
                    is DownloadStatus.InProgress -> "${status.program.title}: DL ${status.progress}%"
                    is DownloadStatus.Muxing -> "${status.program.title}: 変換 ${status.progress}%"
                    else -> null
                }
            }
            val title = "ダウンロード中 (${activeCount}件)"
            val text = activeDescriptions.firstOrNull() ?: "処理中..."

            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .apply {
                    // 複数ダウンロード時はInboxStyleで一覧表示
                    if (activeDescriptions.size > 1) {
                        val inboxStyle = NotificationCompat.InboxStyle()
                            .setBigContentTitle(title)
                        activeDescriptions.forEach { inboxStyle.addLine(it) }
                        setStyle(inboxStyle)
                    }
                }
                .build()
        } else {
            createInitialNotification()
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
