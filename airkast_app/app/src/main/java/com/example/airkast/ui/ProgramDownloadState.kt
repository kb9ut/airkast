package com.example.airkast.ui

import com.example.airkast.service.HlsDownloadService.DownloadStatus

/**
 * 番組行のダウンロードボタンが描画する状態。
 *
 * UI は [resolveProgramDownloadState] が返すこの値「だけ」から描画する(ボタン自身は
 * サービスやマップを参照しない)。状態の判定ルールを変えたいときはこの関数だけを直せばよい。
 */
sealed interface ProgramDownloadState {
    /** 未取得(押すと開始)。 */
    data object Idle : ProgramDownloadState

    /** 待機中(セマフォ待ち等)。 */
    data object Queued : ProgramDownloadState

    /** ダウンロード中。[progress] は 0..100。 */
    data class Downloading(val progress: Int) : ProgramDownloadState

    /** 変換(mux)中。[progress] は 0..100。 */
    data class Converting(val progress: Int) : ProgramDownloadState

    /** 取得済み。 */
    data object Completed : ProgramDownloadState

    /** 失敗(押すと再試行)。 */
    data class Failed(val message: String) : ProgramDownloadState
}

/**
 * サービスの [DownloadStatus](なければ null)と「保存済みファイルがあるか」から表示状態を決める。
 *
 * - 進行中(Pending/InProgress/Muxing)の間だけスピナー/進捗を出す。
 * - Completed が残っていても進行中扱いにしない(スピナーが止まらない問題の回避)。
 * - Canceled/null は、ファイルがあれば取得済み、なければ未取得。
 */
fun resolveProgramDownloadState(
    status: DownloadStatus?,
    isFileDownloaded: Boolean,
): ProgramDownloadState = when (status) {
    is DownloadStatus.Pending -> ProgramDownloadState.Queued
    is DownloadStatus.InProgress -> ProgramDownloadState.Downloading(status.progress)
    is DownloadStatus.Muxing -> ProgramDownloadState.Converting(status.progress)
    is DownloadStatus.Completed -> ProgramDownloadState.Completed
    is DownloadStatus.Error ->
        if (isFileDownloaded) ProgramDownloadState.Completed
        else ProgramDownloadState.Failed(status.message)
    is DownloadStatus.Canceled, null ->
        if (isFileDownloaded) ProgramDownloadState.Completed else ProgramDownloadState.Idle
}
