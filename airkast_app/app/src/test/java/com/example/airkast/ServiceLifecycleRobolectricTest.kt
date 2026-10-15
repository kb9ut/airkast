package com.example.airkast

import android.content.Intent
import androidx.media3.common.Player
import com.example.airkast.favorites.episodeKey
import com.example.airkast.service.HlsDownloadService
import com.example.airkast.service.PlayerService
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WorkManager を初期化しない (お気に入り同期の登録を行わない) テスト用 Application。 */
class TestAirkastApplication : AirkastApplication() {
    override fun onCreate() {
        // super.onCreate() を呼ばない: FavoriteSyncScheduler が WorkManager を要求するため
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestAirkastApplication::class)
class ServiceLifecycleRobolectricTest {

    @After
    fun tearDown() {
        DownloadRegistry.clearForTest()
    }

    @Test
    fun hlsDownloadService_onCreate_startsForegroundWithInitialNotification() {
        val controller = Robolectric.buildService(HlsDownloadService::class.java).create()

        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(notification)
        controller.destroy()
    }

    @Test
    fun hlsDownloadService_cancelIntentForUnknownProgram_marksCanceledAndStopsSelf() {
        val controller = Robolectric.buildService(HlsDownloadService::class.java).create()
        val intent = Intent(RuntimeEnvironment.getApplication(), HlsDownloadService::class.java).apply {
            action = HlsDownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(HlsDownloadService.EXTRA_PROGRAM_ID, "robolectric-unknown-id")
        }

        controller.withIntent(intent).startCommand(0, 1)

        val status = HlsDownloadService.downloadStates.value["robolectric-unknown-id"]
        assertTrue(status is HlsDownloadService.DownloadStatus.Canceled)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun hlsDownloadService_onCreate_dropsFinishedStatesWhenNothingIsRunning() {
        // 前のテストで残った Canceled 状態は、次のサービス起動時に破棄される
        val first = Robolectric.buildService(HlsDownloadService::class.java).create()
        first.withIntent(
            Intent(RuntimeEnvironment.getApplication(), HlsDownloadService::class.java).apply {
                action = HlsDownloadService.ACTION_CANCEL_DOWNLOAD
                putExtra(HlsDownloadService.EXTRA_PROGRAM_ID, "robolectric-stale-id")
            }
        ).startCommand(0, 1)
        first.destroy()
        assertTrue(HlsDownloadService.downloadStates.value.containsKey("robolectric-stale-id"))

        Robolectric.buildService(HlsDownloadService::class.java).create().destroy()

        assertTrue(HlsDownloadService.downloadStates.value.isEmpty())
    }

    @Test
    fun alwaysSeekablePlayer_reportsEveryCommandAsAvailable() {
        val underlying = mockk<Player>(relaxed = true)
        val player = PlayerService.AlwaysSeekablePlayer(underlying)

        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_BACK))
    }

    @Test
    fun alwaysSeekablePlayer_delegatesSeeksToUnderlyingPlayer() {
        val underlying = mockk<Player>(relaxed = true)
        val player = PlayerService.AlwaysSeekablePlayer(underlying)

        player.seekTo(1_234L)
        player.seekBack()
        player.seekForward()

        verify { underlying.seekTo(1_234L) }
        verify { underlying.seekBack() }
        verify { underlying.seekForward() }
    }

    @Test
    fun playerService_createAndDestroy_completesWithoutException() {
        val controller = Robolectric.buildService(PlayerService::class.java).create()

        val service = controller.get()
        assertNotNull(service)
        // ExoPlayer / MediaSession / キャッシュの生成と解放が例外なく完了することだけを確認する
        controller.destroy()
    }

    @Test
    fun hlsDownloadService_doesNotStartAnEpisodeThatIsAlreadyClaimed() {
        val program = AirkastProgram(
            "claimed-1", "TBS", "20260124220000", "20260124230000", "アフター6", "", "", null
        )
        DownloadRegistry.tryClaim(program.episodeKey)
        val intent = Intent(RuntimeEnvironment.getApplication(), HlsDownloadService::class.java).apply {
            action = HlsDownloadService.ACTION_START_DOWNLOAD
            putExtra(HlsDownloadService.EXTRA_PROGRAM, program)
        }
        val controller = Robolectric.buildService(HlsDownloadService::class.java).create()

        controller.withIntent(intent).startCommand(0, 1)

        // 予約が取れない回は Pending にもならず、予約も奪わない
        assertNull(HlsDownloadService.downloadStates.value[program.id])
        assertTrue(DownloadRegistry.isClaimed(program.episodeKey))
        controller.destroy()
    }
}
