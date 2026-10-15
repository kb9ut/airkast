package com.example.airkast.favorites

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.airkast.AirkastClient
import com.example.airkast.AirkastProgram
import com.example.airkast.DownloadRunner
import com.example.airkast.service.HlsDownloadService
import kotlinx.coroutines.CancellationException
import java.io.File

private const val TAG = "FavoriteSyncWorker"
private const val CHANNEL_ID = "FavoriteSyncChannel"
private const val PROGRESS_NOTIFICATION_ID = 2
private const val DONE_NOTIFICATION_BASE_ID = 1000
private const val DONE_NOTIFICATION_TAG = "favorite_done"

/**
 * 約 6 時間ごとに、お気に入り番組の新しい回を自動ダウンロードする。
 * 可能ならフォアグラウンドに昇格して実行し、昇格できない場合は通常の実行枠で続行する。
 */
class FavoriteSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("お気に入りを確認中", "")

    override suspend fun doWork(): Result {
        val repository = FavoriteRepository(PrefsTextStorage(applicationContext))
        if (!repository.hasAutoDownload()) return Result.success()

        createNotificationChannel()
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            // CancellationException は IllegalStateException のサブクラスのため先に再スローする
            throw e
        } catch (e: IllegalStateException) {
            // バックグラウンドからのフォアグラウンド昇格は Android 12 以降で失敗しうるため、失敗しても通常の実行枠で続行する
            Log.w(TAG, "フォアグラウンドに昇格できませんでした。通常の実行枠で続行します: ${e.message}")
        }

        // UI と共有の AirkastClient を使うと、再認証の途中で UI 側のトークンを壊しうるため、Worker 専用のクライアントを使う
        val client = AirkastClient()
        try {
            return syncWith(client, repository)
        } finally {
            client.client.close()
        }
    }

    private suspend fun syncWith(client: AirkastClient, repository: FavoriteRepository): Result {
        try {
            client.authenticate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "認証に失敗: ${e.message}")
            return Result.retry()
        }

        val sync = FavoriteSync(
            repository = repository,
            guides = ClientGuideSource(client),
            downloader = RunnerEpisodeDownloader(applicationContext, DownloadRunner(client)),
            downloadedFiles = { downloadedFiles() },
            isDownloading = { isDownloadingManually(it) },
        )
        val report = try {
            sync.run { program, percent ->
                notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    progressNotification("${program.title} をダウンロード中", "$percent%"),
                )
            }
        } finally {
            // キャンセルや例外で中断しても、進捗通知を残さない
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        }
        report.downloaded.forEach { notifyDownloaded(it) }
        return if (report.allStationsFailed) Result.retry() else Result.success()
    }

    private fun downloadedFiles(): List<File> =
        applicationContext.getExternalFilesDir("downloads")?.listFiles()?.toList().orEmpty()

    /** 画面から手動ダウンロード中の回を、自動ダウンロードが重複して始めないための判定。 */
    private fun isDownloadingManually(program: AirkastProgram): Boolean =
        when (HlsDownloadService.downloadStates.value[program.id]) {
            is HlsDownloadService.DownloadStatus.Pending,
            is HlsDownloadService.DownloadStatus.InProgress,
            is HlsDownloadService.DownloadStatus.Muxing -> true
            else -> false
        }

    private fun notifyDownloaded(program: AirkastProgram) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("ダウンロード完了")
            .setContentText(program.title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val id = DONE_NOTIFICATION_BASE_ID + (program.id.hashCode() and 0xFFFF)
        // media3 の通知 ID(1001 など)と衝突しないよう、タグで名前空間を分ける
        notificationManager.notify(DONE_NOTIFICATION_TAG, id, notification)
    }

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        val notification = progressNotification(title, text)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun progressNotification(title: String, text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "お気に入りの自動ダウンロード", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/** 実際の radiko 番組表を取得する [GuideSource]。 */
internal class ClientGuideSource(private val client: AirkastClient) : GuideSource {
    override suspend fun programs(stationId: String, date: String): List<AirkastProgram> =
        client.getProgramGuide(stationId, date)
}

/** [DownloadRunner] で所定のファイルにダウンロードする [EpisodeDownloader]。失敗時は部分ファイルを消す。 */
internal class RunnerEpisodeDownloader(
    private val context: Context,
    private val runner: DownloadRunner,
) : EpisodeDownloader {
    override suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit) {
        val outputFile = HlsDownloadService.getDownloadFile(context, program)
        try {
            runner.run(program, outputFile, DownloadRunner.DEFAULT_CHUNK_SIZE_SECONDS, onProgress)
        } catch (e: Throwable) {
            HlsDownloadService.partialFiles(outputFile).forEach { if (it.exists()) it.delete() }
            throw e
        }
    }
}
