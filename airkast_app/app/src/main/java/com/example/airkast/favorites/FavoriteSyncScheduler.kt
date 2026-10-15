package com.example.airkast.favorites

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** お気に入りの定期同期の登録/解除。自動DL が ON のお気に入りがある間だけ登録する。 */
object FavoriteSyncScheduler {
    const val UNIQUE_NAME = "favorite_sync"
    const val UNIQUE_NOW_NAME = "favorite_sync_now"
    const val INTERVAL_HOURS = 6L

    internal fun buildConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** 自動DL を ON にした直後などに、定期実行を待たず 1 回だけ同期するためのリクエスト。 */
    internal fun buildOneTimeRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FavoriteSyncWorker>()
        .setConstraints(buildConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()

    /** 条件(非従量課金回線・バッテリー低下でない)が整えば、すぐ 1 回同期する。実行待ちの要求があれば重複させない。 */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NOW_NAME, ExistingWorkPolicy.KEEP, buildOneTimeRequest())
    }

    /** [enabled] が true なら定期実行を登録(登録済みなら維持)、false なら解除する。 */
    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FavoriteSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(buildConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
