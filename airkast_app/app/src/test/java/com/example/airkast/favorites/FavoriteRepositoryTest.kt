package com.example.airkast.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRepositoryTest {

    private class MapStorage(val map: MutableMap<String, String> = mutableMapOf()) : TextStorage {
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private val storage = MapStorage()
    private val repo = FavoriteRepository(storage)

    @Test
    fun startsEmpty() {
        assertTrue(repo.favorites().isEmpty())
        assertFalse(repo.hasAutoDownload())
    }

    @Test
    fun addedFavoritesSurviveReloadingFromStorage() {
        repo.add("TBS", "アフター6")

        val reloaded = FavoriteRepository(storage).favorites()

        assertEquals(listOf(Favorite("TBS", "アフター6", autoDownload = true)), reloaded)
    }

    @Test
    fun addingTheSameShowTwiceKeepsOneEntry_evenIfWidthAndSpacingDiffer() {
        repo.add("TBS", "ＡＢＣ　ラジオ")
        repo.add("TBS", "ABC ラジオ")

        assertEquals(1, repo.favorites().size)
        assertTrue(repo.isFavorite("TBS", " ABC  ラジオ "))
        assertFalse(repo.isFavorite("QRR", "ABC ラジオ"))
    }

    @Test
    fun removeDeletesByNormalizedIdentity() {
        repo.add("TBS", "ABC ラジオ")

        repo.remove("TBS", "ＡＢＣ　ラジオ")

        assertTrue(repo.favorites().isEmpty())
    }

    @Test
    fun setAutoDownloadTogglesOnlyThatFavorite() {
        repo.add("TBS", "A")
        repo.add("TBS", "B")

        repo.setAutoDownload("TBS", "A", false)

        assertEquals(listOf(false, true), repo.favorites().map { it.autoDownload })
        assertTrue(repo.hasAutoDownload())
        repo.setAutoDownload("TBS", "B", false)
        assertFalse(repo.hasAutoDownload())
    }

    @Test
    fun titlesContainingTabsAndNewlinesAreStoredSafely() {
        repo.add("TBS", "タブ\tと\n改行")

        val loaded = FavoriteRepository(storage).favorites()

        assertEquals(1, loaded.size)
        assertEquals("TBS", loaded[0].stationId)
        assertTrue(repo.isFavorite("TBS", "タブ\tと\n改行"))
    }

    @Test
    fun recordsAndClearsFailureCounts() {
        repo.recordFailure("TBS:20260124220000")
        repo.recordFailure("TBS:20260124220000")
        repo.recordFailure("QRR:20260124220000")

        assertEquals(
            mapOf("TBS:20260124220000" to 2, "QRR:20260124220000" to 1),
            FavoriteRepository(storage).failures(),
        )

        repo.clearFailure("TBS:20260124220000")

        assertEquals(mapOf("QRR:20260124220000" to 1), repo.failures())
    }

    @Test
    fun malformedLinesInStorageAreIgnored() {
        storage.write("favorites", "garbage\nTBS\tOK番組\t1\n\t\t\nTBS\tcount不正\tx\n")
        storage.write("failures", "broken\nTBS:1\tabc\nTBS:2\t4\n")

        assertEquals(listOf("OK番組"), repo.favorites().map { it.title })
        assertEquals(mapOf("TBS:2" to 4), repo.failures())
    }

    @Test
    fun twoInstancesOverSameStorageDoNotLoseConcurrentUpdates() {
        // UI と Worker がそれぞれ別インスタンスを作るため、インスタンスをまたいでも更新が消えないことを確認する
        val shared = MapStorage()
        val repos = listOf(FavoriteRepository(shared), FavoriteRepository(shared))
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 4).map { t ->
            Thread {
                try {
                    for (i in 0 until 50) {
                        val id = t * 50 + i
                        val repo = repos[t % 2]
                        repo.add("TBS", "show-$id")
                        repo.recordFailure("TBS:$id")
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("thread errors: $errors", errors.isEmpty())
        val fresh = FavoriteRepository(shared)
        assertEquals(200, fresh.favorites().size)
        assertEquals(200, fresh.failures().size)
    }
}
