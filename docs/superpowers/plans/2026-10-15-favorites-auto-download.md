# お気に入り番組の自動ダウンロード(データ層・Worker) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** お気に入り番組(局+番組名)を保存し、放送終了後の新しい回を Wi-Fi 接続時のみバックグラウンドで自動ダウンロードする仕組みを作る。

**Architecture:** ダウンロード本体を `DownloadRunner` に切り出して Service と Worker で共有する。お気に入りの保存(`FavoriteRepository`)、新しい回の判定(`FavoriteMatcher`、純粋関数)、同期の手順(`FavoriteSync`、依存を差し替え可能)は JVM 単体テストで検証できる形にし、`FavoriteSyncWorker` は WorkManager との薄い接続だけを持つ。

**Tech Stack:** Kotlin, Android (minSdk 26 / targetSdk 36), WorkManager (`androidx.work:work-runtime-ktx`), JUnit4, kotlin.test, kotlinx-coroutines.

**Spec:** `docs/superpowers/specs/2026-10-15-favorites-auto-download-design.md`

**この計画の範囲:** 仕様 §9 の段階 1〜3(`DownloadRunner` 切り出し、データ層、Worker)。段階 4 の UI(★ボタン、お気に入り画面、自動DLスイッチ)は、進行中のデザイン刷新が main に入った後に、別の計画として書く。UI が無いため、この計画の完了時点では実機でお気に入りを登録できない。実機での端末内確認は UI の計画で行う。

## Global Constraints

- 対象は `airkast_app`(Android)のみ。iOS / Web は触らない。
- 新しい依存は `androidx.work:work-runtime-ktx` のみ。Room / kotlinx-serialization は追加しない。
- テストは JUnit4 のみ。`useJUnitPlatform()` を `app/build.gradle.kts` に戻さない。
- Gradle 実行には `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` と `ANDROID_HOME=~/Library/Android/sdk` が必要(既定の JDK 24 では Gradle 8.11 が動かない)。コマンドは `airkast_app/` で `./gradlew ... --offline` を使う。
- コード内のコメントは既存に合わせて日本語。
- タイムスタンプは `radikoTimestampFormat()`(`yyyyMMddHHmmss`、端末のタイムゾーン)で解釈する。
- 失敗の再試行上限 `MAX_ATTEMPTS = 3`、終了後の余裕 `END_MARGIN_MS = 5分`、タイムフリー期間 `WINDOW_MS = 7日`、Worker 間隔 6 時間(制約: `NetworkType.UNMETERED`、`requiresBatteryNotLow`)。
- 新しいファイルは `com.example.airkast.favorites` パッケージに置く(`DownloadRunner` のみ `com.example.airkast`)。
- コミットメッセージの末尾に `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` を付ける。

## 仕様からの調整(この計画で確定する点)

仕様 §3・§5・§8 は、実装を単純にするため次のように読み替える(仕様ファイルも同時に更新済み)。

1. 保存形式は JSON ではなく、タブ区切りの行テキスト(SharedPreferences に文字列で保存)。kotlinx-serialization のプラグインが無く、`isReturnDefaultValues = true` のため JVM テストで `org.json` が使えないので。
2. 失敗回数は `Favorite` に持たせず、`FavoriteRepository` が「回のキー(`局ID:開始時刻`) → 回数」として別に保存する。
3. Worker は `HlsDownloadService.downloadStates`(画面の進捗)を更新しない。進捗は通知のみ。取得済みの反映は、画面が戻ってきたときのファイル再走査に任せる。
4. 手動ダウンロード中の回を自動ダウンロードが重複して始めないよう、`FavoriteSync` に `isDownloading` 判定を持たせる(仕様に無かった追加)。
5. Worker 自体の `work-testing` テストは書かない。判定・手順は `FavoriteMatcher` / `FavoriteSync` の単体テストで検証し、Worker はビルドと実機で確認する。

## Review Focus

仕様が暗黙に前提としているが、仕様のどのタスクのテストにも無い、ユーザーが踏みやすい入力・条件。

1. 全角/半角・空白違いの番組名(`ＡＢＣ　ラジオ` と `ABC ラジオ`)は同じ番組として扱う → Task 2 のテスト。
2. 放送中、または終了から 5 分未満の回は取得しない(境界は「ちょうど 5 分」を含める) → Task 2 のテスト。
3. 3 回失敗した回は再試行しない。成功すると失敗回数が消える → Task 2・Task 4 のテスト。
4. 手動ダウンロード中の回を、自動ダウンロードが重複して始めない → Task 4 のテスト。
5. 全局の番組表取得が失敗(オフライン・エリア外)した場合は失敗回数を増やさず、Worker は `retry` する。キャンセルは失敗に数えない。保存データに壊れた行があっても落ちない → Task 3・Task 4 のテスト。

---

## File Structure

