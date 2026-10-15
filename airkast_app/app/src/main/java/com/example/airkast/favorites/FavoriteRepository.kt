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
 * UI と Worker(別スレッド)の両方から使われ、それぞれが別インスタンスを作るため、
 * 排他はインスタンスごとではなくプロセス全体で一つのロック([LOCK])で行う。
 * 読み取りも書き込みもすべてこのロックの内側で実行する(読み-変更-書きの途中割り込みで更新が消えないため)。
 */
class FavoriteRepository(private val storage: TextStorage) {

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FAILURES = "failures"

        /** 同じ SharedPreferences を扱う全インスタンスで共有する排他ロック。 */
        private val LOCK = Any()
    }

    fun favorites(): List<Favorite> = synchronized(LOCK) {
        parseLines(storage.read(KEY_FAVORITES)) { cols ->
            if (cols.size != 3 || cols[0].isBlank() || cols[1].isBlank()) return@parseLines null
            val auto = when (cols[2]) {
                "1" -> true
                "0" -> false
                else -> return@parseLines null
            }
            Favorite(cols[0], cols[1], auto)
        }
    }

    fun isFavorite(stationId: String, title: String): Boolean = synchronized(LOCK) {
        favorites().any { it.matches(stationId, title) }
    }

    fun add(stationId: String, title: String) {
        synchronized(LOCK) {
            val current = favorites()
            if (current.any { it.matches(stationId, title) }) return
            saveFavorites(current + Favorite(stationId, sanitize(title)))
        }
    }

    fun remove(stationId: String, title: String) = synchronized(LOCK) {
        saveFavorites(favorites().filterNot { it.matches(stationId, title) })
    }

    fun setAutoDownload(stationId: String, title: String, enabled: Boolean) = synchronized(LOCK) {
        saveFavorites(favorites().map {
            if (it.matches(stationId, title)) it.copy(autoDownload = enabled) else it
        })
    }

    fun hasAutoDownload(): Boolean = synchronized(LOCK) {
        favorites().any { it.autoDownload }
    }

    fun failures(): Map<String, Int> = synchronized(LOCK) {
        parseLines(storage.read(KEY_FAILURES)) { cols ->
            val count = cols.getOrNull(1)?.toIntOrNull()
            if (cols.size != 2 || cols[0].isBlank() || count == null) null else cols[0] to count
        }.toMap()
    }

    fun recordFailure(episodeKey: String) = synchronized(LOCK) {
        val current = failures().toMutableMap()
        current[episodeKey] = (current[episodeKey] ?: 0) + 1
        saveFailures(current)
    }

    fun clearFailure(episodeKey: String) = synchronized(LOCK) {
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
