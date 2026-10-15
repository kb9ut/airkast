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
import com.example.airkast.AirkastProgram
import com.example.airkast.DownloadRegistry
import com.example.airkast.DownloadRunner
import com.example.airkast.favorites.episodeKey
import com.example.airkast.getParcelableExtraCompat
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
        internal const val MAX_TRACKED_STATES = 50
        internal const val KEPT_FINISHED_STATES = 20

        // ダウンロード状態の管理 (Static access for ViewModel)
        private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
        val downloadStates = _downloadStates.asStateFlow()

        fun getDownloadFile(context: Context, program: AirkastProgram): File {
            val safeTitle = program.title.replace(Regex("[/:\\\\*?\"<>|]"), "_")
            val stationId = program.stationId.ifEmpty { "unknown" }
            val fileName = "${stationId}_${safeTitle}_${program.startTime}_${program.endTime}.m4a"
            return File(context.getExternalFilesDir("downloads"), fileName)
        }

        /** ダウンロード中に生成される一時ファイルを含む、[finalFile] に関連する全ファイル。失敗/キャンセル時の掃除に使う。 */
        fun partialFiles(finalFile: File): List<File> = listOf(
            finalFile,
            File(finalFile.absolutePath + ".adts"),
            File(finalFile.absolutePath + ".tmp_mux"),
            File(finalFile.absolutePath + ".raw"),
        )

        /** [files] をすべて削除する。存在しないファイルは無視する。失敗/キャンセル時の掃除に使う。 */
        fun deleteAll(files: List<File>) {
            files.forEach { f -> if (f.exists()) f.delete() }
        }

        /**
         * 状態数が [MAX_TRACKED_STATES] を超えたとき、完了済みの状態を古い順に捨てて
         * [KEPT_FINISHED_STATES] 件まで減らす。進行中の状態は捨てない。
         */
        fun pruneStates(states: Map<String, DownloadStatus>): Map<String, DownloadStatus> {
            if (states.size <= MAX_TRACKED_STATES) return states
            val finishedKeys = states.filterValues { it.isFinished() }.keys.toList()
            if (finishedKeys.size <= KEPT_FINISHED_STATES) return states
            val dropKeys = finishedKeys.take(finishedKeys.size - KEPT_FINISHED_STATES).toSet()
            return states.filterKeys { it !in dropKeys }
        }

        /** 完了・失敗・中止の状態をすべて取り除く。 */
        fun withoutFinished(states: Map<String, DownloadStatus>): Map<String, DownloadStatus> =
            states.filterValues { !it.isFinished() }

        /** 通知に表示する、進行中ダウンロードごとの 1 行説明。完了済みの状態は含めない。 */
        fun describeActive(statuses: Collection<DownloadStatus>): List<String> =
            statuses.mapNotNull { status ->
                when (status) {
                    is DownloadStatus.Pending -> "${status.program.title}: 待機中"
                    is DownloadStatus.InProgress -> "${status.program.title}: DL ${status.progress}%"
                    is DownloadStatus.Muxing -> "${status.program.title}: 変換 ${status.progress}%"
                    else -> null
                }
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
    }

    sealed class DownloadStatus(open val program: AirkastProgram) {
        /** 完了・失敗・中止のいずれか (これ以上進行しない状態)。 */
        fun isFinished(): Boolean =
            this is Completed || this is Error || this is Canceled

        /** 待機中・ダウンロード中・変換中のいずれか。 */
        fun isActive(): Boolean = !isFinished()

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
                val program = intent.getParcelableExtraCompat<AirkastProgram>(EXTRA_PROGRAM)
                val chunkSize = intent.getIntExtra(EXTRA_CHUNK_SIZE, DownloadRunner.DEFAULT_CHUNK_SIZE_SECONDS)
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

        // 自動ダウンロードや、中止処理の途中のジョブなど、他の処理が同じ回を書き込み中なら開始しない
        if (!DownloadRegistry.tryClaim(program.episodeKey)) {
            Log.d(TAG, "他の処理が書き込み中のためスキップ: ${program.id}")
            // 動かすジョブが無いのにフォアグラウンドサービスだけ残らないようにする
            if (downloadJobs.isEmpty()) stopSelf()
            return
        }

        // 状態をPendingに（Semaphore取得待ち）
        updateState(program.id, DownloadStatus.Pending(program))
        updateNotification()

        val outputFile = getDownloadFile(this, program)
        val job = serviceScope.launch {
            var semaphoreAcquired = false
            try {
                // Semaphore取得を待つ（最大同時実行数に達している場合はここでサスペンド）
                Log.d(TAG, "Waiting for semaphore: ${program.title}")
                downloadSemaphore.acquire()
                semaphoreAcquired = true
                Log.d(TAG, "Semaphore acquired, starting: ${program.title}")

                runDownload(program, chunkSizeSeconds, outputFile)
                updateState(program.id, DownloadStatus.Completed(program, outputFile))
            } catch (e: CancellationException) {
                deleteAll(partialFiles(outputFile))
                updateState(program.id, DownloadStatus.Canceled(program))
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${program.title}: ${e.message}", e)
                deleteAll(partialFiles(outputFile))
                updateState(program.id, DownloadStatus.Error(program, e.message ?: "Unknown error"))
            } finally {
                // 取得前にキャンセルされた場合は release しない (許可数超過で IllegalStateException になる)
                if (semaphoreAcquired) downloadSemaphore.release()
                downloadJobs.remove(program.id)
                updateNotification()
                // 全ジョブ完了ならサービス停止
                if (downloadJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }
        // 予約の解放はジョブの完了時に行う。ジョブが開始前にキャンセルされても必ず呼ばれ、
        // 後始末(catch 内のファイル削除)が終わった後に解放される。
        job.invokeOnCompletion { DownloadRegistry.release(program.episodeKey) }
        downloadJobs[program.id] = job
    }

    /**
     * 番組を [outputFile] にダウンロードします。進捗は状態フローと通知に反映されます。
     */
    private suspend fun runDownload(program: AirkastProgram, chunkSizeSeconds: Int, outputFile: File) {
        updateState(program.id, DownloadStatus.InProgress(program, 0))
        updateNotification()

        DownloadRunner(client).run(
            program = program,
            outputFile = outputFile,
            chunkSizeSeconds = chunkSizeSeconds,
            onProgress = { percent ->
                updateState(program.id, DownloadStatus.InProgress(program, percent))
                updateNotification()
            },
            onMuxingProgress = { percent ->
                updateState(program.id, DownloadStatus.Muxing(program, percent))
                updateNotification()
            },
        )
    }

    private fun cancelDownload(programId: String) {
        val job = downloadJobs.remove(programId)
        job?.cancel()
        val currentState = _downloadStates.value[programId]
        val program = currentState?.program ?: AirkastProgram(
            id = programId,
            stationId = "Unknown",
            startTime = "20000101000000",
            endTime = "20000101010000",
            title = "Unknown",
            description = "",
            performer = "",
            imageUrl = null
        )

        updateState(programId, DownloadStatus.Canceled(program))

        // 関連ファイルのクリーンアップ。ジョブがあれば、ジョブ自身の catch で(予約を解放する前に)削除する。
        // ここで削除すると、ジョブの後始末と予約の解放が先に終わった後に、別の処理(Worker)が
        // 取り直した予約の下で書き込み中のファイルを消してしまうことがある。
        if (job == null) {
            val finalFile = getDownloadFile(this, program)
            deleteAll(partialFiles(finalFile))
        }

        updateNotification()
        if (downloadJobs.isEmpty()) {
            stopSelf()
        }
    }

    private fun updateState(programId: String, status: DownloadStatus) {
        _downloadStates.value = pruneStates(_downloadStates.value + (programId to status))
    }

    /** サービス起動時、実行中のダウンロードがなければ完了済みの状態を破棄します。 */
    private fun pruneDownloadStates() {
        if (downloadJobs.isEmpty()) {
            val current = _downloadStates.value
            val remaining = withoutFinished(current)
            if (remaining.size != current.size) {
                _downloadStates.value = remaining
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
            val activeDescriptions = describeActive(progressStates.values)
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
