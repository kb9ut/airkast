package com.example.airkast

import android.app.Application
import com.example.airkast.favorites.FavoriteRepository
import com.example.airkast.favorites.FavoriteSyncScheduler
import com.example.airkast.favorites.PrefsTextStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// テストで onCreate (WorkManager の登録) を差し替えられるよう open にしている
open class AirkastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 自動DL が ON のお気に入りがあれば定期同期を登録、無ければ解除する
        FavoriteSyncScheduler.update(
            this,
            FavoriteRepository(PrefsTextStorage(this)).hasAutoDownload(),
        )
    }

    val airkastClient: AirkastClient by lazy {
        AirkastClient()
    }
    
    // 再生位置キャッシュマネージャー
    val playbackPositionManager: PlaybackPositionManager by lazy {
        PlaybackPositionManager(this)
    }

    // PlayerServiceなどからエラーメッセージを通知するためのSharedFlow
    private val _playerServiceMessage = MutableSharedFlow<String>()
    val playerServiceMessage: SharedFlow<String> = _playerServiceMessage.asSharedFlow()

    suspend fun emitPlayerServiceMessage(message: String) {
        _playerServiceMessage.emit(message)
    }
}
