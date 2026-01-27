package com.example.airkast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastClient
import com.example.airkast.HlsDownloader
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class HlsDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var notificationManager: NotificationManager
    private lateinit var client: AirkastClient

    // 各ダウンロードのJobを保持する
    private val downloadJobs = mutableMapOf<String, Job>()
    
    // ダウンロードキュー（並列ダウンロードによるファイル破損を防ぐため、直列処理にする）
    private val downloadQueue = mutableListOf<Pair<AirkastProgram, Int>>()
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()
    private var isProcessingQueue = false

    companion object {
        const val ACTION_START_DOWNLOAD = "ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_PROGRAM = "EXTRA_PROGRAM"
        const val EXTRA_PROGRAM_ID = "EXTRA_PROGRAM_ID"
        const val EXTRA_CHUNK_SIZE = "EXTRA_CHUNK_SIZE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "HlsDownloadServiceChannel"

        // ダウンロード状態の管理 (Static access for ViewModel)
        private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
        val downloadStates = _downloadStates.asStateFlow()

        fun getDownloadFile(context: Context, program: AirkastProgram): File {
            // ファイル名に使えない文字を置換
            val safeTitle = program.title.replace(Regex("[/:\\\\*?\"<>|]"), "_")
            val stationId = program.stationId.ifEmpty { "unknown" }
            // 形式: 放送局ID_番組名_開始時刻_終了時刻.m4a
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
                    // タイトルにアンダースコアが含まれる可能性があるため、中間を結合
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

        // Keep for backward compatibility if needed, but we'll use parseFileName
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
        
        // Prune the static map if it gets too large
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
                val chunkSize = intent.getIntExtra(EXTRA_CHUNK_SIZE, 60) // Default 60s
                program?.let { startDownload(it, chunkSize) }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val programId = intent.getStringExtra(EXTRA_PROGRAM_ID)
                programId?.let { cancelDownload(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(program: AirkastProgram, chunkSizeSeconds: Int) {
        // すでに同じ番組がダウンロード中またはキューにある場合はスキップ
        if (downloadJobs.containsKey(program.id)) return
        if (downloadQueue.any { it.first.id == program.id }) return
        
        // 状態をPendingに更新
        updateState(program.id, DownloadStatus.Pending(program))
        
        // キューに追加
        downloadQueue.add(Pair(program, chunkSizeSeconds))
        
        // キュー処理を開始（まだ開始していない場合）
        processNextInQueue()
        
        updateNotification()
    }
    
    private fun processNextInQueue() {
        if (isProcessingQueue) return
        if (downloadQueue.isEmpty()) {
            // キューが空になったらサービスを停止
            if (downloadJobs.isEmpty()) {
                stopSelf()
            }
            return
        }
        
        isProcessingQueue = true
        val (program, chunkSizeSeconds) = downloadQueue.removeAt(0)
        
        if (downloadJobs.containsKey(program.id)) {
            // 既にダウンロード中（通常は発生しないはず）
            isProcessingQueue = false
            processNextInQueue()
            return
        }
        
        val outputFile = getDownloadFile(this, program)
        val downloadJob = serviceScope.launch {
            try {
                // HlsDownloaderのインスタンス化
                val hlsDownloader = HlsDownloader(client.client)

                // 状態を更新
                updateState(program.id, DownloadStatus.InProgress(program, 0))
                updateNotification()
                
                // 時間をパース
                val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.JAPAN)
                val startTime = format.parse(program.startTime)?.time ?: 0L
                val endTime = format.parse(program.endTime)?.time ?: 0L
                
                if (startTime == 0L || endTime == 0L || startTime >= endTime) {
                     throw Exception("Invalid program duration: start=$startTime, end=$endTime")
                }

                val downloadLsid = client.generateNewLsid() // Generate lsid once for the entire download

                // ダウンロード実行 (分割ダウンロード)
                hlsDownloader.downloadChunked(
                    outputFile = outputFile,
                    startTime = startTime,
                    endTime = endTime,
                    chunkDurationSeconds = chunkSizeSeconds, // ユーザー指定のサイズを使用
                    authToken = client.authToken ?: "",
                    urlGenerator = { s, e ->
                        val sStr = format.format(java.util.Date(s))
                        val eStr = format.format(java.util.Date(e))
                        client.generateHlsUrl(program, sStr, eStr, downloadLsid) // Pass the consistent lsid
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
                // ジョブがキャンセルされた場合
                if (outputFile.exists()) outputFile.delete()
                updateState(program.id, DownloadStatus.Canceled(program))
            } catch (e: Exception) {
                e.printStackTrace()
                if (outputFile.exists()) outputFile.delete()
                updateState(program.id, DownloadStatus.Error(program, e.message ?: "Unknown error"))
            } finally {
                downloadJobs.remove(program.id)
                isProcessingQueue = false
                updateNotification()
                // 次のダウンロードを開始
                processNextInQueue()
            }
        }
        downloadJobs[program.id] = downloadJob
        updateNotification()
    }

    private fun cancelDownload(programId: String) {
        // キューからも削除
        downloadQueue.removeAll { it.first.id == programId }
        
        downloadJobs[programId]?.cancel()
        downloadJobs.remove(programId)
        val currentState = _downloadStates.value[programId]
        val program = currentState?.program ?: AirkastProgram("unknown", "Unknown", "Unknown", "20000101000000", "20000101010000", "", "", "")
        
        updateState(programId, DownloadStatus.Canceled(program))
        updateState(programId, DownloadStatus.Canceled(program))
        
        // 関連するファイルのクリーンアップ
        val finalFile = getDownloadFile(this, program)
        if (finalFile.exists()) finalFile.delete()
        
        // 一時ファイルの削除 (.adts)
        val adtsFile = File(finalFile.absolutePath + ".adts")
        if (adtsFile.exists()) adtsFile.delete()
        
        // 一時Muxファイルの削除 (.tmp_mux)
        val tempMuxFile = File(finalFile.absolutePath + ".tmp_mux")
        if (tempMuxFile.exists()) tempMuxFile.delete()
        
        // Rawファイルの削除 (.raw) - 後方互換性のため念のためチェック
        val rawFile = File(finalFile.absolutePath + ".raw")
        if (rawFile.exists()) rawFile.delete()
        updateNotification()
        if (downloadJobs.isEmpty() && downloadQueue.isEmpty()) {
            stopSelf()
        }
    }

    private fun updateState(programId: String, status: DownloadStatus) {
        // synchronized block to ensure thread safety for map updates, though MutableStateFlow is thread-safe for value setting,
        // we are doing map addition which is copy-on-write
        val currentMap = _downloadStates.value.toMutableMap()
        currentMap[programId] = status
        
        // Prune if map exceeds threshold (e.g., 50 items)
        if (currentMap.size > 50) {
            val finishedStates = currentMap.filterValues { 
                it is DownloadStatus.Completed || it is DownloadStatus.Error || it is DownloadStatus.Canceled 
            }.keys.toList()
            
            // Remove oldest finished states to keep size under control
            if (finishedStates.size > 20) {
                finishedStates.take(finishedStates.size - 20).forEach { currentMap.remove(it) }
            }
        }
        
        _downloadStates.value = currentMap
    }

    private fun pruneDownloadStates() {
        // Clear all terminal states if no active downloads are occurring when service starts
        if (downloadJobs.isEmpty() && downloadQueue.isEmpty()) {
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
        val ongoingDownloads = downloadJobs.size
        val notification = if (ongoingDownloads > 0) {
            val progressStates = _downloadStates.value.filter { it.key in downloadJobs.keys }
            val firstActive = progressStates.entries.firstOrNull { 
                it.value is DownloadStatus.InProgress || it.value is DownloadStatus.Muxing 
            }
            val title = "Processing ($ongoingDownloads files)"
            val text = when (val status = firstActive?.value) {
                is DownloadStatus.InProgress -> "${status.program.title}: Downloading ${status.progress}%"
                is DownloadStatus.Muxing -> "${status.program.title}: Finalizing ${status.progress}%"
                else -> "Waiting for queue..."
            }

            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
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