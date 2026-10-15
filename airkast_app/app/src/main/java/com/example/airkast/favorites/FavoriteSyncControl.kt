package com.example.airkast.favorites

import android.content.Context

/** 定期同期の登録/解除と即時同期の操作。ViewModel を JVM でテストできるよう抽象化する。 */
interface FavoriteSyncControl {
    /** 自動DL が ON のお気に入りがあるときだけ定期実行を登録する。 */
    fun update(enabled: Boolean)

    /** すぐ 1 回同期する。 */
    fun syncNow()
}

class WorkManagerSyncControl(context: Context) : FavoriteSyncControl {
    private val appContext = context.applicationContext

    override fun update(enabled: Boolean) = FavoriteSyncScheduler.update(appContext, enabled)

    override fun syncNow() = FavoriteSyncScheduler.syncNow(appContext)
}
