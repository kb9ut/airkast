package com.example.airkast

/**
 * UIの状態を表すジェネリックなシールクラス。
 * @param T 成功時に保持するデータの型。
 */
sealed class UiState<out T> {
    /**
     * 初期状態またはアイドル状態。
     */
    object Idle : UiState<Nothing>()

    /**
     * データ読み込み中。
     */
    object Loading : UiState<Nothing>()

    /**
     * データ読み込み成功。
     * @param data 取得したデータ。
     */
    data class Success<out T>(val data: T) : UiState<T>()

    /**
     * データ読み込み失敗。
     * @param error 発生したエラー。
     */
    data class Error(val error: Throwable) : UiState<Nothing>()
}

enum class TimeFilter(val label: String) {
    EARLY_MORNING("早朝 (5-9)"),
    DAYTIME("昼 (9-17)"),
    NIGHT("夜 (17-24)"),
    MIDNIGHT("深夜 (24-29)")
}
