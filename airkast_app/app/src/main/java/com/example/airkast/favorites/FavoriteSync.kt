package com.example.airkast.favorites

import android.util.Log
import com.example.airkast.AirkastProgram
import com.example.airkast.isProgramDownloaded
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
    /** 回の書き込み権を予約する。取れなければ(他の処理が書き込み中)その回はスキップする。 */
    private val claim: (AirkastProgram) -> Boolean = { true },
    /** [claim] で取った予約を解放する。 */
    private val release: (AirkastProgram) -> Unit = {},
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

        val downloaded = mutableListOf<AirkastProgram>()
        val failed = mutableListOf<AirkastProgram>()
        for (program in candidates) {
            // 実行中に自動DL が OFF にされた/お気に入りが外された場合は、残りの回を予約も失敗の記録もせずにスキップする
            // (すでにダウンロード中の回は完走させる)
            if (!stillAutoDownloads(program)) continue
            // 手動ダウンロードなど他の処理が書き込み中なら、その回は次の周期に回す(失敗には数えない)
            if (!claim(program)) continue
            try {
                // 予約を取るまでの間に別の処理が保存を終えているかもしれないので、予約後に再確認する
                if (isProgramDownloaded(downloadedFiles(), program)) continue
                // 試行は開始前に数える。中断(キャンセル/プロセス終了)された試行も MAX_ATTEMPTS に含め、
                // 長すぎる回が古い順の先頭で毎回後続をブロックし続けないようにする
                repository.recordFailure(program.episodeKey)
                try {
                    downloader.download(program) { onProgress(program, it) }
                    repository.clearFailure(program.episodeKey)
                    downloaded += program
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "自動ダウンロードに失敗: ${program.title}: ${e.message}")
                    failed += program
                }
            } finally {
                release(program)
            }
        }
        return SyncReport(downloaded, failed, stations.size, stationsFailed)
    }

    /** [program] に一致する、自動DL が ON のお気に入りが現時点でもあるか(実行開始時のコピーではなく最新を読む)。 */
    private fun stillAutoDownloads(program: AirkastProgram): Boolean {
        val key = normalizeProgramTitle(program.title)
        return repository.favorites().any { it.autoDownload && it.stationId == program.stationId && it.key == key }
    }
}
