package com.example.airkast.favorites

import android.util.Log
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FavoriteSync"

/** 番組表の取得元。 [date] は `yyyyMMdd`。 */
interface GuideSource {
    suspend fun programs(stationId: String, date: String): List<AirkastProgram>
}

/** 1 回分のダウンロード。失敗時は例外を投げる。 */
interface EpisodeDownloader {
    suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit)
}

data class SyncReport(
    val downloaded: List<AirkastProgram>,
    val failed: List<AirkastProgram>,
    val stationsTotal: Int,
    val stationsFailed: Int,
) {
    /** すべての局の番組表取得が失敗した(オフライン・エリア外など)。 */
    val allStationsFailed: Boolean get() = stationsTotal > 0 && stationsFailed == stationsTotal
}

/** [nowMs] から過去 [days] 日分(今日を含む)の `yyyyMMdd` を新しい順に返す。 */
internal fun recentDates(nowMs: Long, days: Int = 8): List<String> {
    val format = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
    return (0 until days).map { format.format(Date(nowMs - it * 86_400_000L)) }
}

/**
 * お気に入りの新しい回を探してダウンロードする手順。
 * 実際のネットワーク・ファイル・時計は引数で差し替えられるので、JVM でテストできる。
 */
class FavoriteSync(
    private val repository: FavoriteRepository,
    private val guides: GuideSource,
    private val downloader: EpisodeDownloader,
    private val downloadedFiles: () -> List<File>,
    private val isDownloading: (AirkastProgram) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onProgress: (AirkastProgram, Int) -> Unit = { _, _ -> }): SyncReport {
        val favorites = repository.favorites().filter { it.autoDownload }
        if (favorites.isEmpty()) return SyncReport(emptyList(), emptyList(), 0, 0)

        val nowMs = now()
        val dates = recentDates(nowMs)
        val stations = favorites.map { it.stationId }.distinct()

        val programs = mutableListOf<AirkastProgram>()
        var stationsFailed = 0
        for (station in stations) {
            var succeeded = 0
            for (date in dates) {
                try {
                    programs += guides.programs(station, date)
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "番組表の取得に失敗: $station $date: ${e.message}")
                }
            }
            if (succeeded == 0) stationsFailed++
        }

        val candidates = FavoriteMatcher
            .findNewEpisodes(favorites, programs, nowMs, downloadedFiles(), repository.failures())
            .filterNot { isDownloading(it) }

        val downloaded = mutableListOf<AirkastProgram>()
        val failed = mutableListOf<AirkastProgram>()
        for (program in candidates) {
            try {
                downloader.download(program) { onProgress(program, it) }
                repository.clearFailure(program.episodeKey)
                downloaded += program
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "自動ダウンロードに失敗: ${program.title}: ${e.message}")
                repository.recordFailure(program.episodeKey)
                failed += program
            }
        }
        return SyncReport(downloaded, failed, stations.size, stationsFailed)
    }
}