| ファイル | 責務 |
|---|---|
| `app/src/main/java/com/example/airkast/DownloadRunner.kt`(新規) | 1 番組を m4a にダウンロードする共通処理 |
| `app/src/main/java/com/example/airkast/service/HlsDownloadService.kt`(変更) | `DownloadRunner` を呼ぶ |
| `app/src/main/java/com/example/airkast/favorites/Favorite.kt`(新規) | `Favorite`、番組名の正規化、回のキー |
| `app/src/main/java/com/example/airkast/favorites/FavoriteMatcher.kt`(新規) | 新しい回の判定(純粋関数) |
| `app/src/main/java/com/example/airkast/favorites/FavoriteRepository.kt`(新規) | 保存先の抽象、お気に入りと失敗回数の保存 |
| `app/src/main/java/com/example/airkast/favorites/FavoriteSync.kt`(新規) | 番組表取得→判定→ダウンロードの手順 |
| `app/src/main/java/com/example/airkast/favorites/FavoriteSyncWorker.kt`(新規) | WorkManager の Worker、通知、実依存の組み立て |
| `app/src/main/java/com/example/airkast/favorites/FavoriteSyncScheduler.kt`(新規) | 定期実行の登録/解除 |
| `app/src/main/java/com/example/airkast/AirkastClient.kt`(変更) | `authenticate()` を追加 |
| `app/src/main/java/com/example/airkast/AirkastApplication.kt`(変更) | 起動時にスケジュールを同期 |
| `app/src/main/AndroidManifest.xml`(変更) | WorkManager の SystemForegroundService に dataSync を宣言 |
| `app/build.gradle.kts`(変更) | WorkManager 依存の追加 |
| `app/src/test/java/com/example/airkast/DownloadRunnerTest.kt` ほか `favorites/*Test.kt`(新規) | 各ユニットのテスト |

---

