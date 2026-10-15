package com.example.airkast.favorites

import android.content.Context

/** 文字列の保存先。SharedPreferences を直接使うと JVM テストできないため抽象化する。 */
interface TextStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class PrefsTextStorage(context: Context) : TextStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("airkast_favorites", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * お気に入りと、自動ダウンロードの失敗回数を保存する。
 * 保存形式はタブ区切りの行テキスト:
 * - favorites: `局ID \t 番組名 \t 自動DL(1/0)`
 * - failures:  `回のキー \t 失敗回数`
 * UI と Worker(別スレッド)の両方から使われるため、変更系は同期する。
 */
class FavoriteRepository(private val storage: TextStorage) {

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FAILURES = "failures"
    }

    @Synchronized
    fun favorites(): List<Favorite> = parseLines(storage.read(KEY_FAVORITES)) { cols ->
        if (cols.size != 3 || cols[0].isBlank() || cols[1].isBlank()) return@parseLines null
        val auto = when (cols[2]) {
            "1" -> true
            "0" -> false
            else -> return@parseLines null
        }
        Favorite(cols[0], cols[1], auto)
    }

    @Synchronized
    fun isFavorite(stationId: String, title: String): Boolean =
        favorites().any { it.matches(stationId, title) }

    @Synchronized
    fun add(stationId: String, title: String) {
        val current = favorites()
        if (current.any { it.matches(stationId, title) }) return
        saveFavorites(current + Favorite(stationId, sanitize(title)))
    }

    @Synchronized
    fun remove(stationId: String, title: String) {
        saveFavorites(favorites().filterNot { it.matches(stationId, title) })
    }

    @Synchronized
    fun setAutoDownload(stationId: String, title: String, enabled: Boolean) {
        saveFavorites(favorites().map {
            if (it.matches(stationId, title)) it.copy(autoDownload = enabled) else it
        })
    }

    @Synchronized
    fun hasAutoDownload(): Boolean = favorites().any { it.autoDownload }

    @Synchronized
    fun failures(): Map<String, Int> = parseLines(storage.read(KEY_FAILURES)) { cols ->
        val count = cols.getOrNull(1)?.toIntOrNull()
        if (cols.size != 2 || cols[0].isBlank() || count == null) null else cols[0] to count
    }.toMap()

    @Synchronized
    fun recordFailure(episodeKey: String) {
        val current = failures().toMutableMap()
        current[episodeKey] = (current[episodeKey] ?: 0) + 1
        saveFailures(current)
    }

    @Synchronized
    fun clearFailure(episodeKey: String) {
        val current = failures().toMutableMap()
        if (current.remove(episodeKey) != null) saveFailures(current)
    }

    private fun Favorite.matches(stationId: String, title: String) =
        this.stationId == stationId && key == normalizeProgramTitle(title)

    /** 区切り文字(タブ・改行)を含む番組名を安全に保存できる形にする。 */
    private fun sanitize(title: String) = title.replace(Regex("[\\t\\r\\n]"), " ")

    private fun saveFavorites(list: List<Favorite>) {
        storage.write(
            KEY_FAVORITES,
            list.joinToString("\n") { "${it.stationId}\t${it.title}\t${if (it.autoDownload) 1 else 0}" },
        )
    }

    private fun saveFailures(map: Map<String, Int>) {
        storage.write(KEY_FAILURES, map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    private inline fun <T : Any> parseLines(text: String?, parse: (List<String>) -> T?): List<T> =
        text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { parse(it.split("\t")) }
}
