package com.example.airkast

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 再生位置をキャッシュするためのマネージャークラス。
 * SharedPreferencesを使用して、各ファイルの再生位置を永続化します。
 */
class PlaybackPositionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "playback_positions"
        private const val KEY_PREFIX_POSITION = "position_"
        private const val KEY_PREFIX_DURATION = "duration_"
        
        // 再生完了とみなす閾値（残り5秒以下なら完了扱い）
        private const val COMPLETION_THRESHOLD_MS = 5000L
    }

    /**
     * 指定されたメディアIDの再生位置を保存します。
     * @param mediaId メディアID（通常はprogram.id）
     * @param positionMs 再生位置（ミリ秒）
     * @param durationMs 総再生時間（ミリ秒）
     */
    fun savePosition(mediaId: String, positionMs: Long, durationMs: Long) {
        // 再生がほぼ完了している場合は位置をクリア
        if (durationMs > 0 && positionMs >= durationMs - COMPLETION_THRESHOLD_MS) {
            clearPosition(mediaId)
            return
        }
        
        prefs.edit {
            putLong("$KEY_PREFIX_POSITION$mediaId", positionMs)
            putLong("$KEY_PREFIX_DURATION$mediaId", durationMs)
        }
    }

    /**
     * 指定されたメディアIDの再生位置を取得します。
     * @param mediaId メディアID
     * @return 保存された再生位置（ミリ秒）、なければ0
     */
    fun getPosition(mediaId: String): Long {
        return prefs.getLong("$KEY_PREFIX_POSITION$mediaId", 0L)
    }

    /**
     * 指定されたメディアIDの総再生時間を取得します。
     * @param mediaId メディアID
     * @return 保存された総再生時間（ミリ秒）、なければ0
     */
    fun getDuration(mediaId: String): Long {
        return prefs.getLong("$KEY_PREFIX_DURATION$mediaId", 0L)
    }

    /**
     * 指定されたメディアIDの再生位置をクリアします。
     * @param mediaId メディアID
     */
    fun clearPosition(mediaId: String) {
        prefs.edit {
            remove("$KEY_PREFIX_POSITION$mediaId")
            remove("$KEY_PREFIX_DURATION$mediaId")
        }
    }
}