### Task 1: `DownloadRunner` の切り出し(挙動不変)

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/DownloadRunner.kt`
- Modify: `airkast_app/app/src/main/java/com/example/airkast/service/HlsDownloadService.kt`(`runDownload` と `DEFAULT_CHUNK_SIZE_SECONDS`)
- Test: `airkast_app/app/src/test/java/com/example/airkast/DownloadRunnerTest.kt`

**Interfaces:**
- Consumes: `AirkastClient`(`client`, `authToken`, `generateNewLsid()`, `generateHlsUrl(program, startAt, endAt, lsid)`)、`HlsDownloader(client.client).downloadChunked(...)`、`radikoTimestampFormat()`
- Produces:
  - `class DownloadRunner(private val client: AirkastClient)`
  - `suspend fun run(program: AirkastProgram, outputFile: File, chunkSizeSeconds: Int = DEFAULT_CHUNK_SIZE_SECONDS, onProgress: (Int) -> Unit = {}, onMuxingProgress: (Int) -> Unit = {})`(番組の開始/終了が不正なら `IllegalArgumentException`)
  - `DownloadRunner.DEFAULT_CHUNK_SIZE_SECONDS = 60`(public const)

- [ ] **Step 1: 失敗するテストを書く**

`DownloadRunnerTest.kt`:

```kotlin
package com.example.airkast

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class DownloadRunnerTest {

    private fun program(start: String, end: String) =
        AirkastProgram("id", "TBS", start, end, "title", "", "", null)

    @Test
    fun run_rejectsProgramWhoseEndIsNotAfterStart() {
        val runner = DownloadRunner(AirkastClient())

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                runner.run(
                    program("20260125100000", "20260125100000"),
                    File("unused.m4a"),
                )
            }
        }
    }

    @Test
    fun run_rejectsUnparsableTimes() {
        val runner = DownloadRunner(AirkastClient())

        assertFailsWith<IllegalArgumentException> {
            runBlocking { runner.run(program("", ""), File("unused.m4a")) }
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.DownloadRunnerTest"`
Expected: FAIL(`Unresolved reference: DownloadRunner` でコンパイルエラー)

- [ ] **Step 3: `DownloadRunner` を実装する**

`DownloadRunner.kt`(`HlsDownloadService.runDownload` の中身をそのまま移す。`radikoTimestampFormat()` の `parse` は失敗時に例外を投げるので `runCatching` で `null` 扱いにする):

```kotlin
package com.example.airkast

import java.io.File
import java.util.Date

/**
 * 1 番組を m4a にダウンロードする共通処理。
 * フォアグラウンドサービス([com.example.airkast.service.HlsDownloadService])と
 * お気に入りの自動ダウンロード Worker の両方から使う。状態や通知は持たない。
 */
class DownloadRunner(private val client: AirkastClient) {

    companion object {
        const val DEFAULT_CHUNK_SIZE_SECONDS = 60
    }

    /**
     * 番組を [outputFile] にダウンロードします。進捗は 0〜100 の整数で通知します。
     *
     * @throws IllegalArgumentException 番組の開始/終了時刻が不正な場合。
     */
    suspend fun run(
        program: AirkastProgram,
        outputFile: File,
        chunkSizeSeconds: Int = DEFAULT_CHUNK_SIZE_SECONDS,
        onProgress: (Int) -> Unit = {},
        onMuxingProgress: (Int) -> Unit = {},
    ) {
        val format = radikoTimestampFormat()
        val startTime = runCatching { format.parse(program.startTime)?.time }.getOrNull() ?: 0L
        val endTime = runCatching { format.parse(program.endTime)?.time }.getOrNull() ?: 0L

        require(startTime != 0L && endTime != 0L && startTime < endTime) {
            "Invalid program duration: start=$startTime, end=$endTime"
        }

        val downloadLsid = client.generateNewLsid()

        HlsDownloader(client.client).downloadChunked(
            outputFile = outputFile,
            startTime = startTime,
            endTime = endTime,
            chunkDurationSeconds = chunkSizeSeconds,
            authToken = client.authToken ?: "",
            urlGenerator = { s, e ->
                client.generateHlsUrl(program, format.format(Date(s)), format.format(Date(e)), downloadLsid)
            },
            onProgress = { onProgress((it * 100).toInt()) },
            onMuxingProgress = { onMuxingProgress((it * 100).toInt()) },
        )
    }
}
```

- [ ] **Step 4: `HlsDownloadService` を `DownloadRunner` 経由にする**

`HlsDownloadService.kt` で、`runDownload` を次のように置き換え、`DEFAULT_CHUNK_SIZE_SECONDS` の private 定数を削除して `DownloadRunner.DEFAULT_CHUNK_SIZE_SECONDS` を参照する(`onStartCommand` の `getIntExtra(EXTRA_CHUNK_SIZE, ...)` の既定値)。`radikoTimestampFormat` / `Date` / `HlsDownloader` の import で未使用になったものは消す。

```kotlin
    /**
     * 番組を [outputFile] にダウンロードします。進捗は状態フローと通知に反映されます。
     */
    private suspend fun runDownload(program: AirkastProgram, chunkSizeSeconds: Int, outputFile: File) {
        updateState(program.id, DownloadStatus.InProgress(program, 0))
        updateNotification()

        DownloadRunner(client).run(
            program = program,
            outputFile = outputFile,
            chunkSizeSeconds = chunkSizeSeconds,
            onProgress = { percent ->
                updateState(program.id, DownloadStatus.InProgress(program, percent))
                updateNotification()
            },
            onMuxingProgress = { percent ->
                updateState(program.id, DownloadStatus.Muxing(program, percent))
                updateNotification()
            },
        )
    }
```

import に `com.example.airkast.DownloadRunner` を追加する。

- [ ] **Step 5: テストを実行して、新旧すべて通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline`
Expected: PASS(既存 31 件 + 新規 2 件 = 33 件、失敗 0)

- [ ] **Step 6: ビルドを確認する**

Run: `cd airkast_app && ./gradlew assembleDebug --offline`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/DownloadRunner.kt \
        airkast_app/app/src/main/java/com/example/airkast/service/HlsDownloadService.kt \
        airkast_app/app/src/test/java/com/example/airkast/DownloadRunnerTest.kt
git commit -m "refactor: Extract DownloadRunner from HlsDownloadService

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `Favorite` と `FavoriteMatcher`

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/Favorite.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteMatcher.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteMatcherTest.kt`

**Interfaces:**
- Consumes: `AirkastProgram`、`radikoTimestampFormat()`、`isProgramDownloaded(files: List<File>, program: AirkastProgram): Boolean`(`MainViewModel.kt` の `internal` 関数、パッケージ `com.example.airkast`)
- Produces:
  - `data class Favorite(val stationId: String, val title: String, val autoDownload: Boolean = true)` と `val key: String`(正規化済み番組名)
  - `fun normalizeProgramTitle(title: String): String`
  - `val AirkastProgram.episodeKey: String`(`"$stationId:$startTime"`)
  - `object FavoriteMatcher` の `END_MARGIN_MS`、`WINDOW_MS`、`MAX_ATTEMPTS` と `fun findNewEpisodes(favorites: List<Favorite>, programs: List<AirkastProgram>, nowMs: Long, downloadedFiles: List<File>, failures: Map<String, Int>): List<AirkastProgram>`(放送開始の古い順)

- [ ] **Step 1: 失敗するテストを書く**

`FavoriteMatcherTest.kt`:

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavoriteMatcherTest {

    private fun ts(s: String) = radikoTimestampFormat().parse(s)!!.time

    // 2026-01-25 12:00:00 を「現在」とする
    private val now = ts("20260125120000")

    private fun prog(
        station: String = "TBS",
        title: String = "アフター6",
        start: String = "20260124220000",
        end: String = "20260124230000",
    ) = AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val fav = Favorite("TBS", "アフター6")

    private fun find(
        programs: List<AirkastProgram>,
        favorites: List<Favorite> = listOf(fav),
        downloaded: List<File> = emptyList(),
        failures: Map<String, Int> = emptyMap(),
    ) = FavoriteMatcher.findNewEpisodes(favorites, programs, now, downloaded, failures)

    @Test
    fun matchesByStationAndTitle() {
        val result = find(listOf(prog(), prog(station = "QRR"), prog(title = "別の番組")))

        assertEquals(listOf(prog()), result)
    }

    @Test
    fun normalizesWidthAndWhitespaceInTitles() {
        val favorite = Favorite("TBS", "ＡＢＣ　ラジオ")

        val result = find(listOf(prog(title = "ABC ラジオ")), listOf(favorite))

        assertEquals(1, result.size)
    }

    @Test
    fun ignoresFavoritesWithAutoDownloadOff() {
        val result = find(listOf(prog()), listOf(fav.copy(autoDownload = false)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeStillOnAir() {
        val result = find(listOf(prog(start = "20260125110000", end = "20260125121000")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeThatEndedLessThanMarginAgo() {
        // 終了 11:56 → 現在 12:00 の 5 分前(11:55)より後なのでまだ取得しない
        val result = find(listOf(prog(start = "20260125105600", end = "20260125115600")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun includesEpisodeThatEndedExactlyMarginAgo() {
        val result = find(listOf(prog(start = "20260125105500", end = "20260125115500")))

        assertEquals(1, result.size)
    }

    @Test
    fun excludesEpisodeStartedBeforeTimeFreeWindow() {
        // 現在の 7 日前は 2026-01-18 12:00:00
        val tooOld = prog(start = "20260118115900", end = "20260118125900")
        val boundary = prog(start = "20260118120000", end = "20260118130000")

        val result = find(listOf(tooOld, boundary))

        assertEquals(listOf(boundary), result)
    }

    @Test
    fun skipsAlreadyDownloadedEpisode() {
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")

        val result = find(listOf(prog()), downloaded = listOf(file))

        assertTrue(result.isEmpty())
    }

    @Test
    fun skipsEpisodeThatFailedMaxAttempts() {
        val key = "TBS:20260124220000"

        assertEquals(1, find(listOf(prog()), failures = mapOf(key to 2)).size)
        assertTrue(find(listOf(prog()), failures = mapOf(key to 3)).isEmpty())
    }

    @Test
    fun removesDuplicatesAndSortsByStartTime() {
        val late = prog(start = "20260124220000", end = "20260124230000")
        val early = prog(start = "20260123220000", end = "20260123230000")

        val result = find(listOf(late, early, late))

        assertEquals(listOf(early, late), result)
    }

    @Test
    fun ignoresProgramsWithUnparsableTimes() {
        val result = find(listOf(prog(start = "", end = "")))

        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteMatcherTest"`
Expected: FAIL(`Unresolved reference: Favorite` などでコンパイルエラー)

- [ ] **Step 3: `Favorite.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import java.text.Normalizer

/**
 * お気に入り番組。同一性は「放送局ID + 正規化した番組名」で決まる。
 * 曜日や時刻は持たない(放送時間が変わっても追従するため)。
 */
data class Favorite(
    val stationId: String,
    val title: String,
    val autoDownload: Boolean = true,
) {
    /** 照合用の正規化済み番組名 */
    val key: String get() = normalizeProgramTitle(title)
}

/** 全角/半角を統一(NFKC)し、前後の空白を除いて連続する空白を 1 つにする。 */
fun normalizeProgramTitle(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

/** 回を一意に識別するキー(失敗回数の記録に使う)。 */
val AirkastProgram.episodeKey: String
    get() = "$stationId:$startTime"
```

- [ ] **Step 4: `FavoriteMatcher.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.isProgramDownloaded
import com.example.airkast.radikoTimestampFormat
import java.io.File

/** お気に入りと番組表から、自動ダウンロードすべき「新しい回」を判定する純粋関数。 */
object FavoriteMatcher {
    /** タイムフリーに反映されるまでの余裕 */
    const val END_MARGIN_MS = 5 * 60 * 1000L

    /** タイムフリーで聴ける期間 */
    const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    /** 1 つの回に対する自動ダウンロードの試行上限 */
    const val MAX_ATTEMPTS = 3

    /**
     * 次をすべて満たす番組を放送開始の古い順に返す。
     * 1. 自動DL ON のお気に入りと、局ID・正規化した番組名が一致する
     * 2. 放送終了が (現在 − [END_MARGIN_MS]) 以前
     * 3. 放送開始が (現在 − [WINDOW_MS]) 以降
     * 4. 取得済みでない
     * 5. 失敗回数が [MAX_ATTEMPTS] 未満
     */
    fun findNewEpisodes(
        favorites: List<Favorite>,
        programs: List<AirkastProgram>,
        nowMs: Long,
        downloadedFiles: List<File>,
        failures: Map<String, Int>,
    ): List<AirkastProgram> {
        val targets = favorites.filter { it.autoDownload }.map { it.stationId to it.key }.toSet()
        if (targets.isEmpty()) return emptyList()

        val format = radikoTimestampFormat()
        return programs
            .distinctBy { Triple(it.stationId, it.startTime, it.endTime) }
            .filter { (it.stationId to normalizeProgramTitle(it.title)) in targets }
            .filter { program ->
                val start = runCatching { format.parse(program.startTime)?.time }.getOrNull()
                val end = runCatching { format.parse(program.endTime)?.time }.getOrNull()
                start != null && end != null &&
                    end <= nowMs - END_MARGIN_MS &&
                    start >= nowMs - WINDOW_MS
            }
            .filterNot { isProgramDownloaded(downloadedFiles, it) }
            .filter { (failures[it.episodeKey] ?: 0) < MAX_ATTEMPTS }
            .sortedBy { it.startTime }
    }
}
```

- [ ] **Step 5: テストが通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteMatcherTest"`
Expected: PASS(11 件)

- [ ] **Step 6: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites \
        airkast_app/app/src/test/java/com/example/airkast/favorites
git commit -m "feat: Add Favorite model and FavoriteMatcher

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `FavoriteRepository`

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteRepository.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteRepositoryTest.kt`

**Interfaces:**
- Consumes: `Favorite`、`normalizeProgramTitle`(Task 2)
- Produces:
  - `interface TextStorage { fun read(key: String): String?; fun write(key: String, value: String) }`
  - `class PrefsTextStorage(context: Context) : TextStorage`(SharedPreferences `airkast_favorites`)
  - `class FavoriteRepository(storage: TextStorage)`:
    - `fun favorites(): List<Favorite>`
    - `fun isFavorite(stationId: String, title: String): Boolean`
    - `fun add(stationId: String, title: String)`(重複は無視)
    - `fun remove(stationId: String, title: String)`
    - `fun setAutoDownload(stationId: String, title: String, enabled: Boolean)`
    - `fun hasAutoDownload(): Boolean`
    - `fun failures(): Map<String, Int>`
    - `fun recordFailure(episodeKey: String)`
    - `fun clearFailure(episodeKey: String)`

- [ ] **Step 1: 失敗するテストを書く**

`FavoriteRepositoryTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteRepositoryTest"`
Expected: FAIL(`Unresolved reference: FavoriteRepository`)

- [ ] **Step 3: `FavoriteRepository.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import android.content.Context

/** 文字列の保存先。SharedPreferences を直接使うと JVM テストできないため抽象化する。 */
interface TextStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class PrefsTextStorage(context: Context) : TextStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("airkast_favorites", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * お気に入りと、自動ダウンロードの失敗回数を保存する。
 * 保存形式はタブ区切りの行テキスト:
 * - favorites: `局ID \t 番組名 \t 自動DL(1/0)`
 * - failures:  `回のキー \t 失敗回数`
 * UI と Worker(別スレッド)の両方から使われるため、変更系は同期する。
 */
class FavoriteRepository(private val storage: TextStorage) {

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FAILURES = "failures"
    }

    @Synchronized
    fun favorites(): List<Favorite> = parseLines(storage.read(KEY_FAVORITES)) { cols ->
        if (cols.size != 3 || cols[0].isBlank() || cols[1].isBlank()) return@parseLines null
        val auto = when (cols[2]) {
            "1" -> true
            "0" -> false
            else -> return@parseLines null
        }
        Favorite(cols[0], cols[1], auto)
    }

    @Synchronized
    fun isFavorite(stationId: String, title: String): Boolean =
        favorites().any { it.matches(stationId, title) }

    @Synchronized
    fun add(stationId: String, title: String) {
        val current = favorites()
        if (current.any { it.matches(stationId, title) }) return
        saveFavorites(current + Favorite(stationId, sanitize(title)))
    }

    @Synchronized
    fun remove(stationId: String, title: String) {
        saveFavorites(favorites().filterNot { it.matches(stationId, title) })
    }

    @Synchronized
    fun setAutoDownload(stationId: String, title: String, enabled: Boolean) {
        saveFavorites(favorites().map {
            if (it.matches(stationId, title)) it.copy(autoDownload = enabled) else it
        })
    }

    @Synchronized
    fun hasAutoDownload(): Boolean = favorites().any { it.autoDownload }

    @Synchronized
    fun failures(): Map<String, Int> = parseLines(storage.read(KEY_FAILURES)) { cols ->
        val count = cols.getOrNull(1)?.toIntOrNull()
        if (cols.size != 2 || cols[0].isBlank() || count == null) null else cols[0] to count
    }.toMap()

    @Synchronized
    fun recordFailure(episodeKey: String) {
        val current = failures().toMutableMap()
        current[episodeKey] = (current[episodeKey] ?: 0) + 1
        saveFailures(current)
    }

    @Synchronized
    fun clearFailure(episodeKey: String) {
        val current = failures().toMutableMap()
        if (current.remove(episodeKey) != null) saveFailures(current)
    }

    private fun Favorite.matches(stationId: String, title: String) =
        this.stationId == stationId && key == normalizeProgramTitle(title)

    /** 区切り文字(タブ・改行)を含む番組名を安全に保存できる形にする。 */
    private fun sanitize(title: String) = title.replace(Regex("[\\t\\r\\n]"), " ")

    private fun saveFavorites(list: List<Favorite>) {
        storage.write(
            KEY_FAVORITES,
            list.joinToString("\n") { "${it.stationId}\t${it.title}\t${if (it.autoDownload) 1 else 0}" },
        )
    }

    private fun saveFailures(map: Map<String, Int>) {
        storage.write(KEY_FAILURES, map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    private inline fun <T : Any> parseLines(text: String?, parse: (List<String>) -> T?): List<T> =
        text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { parse(it.split("\t")) }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteRepositoryTest"`
Expected: PASS(8 件)

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteRepository.kt \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteRepositoryTest.kt
git commit -m "feat: Add FavoriteRepository with text-based storage

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `FavoriteSync`(番組表取得→判定→ダウンロードの手順)

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSync.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncTest.kt`

**Interfaces:**
- Consumes: `FavoriteRepository`(Task 3)、`FavoriteMatcher`・`episodeKey`(Task 2)
- Produces:
  - `interface GuideSource { suspend fun programs(stationId: String, date: String): List<AirkastProgram> }`(`date` は `yyyyMMdd`)
  - `interface EpisodeDownloader { suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit) }`(失敗時は例外を投げる)
  - `data class SyncReport(val downloaded: List<AirkastProgram>, val failed: List<AirkastProgram>, val stationsTotal: Int, val stationsFailed: Int)` と `val allStationsFailed: Boolean`
  - `class FavoriteSync(repository, guides, downloader, downloadedFiles: () -> List<File>, isDownloading: (AirkastProgram) -> Boolean = { false }, now: () -> Long = System::currentTimeMillis)` の `suspend fun run(onProgress: (AirkastProgram, Int) -> Unit = { _, _ -> }): SyncReport`
  - `internal fun recentDates(nowMs: Long, days: Int = 8): List<String>`

- [ ] **Step 1: 失敗するテストを書く**

`FavoriteSyncTest.kt`:

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class FavoriteSyncTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private class MapStorage : TextStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private fun prog(station: String = "TBS", start: String = "20260124220000", end: String = "20260124230000") =
        AirkastProgram("$station-$start", station, start, end, "アフター6", "", "", null)

    private class FakeGuides(
        private val onRequest: (String, String) -> List<AirkastProgram>,
    ) : GuideSource {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun programs(stationId: String, date: String): List<AirkastProgram> {
            requests += stationId to date
            return onRequest(stationId, date)
        }
    }

    private class FakeDownloader(private val onDownload: (AirkastProgram) -> Unit = {}) : EpisodeDownloader {
        val downloaded = mutableListOf<AirkastProgram>()
        override suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit) {
            onDownload(program)
            onProgress(100)
            downloaded += program
        }
    }

    private val repo = FavoriteRepository(MapStorage()).also { it.add("TBS", "アフター6") }

    private fun sync(
        guides: GuideSource,
        downloader: EpisodeDownloader,
        downloaded: List<File> = emptyList(),
        isDownloading: (AirkastProgram) -> Boolean = { false },
    ) = FavoriteSync(repo, guides, downloader, { downloaded }, isDownloading, { now })

    @Test
    fun recentDatesListsToday_andSevenPreviousDays() {
        assertEquals(
            listOf(
                "20260125", "20260124", "20260123", "20260122",
                "20260121", "20260120", "20260119", "20260118",
            ),
            recentDates(now),
        )
    }

    @Test
    fun downloadsNewEpisodeFoundInGuide() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        val report = sync(guides, downloader).run()

        assertEquals(listOf(prog()), downloader.downloaded)
        assertEquals(listOf(prog()), report.downloaded)
        assertTrue(report.failed.isEmpty())
        assertEquals(8, guides.requests.size)
        assertEquals(setOf("TBS"), guides.requests.map { it.first }.toSet())
    }

    @Test
    fun doesNothingWhenThereAreNoAutoDownloadFavorites() = runBlocking {
        repo.setAutoDownload("TBS", "アフター6", false)
        val guides = FakeGuides { _, _ -> listOf(prog()) }
        val downloader = FakeDownloader()

        val report = sync(guides, downloader).run()

        assertTrue(guides.requests.isEmpty())
        assertTrue(downloader.downloaded.isEmpty())
        assertFalse(report.allStationsFailed)
    }

    @Test
    fun skipsAlreadyDownloadedEpisode() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")

        sync(guides, downloader, downloaded = listOf(file)).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun skipsEpisodeThatIsBeingDownloadedManually() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        sync(guides, downloader, isDownloading = { it.id == prog().id }).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun recordsFailureAndContinuesWithNextEpisode() = runBlocking {
        val first = prog(start = "20260123220000", end = "20260123230000")
        val second = prog(start = "20260124220000", end = "20260124230000")
        val guides = FakeGuides { _, date ->
            when (date) {
                "20260123" -> listOf(first)
                "20260124" -> listOf(second)
                else -> emptyList()
            }
        }
        val downloader = FakeDownloader { if (it == first) throw java.io.IOException("boom") }

        val report = sync(guides, downloader).run()

        assertEquals(listOf(first), report.failed)
        assertEquals(listOf(second), report.downloaded)
        assertEquals(mapOf(first.episodeKey to 1), repo.failures())
    }

    @Test
    fun successClearsEarlierFailureCount() = runBlocking {
        repo.recordFailure(prog().episodeKey)
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }

        sync(guides, FakeDownloader()).run()

        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun doesNotRetryEpisodeThatReachedMaxAttempts() = runBlocking {
        repeat(FavoriteMatcher.MAX_ATTEMPTS) { repo.recordFailure(prog().episodeKey) }
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()

        sync(guides, downloader).run()

        assertTrue(downloader.downloaded.isEmpty())
    }

    @Test
    fun guideFailureForEveryStationIsReported_withoutCountingEpisodeFailures() = runBlocking {
        val guides = FakeGuides { _, _ -> throw java.io.IOException("offline") }

        val report = sync(guides, FakeDownloader()).run()

        assertTrue(report.allStationsFailed)
        assertEquals(1, report.stationsTotal)
        assertTrue(repo.failures().isEmpty())
    }

    @Test
    fun partialGuideFailureIsNotAllStationsFailed() = runBlocking {
        val guides = FakeGuides { _, date ->
            if (date == "20260125") throw java.io.IOException("one day failed") else emptyList()
        }

        val report = sync(guides, FakeDownloader()).run()

        assertFalse(report.allStationsFailed)
    }

    @Test
    fun cancellationPropagatesAndIsNotCountedAsFailure() {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader { throw CancellationException("stopped") }

        assertFailsWith<CancellationException> { runBlocking { sync(guides, downloader).run() } }
        assertTrue(repo.failures().isEmpty())
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteSyncTest"`
Expected: FAIL(`Unresolved reference: FavoriteSync` など)

- [ ] **Step 3: `FavoriteSync.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import android.util.Log
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FavoriteSync"

/** 番組表の取得元。 [date] は `yyyyMMdd`。 */
interface GuideSource {
    suspend fun programs(stationId: String, date: String): List<AirkastProgram>
}

/** 1 回分のダウンロード。失敗時は例外を投げる。 */
interface EpisodeDownloader {
    suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit)
}

data class SyncReport(
    val downloaded: List<AirkastProgram>,
    val failed: List<AirkastProgram>,
    val stationsTotal: Int,
    val stationsFailed: Int,
) {
    /** すべての局の番組表取得が失敗した(オフライン・エリア外など)。 */
    val allStationsFailed: Boolean get() = stationsTotal > 0 && stationsFailed == stationsTotal
}

/** [nowMs] から過去 [days] 日分(今日を含む)の `yyyyMMdd` を新しい順に返す。 */
internal fun recentDates(nowMs: Long, days: Int = 8): List<String> {
    val format = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
    return (0 until days).map { format.format(Date(nowMs - it * 86_400_000L)) }
}

/**
 * お気に入りの新しい回を探してダウンロードする手順。
 * 実際のネットワーク・ファイル・時計は引数で差し替えられるので、JVM でテストできる。
 */
class FavoriteSync(
    private val repository: FavoriteRepository,
    private val guides: GuideSource,
    private val downloader: EpisodeDownloader,
    private val downloadedFiles: () -> List<File>,
    private val isDownloading: (AirkastProgram) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onProgress: (AirkastProgram, Int) -> Unit = { _, _ -> }): SyncReport {
        val favorites = repository.favorites().filter { it.autoDownload }
        if (favorites.isEmpty()) return SyncReport(emptyList(), emptyList(), 0, 0)

        val nowMs = now()
        val dates = recentDates(nowMs)
        val stations = favorites.map { it.stationId }.distinct()

        val programs = mutableListOf<AirkastProgram>()
        var stationsFailed = 0
        for (station in stations) {
            var succeeded = 0
            for (date in dates) {
                try {
                    programs += guides.programs(station, date)
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "番組表の取得に失敗: $station $date: ${e.message}")
                }
            }
            if (succeeded == 0) stationsFailed++
        }

        val candidates = FavoriteMatcher
            .findNewEpisodes(favorites, programs, nowMs, downloadedFiles(), repository.failures())
            .filterNot { isDownloading(it) }

        val downloaded = mutableListOf<AirkastProgram>()
        val failed = mutableListOf<AirkastProgram>()
        for (program in candidates) {
            try {
                downloader.download(program) { onProgress(program, it) }
                repository.clearFailure(program.episodeKey)
                downloaded += program
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "自動ダウンロードに失敗: ${program.title}: ${e.message}")
                repository.recordFailure(program.episodeKey)
                failed += program
            }
        }
        return SyncReport(downloaded, failed, stations.size, stationsFailed)
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteSyncTest"`
Expected: PASS(11 件)

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSync.kt \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncTest.kt
git commit -m "feat: Add FavoriteSync orchestration for new favorite episodes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: WorkManager の Worker・スケジューラ・配線

**Files:**
- Modify: `airkast_app/app/build.gradle.kts`(依存)
- Modify: `airkast_app/app/src/main/AndroidManifest.xml`(SystemForegroundService)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/AirkastClient.kt`(`authenticate()`)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/AirkastApplication.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncWorker.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncScheduler.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncSchedulerTest.kt`(判定のみ)

**Interfaces:**
- Consumes: `FavoriteSync`・`GuideSource`・`EpisodeDownloader`(Task 4)、`FavoriteRepository`・`PrefsTextStorage`(Task 3)、`DownloadRunner`(Task 1)、`HlsDownloadService.getDownloadFile(context, program): File` と `HlsDownloadService.downloadStates: StateFlow<Map<String, DownloadStatus>>`、`AirkastClient.auth1()/getPartialKey(offset, length)/auth2(partialKey)/getProgramGuide(stationId, date)`
- Produces:
  - `suspend fun AirkastClient.authenticate(): String`(`auth2` が返すエリアID)
  - `object FavoriteSyncScheduler { fun update(context: Context, enabled: Boolean) }`(UI 計画でも、お気に入りの変更後に呼ぶ)
  - `class FavoriteSyncWorker(context, params) : CoroutineWorker`

- [ ] **Step 1: 依存を追加する**

`app/build.gradle.kts` の `dependencies` の Media3 の並びの後に追加:

```kotlin
    // WorkManager (お気に入りの自動ダウンロード)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
```

- [ ] **Step 2: `AirkastClient.authenticate()` を追加する**

`AirkastClient.kt` の `auth2` の直後に追加(`MainViewModel.authenticateAndFetchStations` の auth1→getPartialKey→auth2 と同じ手順):

```kotlin
    /** auth1 → 部分キー生成 → auth2 を一括で行い、認証トークンを確立してエリアIDを返します。 */
    suspend fun authenticate(): String {
        val (keyLength, keyOffset) = auth1()
        return auth2(getPartialKey(keyOffset, keyLength))
    }
```

- [ ] **Step 3: スケジューラの判定テストを書く(失敗させる)**

WorkManager 自体は JVM でテストしないが、登録の条件(制約・周期・一意名)は定数として公開して検証する。`FavoriteSyncSchedulerTest.kt`:

```kotlin
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
```

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteSyncSchedulerTest"`
Expected: FAIL(`Unresolved reference: FavoriteSyncScheduler`)

- [ ] **Step 4: `FavoriteSyncScheduler.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** お気に入りの定期同期の登録/解除。自動DL が ON のお気に入りがある間だけ登録する。 */
object FavoriteSyncScheduler {
    const val UNIQUE_NAME = "favorite_sync"
    const val INTERVAL_HOURS = 6L

    internal fun buildConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** [enabled] が true なら定期実行を登録(登録済みなら維持)、false なら解除する。 */
    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FavoriteSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(buildConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
```

- [ ] **Step 5: `FavoriteSyncWorker.kt` を実装する**

Android 12 以降はバックグラウンドから通常のフォアグラウンドサービスを開始できないため、Worker 自身が `setForeground` で dataSync として動く。

```kotlin
package com.example.airkast.favorites

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastClient
import com.example.airkast.AirkastProgram
import com.example.airkast.DownloadRunner
import com.example.airkast.service.HlsDownloadService
import kotlinx.coroutines.CancellationException
import java.io.File

private const val TAG = "FavoriteSyncWorker"
private const val CHANNEL_ID = "FavoriteSyncChannel"
private const val PROGRESS_NOTIFICATION_ID = 2
private const val DONE_NOTIFICATION_BASE_ID = 1000

/** 約 6 時間ごとに、お気に入り番組の新しい回を自動ダウンロードする。 */
class FavoriteSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("お気に入りを確認中", "")

    override suspend fun doWork(): Result {
        val repository = FavoriteRepository(PrefsTextStorage(applicationContext))
        if (!repository.hasAutoDownload()) return Result.success()

        val client = (applicationContext as AirkastApplication).airkastClient
        createNotificationChannel()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // バックグラウンド起動の制限などで昇格できなかった場合は、次の機会に回す
            Log.w(TAG, "フォアグラウンドに昇格できませんでした: ${e.message}")
            return Result.retry()
        }

        try {
            client.authenticate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "認証に失敗: ${e.message}")
            return Result.retry()
        }

        val sync = FavoriteSync(
            repository = repository,
            guides = ClientGuideSource(client),
            downloader = RunnerEpisodeDownloader(applicationContext, DownloadRunner(client)),
            downloadedFiles = { downloadedFiles() },
            isDownloading = { isDownloadingManually(it) },
        )
        val report = sync.run { program, percent ->
            notificationManager.notify(
                PROGRESS_NOTIFICATION_ID,
                progressNotification("${program.title} をダウンロード中", "$percent%"),
            )
        }

        notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        report.downloaded.forEach { notifyDownloaded(it) }
        return if (report.allStationsFailed) Result.retry() else Result.success()
    }

    private fun downloadedFiles(): List<File> =
        applicationContext.getExternalFilesDir("downloads")?.listFiles()?.toList().orEmpty()

    /** 画面から手動ダウンロード中の回を、自動ダウンロードが重複して始めないための判定。 */
    private fun isDownloadingManually(program: AirkastProgram): Boolean =
        when (HlsDownloadService.downloadStates.value[program.id]) {
            is HlsDownloadService.DownloadStatus.Pending,
            is HlsDownloadService.DownloadStatus.InProgress,
            is HlsDownloadService.DownloadStatus.Muxing -> true
            else -> false
        }

    private fun notifyDownloaded(program: AirkastProgram) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("ダウンロード完了")
            .setContentText(program.title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val id = DONE_NOTIFICATION_BASE_ID + (program.id.hashCode() and 0xFFFF)
        notificationManager.notify(id, notification)
    }

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        val notification = progressNotification(title, text)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun progressNotification(title: String, text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "お気に入りの自動ダウンロード", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/** 実際の radiko 番組表を取得する [GuideSource]。 */
internal class ClientGuideSource(private val client: AirkastClient) : GuideSource {
    override suspend fun programs(stationId: String, date: String): List<AirkastProgram> =
        client.getProgramGuide(stationId, date)
}

/** [DownloadRunner] で所定のファイルにダウンロードする [EpisodeDownloader]。失敗時は部分ファイルを消す。 */
internal class RunnerEpisodeDownloader(
    private val context: Context,
    private val runner: DownloadRunner,
) : EpisodeDownloader {
    override suspend fun download(program: AirkastProgram, onProgress: (Int) -> Unit) {
        val outputFile = HlsDownloadService.getDownloadFile(context, program)
        try {
            runner.run(program, outputFile, DownloadRunner.DEFAULT_CHUNK_SIZE_SECONDS, onProgress)
        } catch (e: Throwable) {
            if (outputFile.exists()) outputFile.delete()
            throw e
        }
    }
}
```

- [ ] **Step 6: Manifest に WorkManager のフォアグラウンドサービス種別を宣言する**

Android 14 以降は、フォアグラウンドサービスに種別の宣言が必須。`AndroidManifest.xml` のルート `<manifest>` に `xmlns:tools="http://schemas.android.com/tools"` がなければ追加し、`<application>` 内、既存の `<service>` の並びに次を追加:

```xml
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
```

(`FOREGROUND_SERVICE_DATA_SYNC` 権限は宣言済み。)

- [ ] **Step 7: Application でスケジュールを同期する**

`AirkastApplication.kt` に追加(import: `com.example.airkast.favorites.FavoriteRepository`、`FavoriteSyncScheduler`、`PrefsTextStorage`):

```kotlin
    override fun onCreate() {
        super.onCreate()
        // 自動DL が ON のお気に入りがあれば定期同期を登録、無ければ解除する
        FavoriteSyncScheduler.update(
            this,
            FavoriteRepository(PrefsTextStorage(this)).hasAutoDownload(),
        )
    }
```

- [ ] **Step 8: 全テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline`
Expected: PASS(既存 31 + 2(Task 1) + 11(Task 2) + 8(Task 3) + 11(Task 4) + 2(Task 5) = 65 件、失敗 0)

Run: `cd airkast_app && ./gradlew assembleDebug --offline`
Expected: BUILD SUCCESSFUL(マニフェストのマージ警告・エラーがないこと)

- [ ] **Step 9: マージ後のマニフェストを確認する**

Run: `cd airkast_app && grep -n "SystemForegroundService" -A2 app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
Expected: `android:foregroundServiceType="dataSync"` が表示される(パスが異なる場合は `find app/build -name AndroidManifest.xml -path "*merged_manifest*"` で探す)

- [ ] **Step 10: コミット**

```bash
git add airkast_app/app/build.gradle.kts \
        airkast_app/app/src/main/AndroidManifest.xml \
        airkast_app/app/src/main/java/com/example/airkast/AirkastClient.kt \
        airkast_app/app/src/main/java/com/example/airkast/AirkastApplication.kt \
        airkast_app/app/src/main/java/com/example/airkast/favorites \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncSchedulerTest.kt
git commit -m "feat: Add FavoriteSyncWorker for weekly auto-download of favorites

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. 仕様の網羅:** §2 構成(`DownloadRunner`=Task 1、`Favorite`/`Repository`/`Matcher`=Task 2・3、`FavoriteSync`/Worker/Scheduler=Task 4・5)、§3 データ(Task 2・3、調整 1・2 に記載)、§4 判定 5 条件(Task 2 の 11 テスト)、§5 Worker(制約・6 時間・setForeground・認証失敗は retry・Task 5)、§7 エラー処理(Task 4・5)、§8 テスト(調整 5 に記載)。§6 画面は範囲外(冒頭に明記)。
**2. プレースホルダ:** なし。すべてのコード手順にコードを記載。
**3. 型の整合:** `episodeKey`(Task 2)を Task 3・4 のテストと実装が同じ形(`局ID:開始時刻`)で使う。`FavoriteSync` のコンストラクタ引数順(repository, guides, downloader, downloadedFiles, isDownloading, now)が Task 4 の実装・テストと Task 5 の Worker で一致。`DownloadRunner.run` の引数は Task 1 と Task 5 で一致。
**4. Review Focus:** 5 項目すべてに、所有タスクのテストがある。
