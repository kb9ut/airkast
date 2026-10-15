package com.example.airkast.service

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastProgram
import com.example.airkast.MainActivity
import com.example.airkast.PlaybackPositionManager
import com.example.airkast.getParcelableCompat
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "PlayerService"

/**
 * 音声再生を担当する MediaLibraryService。
 * ローカルのM4A再生とタイムフリーHLSのストリーミング再生を、
 * [PlayerCommands] のカスタムコマンド経由で受け付けます。
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null

    private lateinit var positionManager: PlaybackPositionManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Cache for streaming
    private var downloadCache: SimpleCache? = null

    // 現在再生中のメディアID
    private var currentMediaId: String? = null

    // 現在再生中のファイルパス (ローカルファイル再生時のみ)
    private var currentFilePath: String? = null

    // プログラムから取得したduration (AAC-ADTSファイルではExoPlayerがdurationを返さないため)
    private var programDurationMs: Long = 0L

    // 位置を定期保存するJob
    private var positionSaveJob: Job? = null

    companion object {
        // 位置保存の間隔（ミリ秒）
        private const val POSITION_SAVE_INTERVAL_MS = 5000L

        private const val STREAM_CACHE_SIZE_BYTES = 100L * 1024 * 1024
        private const val SEEK_BACK_INCREMENT_MS = 15_000L
        private const val SEEK_FORWARD_INCREMENT_MS = 30_000L

        private const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 再生エラーの原因 ([cause] のメッセージ/型) からユーザー向けメッセージを作る。 */
        internal fun toUserMessage(errorMessage: String?, cause: Throwable?): String = when {
            cause?.message?.contains("403") == true ->
                "エリア外エラー: この番組はお住まいの地域では再生できません"
            cause?.message?.contains("404") == true ->
                "番組が見つかりません"
            cause?.message?.contains("401") == true ->
                "認証エラー: アプリを再起動してください"
            cause is java.net.UnknownHostException ->
                "ネットワークエラー: インターネット接続を確認してください"
            cause is java.net.SocketTimeoutException ->
                "タイムアウト: 通信に時間がかかっています"
            else ->
                "再生エラー: ${errorMessage ?: "不明なエラー"}"
        }

        /**
         * 番組の長さ ([programDurationMs]) か ExoPlayer の長さ ([exoDurationMs]) のうち、
         * 使えるものを優先順に返す。どちらも使えなければ null。
         */
        internal fun knownDurationMs(programDurationMs: Long, exoDurationMs: Long): Long? = when {
            programDurationMs > 0 -> programDurationMs
            exoDurationMs != C.TIME_UNSET && exoDurationMs > 0 -> exoDurationMs
            else -> null
        }

        /** 位置と長さがともに有効なときだけ再生位置を保存する。 */
        internal fun shouldSavePosition(positionMs: Long, durationMs: Long): Boolean =
            positionMs > 0 && durationMs > 0
    }

    override fun onCreate() {
        super.onCreate()
        positionManager = (application as AirkastApplication).playbackPositionManager
        downloadCache = createStreamCache()

        val exoPlayer = createPlayer()
        exoPlayer.addListener(createPlayerListener())
        player = exoPlayer

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        mediaSession = MediaLibrarySession.Builder(this, AlwaysSeekablePlayer(exoPlayer), CustomMediaLibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .build()

        setMediaNotificationProvider(CustomNotificationProvider())
    }

    private fun createStreamCache(): SimpleCache {
        val cacheDir = File(cacheDir, "media_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(this)
        )
    }

    private fun createPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        return ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
            .build()
    }

    /** 再生位置の保存と、再生エラーのユーザー通知を行うリスナー。 */
    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    // 再生完了時は位置をクリア
                    currentMediaId?.let { positionManager.clearPosition(it) }
                    stopPositionSaveJob()
                }
                Player.STATE_READY -> {
                    if (player?.playWhenReady == true) {
                        startPositionSaveJob()
                    }
                }
                Player.STATE_IDLE -> {
                    stopPositionSaveJob()
                    saveCurrentPosition()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startPositionSaveJob()
            } else {
                stopPositionSaveJob()
                saveCurrentPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            emitMessage(toUserMessage(error.message, error.cause))
        }
    }

    private fun emitMessage(message: String) {
        serviceScope.launch {
            (application as AirkastApplication).emitPlayerServiceMessage(message)
        }
    }

    /**
     * 常にシーク/スキップコマンドを「利用可能」として報告するプレーヤー。
     * ストリーミング/ADTSでは ExoPlayer がシーク可否を正しく返さないことがあるため。
     */
    internal class AlwaysSeekablePlayer(private val underlyingPlayer: Player) : ForwardingPlayer(underlyingPlayer) {
        override fun getAvailableCommands(): Player.Commands {
            return Player.Commands.Builder()
                .addAllCommands()
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return true // 全てのコマンドを利用可能として報告
        }

        override fun seekTo(positionMs: Long) {
            Log.d(TAG, "forwardingPlayer.seekTo: $positionMs")
            underlyingPlayer.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            Log.d(TAG, "forwardingPlayer.seekTo idx=$mediaItemIndex: $positionMs")
            underlyingPlayer.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekForward() {
            Log.d(TAG, "forwardingPlayer.seekForward")
            underlyingPlayer.seekForward()
        }

        override fun seekBack() {
            Log.d(TAG, "forwardingPlayer.seekBack")
            underlyingPlayer.seekBack()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // アプリがタスクから削除された時に位置を保存
        saveCurrentPosition()
        player?.let {
            if (!it.playWhenReady || it.playbackState == Player.STATE_ENDED) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // サービス終了時に位置を保存
        saveCurrentPosition()
        stopPositionSaveJob()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        downloadCache?.release()
        downloadCache = null
        serviceJob.cancel()
        super.onDestroy()
    }

    /**
     * 現在の再生位置を保存します。
     */
    private fun saveCurrentPosition() {
        val mediaId = currentMediaId ?: return
        val currentPlayer = player ?: return

        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration

        if (shouldSavePosition(position, duration)) {
            positionManager.savePosition(mediaId, position, duration)
        }
    }

    /**
     * 定期的に位置を保存するJobを開始します。
     */
    private fun startPositionSaveJob() {
        if (positionSaveJob?.isActive == true) return

        positionSaveJob = serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPosition()
            }
        }
    }

    /**
     * 位置保存Jobを停止します。
     */
    private fun stopPositionSaveJob() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            sessionCommands.add(SessionCommand(PlayerCommands.PLAY_FROM_FILE, Bundle.EMPTY))
            sessionCommands.add(SessionCommand(PlayerCommands.PLAY_STREAM, Bundle.EMPTY))
            sessionCommands.add(SessionCommand(PlayerCommands.GET_DURATION, Bundle.EMPTY))

            val playerCommands = Player.Commands.Builder()
                .addAllCommands()
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                PlayerCommands.PLAY_STREAM -> handlePlayStream(args)
                PlayerCommands.PLAY_FROM_FILE -> handlePlayFromFile(args)
                PlayerCommands.GET_DURATION -> handleGetDuration()
                else -> null
            } ?: super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val libraryRoot = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Radiko Downloads").setIsPlayable(false).setIsBrowsable(true).build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(libraryRoot, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // Playlists implementation would go here
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private fun immediateResult(code: Int, extras: Bundle = Bundle.EMPTY): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(code, extras))

    /** PLAY_STREAM: タイムフリーHLSをストリーミング再生します。 */
    private fun handlePlayStream(args: Bundle): ListenableFuture<SessionResult> {
        Log.d(TAG, "=== Received ${PlayerCommands.PLAY_STREAM} ===")
        val url = args.getString(PlayerCommands.KEY_URL)
        val authToken = args.getString(PlayerCommands.KEY_AUTH_TOKEN)
        Log.d(TAG, "Stream URL: ${url?.take(80)}...")
        val program = args.getParcelableCompat<AirkastProgram>(PlayerCommands.KEY_PROGRAM)
        Log.d(TAG, "Program: ${program?.title}, durationMs: ${program?.durationMs}")

        if (url == null || authToken == null) {
            return immediateResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(program?.title ?: "Unknown")
                    .setArtist(program?.performer ?: "")
                    .setExtras(Bundle().apply {
                        if (program != null) putParcelable(PlayerCommands.KEY_PROGRAM, program)
                    })
                    .build()
            )
            .build()
        val mediaSource = HlsMediaSource.Factory(createStreamDataSourceFactory(authToken))
            .createMediaSource(mediaItem)

        // 現在のメディア情報を更新（ストリーミングではファイルパスはなし）
        if (program != null) {
            currentMediaId = program.id
            currentFilePath = null
            programDurationMs = program.durationMs
        }
        serviceScope.launch {
            player?.apply {
                setMediaSource(mediaSource)
                prepare()
                play()
            }
        }
        return immediateResult(SessionResult.RESULT_SUCCESS)
    }

    /** radikoの認証ヘッダー付きで、キャッシュ経由のHTTPデータソースを作ります。 */
    private fun createStreamDataSourceFactory(authToken: String): CacheDataSource.Factory {
        val headers = mapOf(
            "X-Radiko-Authtoken" to authToken,
            "Referer" to "https://radiko.jp/",
            "Origin" to "https://radiko.jp",
            "User-Agent" to STREAM_USER_AGENT
        )
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setDefaultRequestProperties(headers)

        return CacheDataSource.Factory()
            .setCache(downloadCache!!)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** PLAY_FROM_FILE: ダウンロード済みファイルを再生します。引数が不正なら null (未処理)。 */
    private fun handlePlayFromFile(args: Bundle): ListenableFuture<SessionResult>? {
        Log.d(TAG, "=== Received ${PlayerCommands.PLAY_FROM_FILE} ===")
        val filePath = args.getString(PlayerCommands.KEY_FILE_PATH)
        Log.d(TAG, "File path: $filePath")
        val program = args.getParcelableCompat<AirkastProgram>(PlayerCommands.KEY_PROGRAM)
        Log.d(TAG, "Program: ${program?.title}, durationMs: ${program?.durationMs}")

        if (filePath.isNullOrEmpty() || program == null) return null
        playFile(filePath, program)
        return immediateResult(SessionResult.RESULT_SUCCESS)
    }

    /** GET_DURATION: 再生中メディアの長さ (ミリ秒) を結果の extras で返します。 */
    private fun handleGetDuration(): ListenableFuture<SessionResult> {
        val resultExtras = Bundle().apply {
            putLong(PlayerCommands.KEY_DURATION_MS, resolveDurationMs())
        }
        return immediateResult(SessionResult.RESULT_SUCCESS, resultExtras)
    }

    /**
     * 再生中メディアの長さを求めます。優先順位:
     * 1. programDurationMs (番組のstartTime/endTimeから計算した正確なduration)
     * 2. ExoPlayerのduration
     * 3. MediaMetadataRetriever (AAC-ADTSでは不正確なことが多い)
     */
    private fun resolveDurationMs(): Long {
        val exoDuration = player?.duration ?: C.TIME_UNSET
        Log.d(TAG, "get_duration: programDurationMs=$programDurationMs, exoplayer duration=$exoDuration")
        knownDurationMs(programDurationMs, exoDuration)?.let { return it }

        // フォールバック: MediaMetadataRetriever (通常は不正確)
        val filePath = currentFilePath ?: return C.TIME_UNSET
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val metaDuration = durationStr?.toLongOrNull() ?: C.TIME_UNSET
            Log.d(TAG, "get_duration: MediaMetadataRetriever duration=$metaDuration")
            metaDuration
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever error: ${e.message}")
            C.TIME_UNSET
        }
    }

    private fun playFile(filePath: String, program: AirkastProgram) {
        val file = File(filePath)
        if (!file.exists()) {
            emitMessage("ファイルが見つかりません: ${file.name}")
            return
        }

        // 現在のメディアIDとファイルパスを更新
        currentMediaId = program.id
        currentFilePath = filePath
        programDurationMs = program.durationMs
        Log.d(TAG, "playFile: programDurationMs=$programDurationMs")

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(program.title)
            .setArtist(program.performer)
            .setGenre("Radio")
            .setExtras(Bundle().apply { putParcelable(PlayerCommands.KEY_PROGRAM, program) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(program.id)
            .setUri(Uri.fromFile(file).toString())
            .setMimeType("audio/aac")
            .setMediaMetadata(mediaMetadata)
            .build()

        // 保存された再生位置を取得
        val savedPosition = positionManager.getPosition(program.id)

        player?.apply {
            setMediaItem(mediaItem)
            prepare()

            // 保存された位置があれば、そこから再生を再開
            if (savedPosition > 0) {
                seekTo(savedPosition)
            }

            play()
        }
    }

    @UnstableApi
    private inner class CustomNotificationProvider : DefaultMediaNotificationProvider(this@PlayerService) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showInCompactViewIndices: Boolean
        ): ImmutableList<CommandButton> {
            val isPlaying = session.player.isPlaying

            val playPauseButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setIconResId(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                .setDisplayName(if (isPlaying) "Pause" else "Play")
                .build()

            val seekBackButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setIconResId(android.R.drawable.ic_media_rew)
                .setDisplayName("Seek Back 15s")
                .build()

            val seekForwardButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setIconResId(android.R.drawable.ic_media_ff)
                .setDisplayName("Seek Forward 30s")
                .build()

            return ImmutableList.of(seekBackButton, playPauseButton, seekForwardButton)
        }
    }
}
