package com.example.airkast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * プロセス内で「いま書き込み中の回」を予約する仕組み。
 * 手動ダウンロード([com.example.airkast.service.HlsDownloadService])と自動ダウンロード(Worker)が、
 * 同じ回を同じファイルパスへ同時に書き込んで壊し合うのを防ぐ。
 * キーは回の識別子 `AirkastProgram.episodeKey`(`局ID:開始時刻`)。
 */
object DownloadRegistry {
    private val _claimed = MutableStateFlow<Set<String>>(emptySet())

    /** いま予約されている回のキーの集合(画面の「自動取得中」表示などに使う)。 */
    val claimed: StateFlow<Set<String>> = _claimed.asStateFlow()

    /** [key] を予約する。取れたら true、すでに予約済みなら false。複数スレッドから呼んでも成功するのは 1 つだけ。 */
    fun tryClaim(key: String): Boolean {
        while (true) {
            val current = _claimed.value
            if (key in current) return false
            if (_claimed.compareAndSet(current, current + key)) return true
        }
    }

    /** [key] の予約を解放する。予約されていなくても何もしない。 */
    fun release(key: String) {
        _claimed.update { it - key }
    }

    fun isClaimed(key: String): Boolean = key in _claimed.value

    internal fun clearForTest() {
        _claimed.value = emptySet()
    }
}
