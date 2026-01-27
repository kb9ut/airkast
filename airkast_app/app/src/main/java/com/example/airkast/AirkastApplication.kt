package com.example.airkast

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AirkastApplication : Application() {
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
