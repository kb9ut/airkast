package com.example.airkast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadRegistryTest {

    @Before
    fun setUp() = DownloadRegistry.clearForTest()

    @After
    fun tearDown() = DownloadRegistry.clearForTest()

    @Test
    fun claimThenReleaseAllowsClaimingAgain() {
        assertTrue(DownloadRegistry.tryClaim("TBS:20260124220000"))
        assertTrue(DownloadRegistry.isClaimed("TBS:20260124220000"))

        DownloadRegistry.release("TBS:20260124220000")

        assertFalse(DownloadRegistry.isClaimed("TBS:20260124220000"))
        assertTrue(DownloadRegistry.tryClaim("TBS:20260124220000"))
    }

    @Test
    fun secondClaimOfTheSameKeyIsRejected_butOtherKeysAreIndependent() {
        assertTrue(DownloadRegistry.tryClaim("TBS:1"))

        assertFalse(DownloadRegistry.tryClaim("TBS:1"))
        assertTrue(DownloadRegistry.tryClaim("TBS:2"))
    }

    @Test
    fun releasingAnUnclaimedKeyIsHarmless() {
        DownloadRegistry.release("never-claimed")

        assertTrue(DownloadRegistry.claimed.value.isEmpty())
    }

    @Test
    fun claimedFlowReflectsCurrentSet() {
        DownloadRegistry.tryClaim("A:1")
        DownloadRegistry.tryClaim("B:2")
        assertEquals(setOf("A:1", "B:2"), DownloadRegistry.claimed.value)

        DownloadRegistry.release("A:1")

        assertEquals(setOf("B:2"), DownloadRegistry.claimed.value)
    }

    @Test
    fun onlyOneOfManyConcurrentClaimersWins() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val wins = AtomicInteger(0)

        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                if (DownloadRegistry.tryClaim("TBS:contended")) wins.incrementAndGet()
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(1, wins.get())
    }
}
