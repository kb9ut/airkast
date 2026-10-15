package com.example.airkast.favorites

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
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

    /**
     * 1 回だけの同期の重複ポリシー。同期の実行中に★が追加されても取りこぼさないよう、
     * 実行中の要求の後ろに追加する(KEEP だと実行中の追加が捨てられる)。
     */
    internal val SYNC_NOW_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

    /** 定期実行の要求。登録直後の即時実行を避けるため、最初の実行を 1 周期遅らせる(1 回だけの同期と二重に走らせない)。 */
    internal fun buildPeriodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<FavoriteSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(buildConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
            .build()

    /**
     * 条件(非従量課金回線・バッテリー低下でない)が整えば、すぐ 1 回同期する。
     * 実行中の同期があればその後ろに続けて実行する([SYNC_NOW_POLICY])。
     */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NOW_NAME, SYNC_NOW_POLICY, buildOneTimeRequest())
    }

    /** [enabled] が true なら定期実行を登録(登録済みなら維持)、false なら解除する。 */
    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            // 待機中/実行中の 1 回だけの同期も止める(実行中の回は Worker のキャンセルで中断される。
            // キャンセルが間に合わない場合でも、FavoriteSync が次の回の開始前に設定を再確認する)
            workManager.cancelUniqueWork(UNIQUE_NOW_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, buildPeriodicRequest())
    }
}
