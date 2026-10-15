package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.isProgramDownloaded
import java.io.File

/** お気に入りと番組表から、自動ダウンロードすべき「新しい回」を判定する純粋関数。 */
object FavoriteMatcher {
    /** タイムフリーに反映されるまでの余裕 */
    const val END_MARGIN_MS = 5 * 60 * 1000L

    /** タイムフリーで聴ける期間 */
    const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    /** 1 つの回に対する自動ダウンロードの試行上限 */
    const val MAX_ATTEMPTS = 3

    /**
     * 次をすべて満たす番組を放送開始の古い順に返す。
     * 1. 自動DL ON のお気に入りと、局ID・正規化した番組名が一致する
     * 2. 放送終了が (現在 − [END_MARGIN_MS]) 以前
     * 3. 放送開始が (現在 − [WINDOW_MS]) 以降
     * 4. 取得済みでない
     * 5. 失敗回数が [MAX_ATTEMPTS] 未満
     */
    fun findNewEpisodes(
        favorites: List<Favorite>,
        programs: List<AirkastProgram>,
        nowMs: Long,
        downloadedFiles: List<File>,
        failures: Map<String, Int>,
    ): List<AirkastProgram> {
        val targets = favorites.filter { it.autoDownload }.map { it.stationId to it.key }.toSet()
        if (targets.isEmpty()) return emptyList()

        return programs
            .distinctBy { Triple(it.stationId, it.startTime, it.endTime) }
            .filter { (it.stationId to normalizeProgramTitle(it.title)) in targets }
            .filter { program ->
                val (start, _) = program.timeRangeMs() ?: return@filter false
                program.hasEndedForDownload(nowMs) && start >= nowMs - WINDOW_MS
            }
            .filterNot { isProgramDownloaded(downloadedFiles, it) }
            .filter { (failures[it.episodeKey] ?: 0) < MAX_ATTEMPTS }
            .sortedBy { it.startTime }
    }
}
