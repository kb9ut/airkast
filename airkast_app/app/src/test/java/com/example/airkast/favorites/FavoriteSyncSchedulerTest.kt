package com.example.airkast.favorites

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSyncSchedulerTest {

    @Test
    fun constraintsRequireUnmeteredNetworkAndHealthyBattery() {
        val constraints = FavoriteSyncScheduler.buildConstraints()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test
    fun runsEverySixHours() {
        assertEquals(6L, FavoriteSyncScheduler.INTERVAL_HOURS)
    }
}
