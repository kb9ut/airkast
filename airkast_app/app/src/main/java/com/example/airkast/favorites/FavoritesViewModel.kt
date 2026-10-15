package com.example.airkast.favorites

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FavoritesViewModel"

enum class FavoriteRowStatus { LOADING, LOADED, NO_RECENT_EPISODE, ERROR }

/** お気に入り画面の 1 行。 [latest] は最新の放送済みの回(見つからなければ null)。 */
data class FavoriteRow(
    val favorite: Favorite,
    val latest: AirkastProgram?,
    val status: FavoriteRowStatus,
)

/** 局ID + 正規化した番組名。★の状態の判定に使う(全角/半角・空白の違いを吸収する)。 */
fun favoriteKeyOf(stationId: String, title: String): String = "$stationId|${normalizeProgramTitle(title)}"

/**
 * お気に入りの操作、各お気に入りの最新回の取得、定期同期の更新を受け持つ。
 * 通知権限の要求は画面側([com.example.airkast.ui.rememberNotificationPermissionGate])の責務で、
 * ここでは権限の有無に関わらず登録とスケジュール更新を行う(権限が無くても自動DLは動く)。
 */
open class FavoritesViewModel(
    application: Application,
    private val repository: FavoriteRepository,
    private val syncControl: FavoriteSyncControl,
    private val guides: GuideSource,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        FavoriteRepository(PrefsTextStorage(application)),
        WorkManagerSyncControl(application),
        ClientGuideSource((application as AirkastApplication).airkastClient),
    )

    private val _rows = MutableStateFlow<List<FavoriteRow>>(emptyList())
    val rows: StateFlow<List<FavoriteRow>> = _rows.asStateFlow()

    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys.asStateFlow()

    /** 取得済みの最新回(キーは [favoriteKeyOf])。Main スレッドからのみ触る。 */
    private val latestCache = mutableMapOf<String, FavoriteRow>()

    /** 実行中の強制更新。重複した強制更新(起動・復帰・タブ表示)を 1 つにまとめるために保持する。 */
    private var forceJob: Job? = null

    init {
        publish()
        refresh(force = false)
    }

    fun isFavorite(stationId: String, title: String): Boolean = favoriteKeyOf(stationId, title) in _favoriteKeys.value

    fun toggleFavorite(stationId: String, title: String) {
        if (isFavorite(stationId, title)) removeFavorite(stationId, title) else addFavorite(stationId, title)
    }

    /** お気に入りに追加する(自動DL は既定で ON)。すでに登録済みなら何もしない。 */
    fun addFavorite(stationId: String, title: String) {
        if (title.isBlank() || repository.isFavorite(stationId, title)) return
        repository.add(stationId, title)
        onFavoritesChanged(syncNow = true)
    }

    fun removeFavorite(stationId: String, title: String) {
        repository.remove(stationId, title)
        onFavoritesChanged(syncNow = false)
    }

    fun setAutoDownload(stationId: String, title: String, enabled: Boolean) {
        repository.setAutoDownload(stationId, title, enabled)
        onFavoritesChanged(syncNow = enabled)
    }

    /**
     * 各お気に入りの最新回を取得し直す。 [force] が false のときは、まだ取得していないお気に入りだけ取得する。
     * 局ごとに直近 8 日分の番組表を取得し、局単位の失敗はその局の行だけに影響させる。
     */
    fun refresh(force: Boolean = true) {
        // 強制更新は同時に 1 つだけ(古い更新が新しい結果を上書きしない)。非強制は常に走らせ、追加直後のお気に入りも取得する
        if (force && forceJob?.isActive == true) return
        val job = viewModelScope.launch {
            val favorites = repository.favorites()
            val targets = if (force) favorites else favorites.filter { favoriteKeyOf(it.stationId, it.title) !in latestCache }
            val nowMs = now()
            for ((station, stationFavorites) in targets.groupBy { it.stationId }) {
                val programs = mutableListOf<AirkastProgram>()
                var succeeded = 0
                for (date in recentDates(nowMs)) {
                    try {
                        programs += guides.programs(station, date)
                        succeeded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "番組表の取得に失敗: $station $date: ${e.message}")
                    }
                }
                val dates = recentDates(nowMs)
                for (favorite in stationFavorites) {
                    val cacheKey = favoriteKeyOf(favorite.stationId, favorite.title)
                    val cached = latestCache[cacheKey]
                    latestCache[cacheKey] = if (succeeded == 0) {
                        // 全日の取得に失敗(オフラインなど)。取得済みの行があれば消さずに残す(再生できなくなるのを防ぐ)
                        cached ?: FavoriteRow(favorite, null, FavoriteRowStatus.ERROR)
                    } else {
                        val latest = latestEpisode(programs, station, favorite.key, nowMs)
                        val fresh = FavoriteRow(
                            favorite,
                            latest,
                            if (latest != null) FavoriteRowStatus.LOADED else FavoriteRowStatus.NO_RECENT_EPISODE,
                        )
                        // 一部の日の取得に失敗したとき、見つかった最新回が取得済みのものより古い(または無い)なら、
                        // 失敗した日にあった新しい回を取りこぼしただけなので、取得済みの行を残す
                        val cachedStart = cached?.latest?.startTime
                        if (succeeded < dates.size && cachedStart != null &&
                            (latest == null || latest.startTime < cachedStart)
                        ) cached else fresh
                    }
                }
                publish()
            }
            publish()
        }
        if (force) forceJob = job
    }

    private fun onFavoritesChanged(syncNow: Boolean) {
        val hasAutoDownload = repository.hasAutoDownload()
        syncControl.update(hasAutoDownload)
        if (syncNow && hasAutoDownload) syncControl.syncNow()
        publish()
        refresh(force = false)
    }

    /** リポジトリの内容とキャッシュから、画面用の状態を組み立て直す。 */
    private fun publish() {
        val favorites = repository.favorites()
        val keys = favorites.map { favoriteKeyOf(it.stationId, it.title) }
        latestCache.keys.retainAll(keys.toSet())
        _favoriteKeys.value = keys.toSet()
        _rows.value = favorites.map { favorite ->
            latestCache[favoriteKeyOf(favorite.stationId, favorite.title)]?.copy(favorite = favorite)
                ?: FavoriteRow(favorite, null, FavoriteRowStatus.LOADING)
        }
    }
}
