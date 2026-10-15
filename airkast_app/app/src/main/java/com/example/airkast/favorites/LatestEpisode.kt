package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat

/** 番組の (開始, 終了) をミリ秒で返す。時刻を解釈できなければ null。 */
internal fun AirkastProgram.timeRangeMs(): Pair<Long, Long>? {
    val format = radikoTimestampFormat()
    val start = runCatching { format.parse(startTime)?.time }.getOrNull()
    val end = runCatching { format.parse(endTime)?.time }.getOrNull()
    return if (start != null && end != null) start to end else null
}

/** 放送終了から [FavoriteMatcher.END_MARGIN_MS] 以上経ち、タイムフリーで取得できる状態か。 */
internal fun AirkastProgram.hasEndedForDownload(nowMs: Long): Boolean {
    val (_, end) = timeRangeMs() ?: return false
    return end <= nowMs - FavoriteMatcher.END_MARGIN_MS
}

/**
 * [programs] から、[stationId] と正規化済みの番組名 [titleKey] が一致し、取得できる状態になった
 * 最新の回(放送開始が最も新しい)を返す。なければ null。お気に入り画面の「最新回」に使う。
 */
fun latestEpisode(
    programs: List<AirkastProgram>,
    stationId: String,
    titleKey: String,
    nowMs: Long,
): AirkastProgram? = programs
    .filter { it.stationId == stationId && normalizeProgramTitle(it.title) == titleKey }
    .filter { it.hasEndedForDownload(nowMs) }
    .maxByOrNull { it.startTime }
