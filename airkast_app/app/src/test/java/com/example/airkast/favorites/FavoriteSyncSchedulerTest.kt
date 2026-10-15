package com.example.airkast.favorites

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun syncNowUsesItsOwnUniqueName() {
        assertEquals("favorite_sync_now", FavoriteSyncScheduler.UNIQUE_NOW_NAME)
        assertNotEquals(FavoriteSyncScheduler.UNIQUE_NAME, FavoriteSyncScheduler.UNIQUE_NOW_NAME)
    }

    @Test
    fun oneTimeRequestTargetsTheSyncWorker() {
        val request = FavoriteSyncScheduler.buildOneTimeRequest()

        assertEquals(FavoriteSyncWorker::class.java.name, request.workSpec.workerClassName)
    }
}
