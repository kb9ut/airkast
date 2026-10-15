package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import java.text.Normalizer

/**
 * お気に入り番組。同一性は「放送局ID + 正規化した番組名」で決まる。
 * 曜日や時刻は持たない(放送時間が変わっても追従するため)。
 */
data class Favorite(
    val stationId: String,
    val title: String,
    val autoDownload: Boolean = true,
) {
    /** 照合用の正規化済み番組名 */
    val key: String get() = normalizeProgramTitle(title)
}

/** 全角/半角を統一(NFKC)し、前後の空白を除いて連続する空白を 1 つにする。 */
fun normalizeProgramTitle(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

/** 回を一意に識別するキー(失敗回数の記録に使う)。 */
val AirkastProgram.episodeKey: String
    get() = "$stationId:$startTime"
