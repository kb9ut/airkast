package com.example.airkast

import androidx.media3.common.C
import com.example.airkast.service.PlayerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlayerServiceLogicTest {

    // --- toUserMessage ---

    @Test
    fun toUserMessage_mapsHttpStatusInCauseMessage() {
        assertEquals(
            "エリア外エラー: この番組はお住まいの地域では再生できません",
            PlayerService.toUserMessage("src error", RuntimeException("Response code: 403")),
        )
        assertEquals(
            "番組が見つかりません",
            PlayerService.toUserMessage("src error", RuntimeException("Response code: 404")),
        )
        assertEquals(
            "認証エラー: アプリを再起動してください",
            PlayerService.toUserMessage("src error", RuntimeException("Response code: 401")),
        )
    }

    @Test
    fun toUserMessage_mapsNetworkExceptions() {
        assertEquals(
            "ネットワークエラー: インターネット接続を確認してください",
            PlayerService.toUserMessage("src error", UnknownHostException("host")),
        )
        assertEquals(
            "タイムアウト: 通信に時間がかかっています",
            PlayerService.toUserMessage("src error", SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun toUserMessage_fallsBackToGenericMessage() {
        assertEquals("再生エラー: decoder failed", PlayerService.toUserMessage("decoder failed", null))
        assertEquals("再生エラー: 不明なエラー", PlayerService.toUserMessage(null, null))
    }

    @Test
    fun toUserMessage_httpStatusTakesPriorityOverExceptionType() {
        // 403 を含むメッセージは、例外型より先に判定される
        assertEquals(
            "エリア外エラー: この番組はお住まいの地域では再生できません",
            PlayerService.toUserMessage("x", UnknownHostException("403")),
        )
    }

    // --- duration ---

    @Test
    fun knownDurationMs_prefersProgramDuration() {
        assertEquals(3_600_000L, PlayerService.knownDurationMs(3_600_000L, 1_000L))
    }

    @Test
    fun knownDurationMs_usesExoDurationWhenProgramDurationMissing() {
        assertEquals(1_000L, PlayerService.knownDurationMs(0L, 1_000L))
    }

    @Test
    fun knownDurationMs_returnsNullWhenNeitherIsUsable() {
        assertNull(PlayerService.knownDurationMs(0L, C.TIME_UNSET))
        assertNull(PlayerService.knownDurationMs(0L, 0L))
    }

    // --- 位置保存 ---

    @Test
    fun shouldSavePosition_requiresPositivePositionAndDuration() {
        assertTrue(PlayerService.shouldSavePosition(1L, 1L))
        assertFalse(PlayerService.shouldSavePosition(0L, 1_000L))
        assertFalse(PlayerService.shouldSavePosition(1_000L, 0L))
        assertFalse(PlayerService.shouldSavePosition(1_000L, C.TIME_UNSET))
    }
}
