package com.example.airkast.service

/**
 * MainViewModel (MediaController) と PlayerService (MediaLibraryService) の間で
 * やり取りするカスタムセッションコマンドと Bundle キーの定義。
 */
object PlayerCommands {
    /** ローカルファイルを再生する。引数: [KEY_FILE_PATH], [KEY_PROGRAM] */
    const val PLAY_FROM_FILE = "custom_command_play_from_file"

    /** タイムフリーHLSをストリーミング再生する。引数: [KEY_URL], [KEY_AUTH_TOKEN], [KEY_PROGRAM] */
    const val PLAY_STREAM = "custom_command_play_stream"

    /** 再生中メディアの長さを取得する。結果: [KEY_DURATION_MS] */
    const val GET_DURATION = "custom_command_get_duration"

    const val KEY_FILE_PATH = "file_path"
    const val KEY_PROGRAM = "program"
    const val KEY_URL = "url"
    const val KEY_AUTH_TOKEN = "auth_token"
    const val KEY_DURATION_MS = "duration_ms"
}
