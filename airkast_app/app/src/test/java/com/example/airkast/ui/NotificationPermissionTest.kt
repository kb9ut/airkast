package com.example.airkast.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun neverAsksBeforeAndroid13() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, granted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 26, granted = false))
    }

    @Test
    fun asksOnAndroid13OrLaterOnlyWhenNotGranted() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, granted = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 36, granted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 33, granted = true))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 36, granted = true))
    }
}
