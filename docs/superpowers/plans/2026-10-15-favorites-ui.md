# お気に入り画面(UI)と衝突対策 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 実装済みの自動ダウンロードを使えるようにする。お気に入りタブと番組表の★、手動DLと自動DLの衝突対策(`DownloadRegistry`)、通知権限の要求、スケジュール更新(`syncNow` を含む)を作る。

**Architecture:** プロセス内の `DownloadRegistry` で「いま書き込み中の回」を予約し、`HlsDownloadService` と `FavoriteSync` が予約してから書き込む。`FavoritesViewModel` がお気に入りの操作・最新回の取得・スケジュール更新を受け持ち、画面は `FavoritesViewModel` と既存の `MainViewModel` の状態から描画する。

**Tech Stack:** Kotlin, Jetpack Compose (Material3 1.3), WorkManager 2.10, JUnit4, mockk, Robolectric 4.16.1。

**Spec:** `docs/superpowers/specs/2026-10-15-favorites-ui-design.md`(前提: `docs/superpowers/specs/2026-10-15-favorites-auto-download-design.md`)

## Global Constraints

- 対象は `airkast_app`(Android)のみ。新しい依存は追加しない。
- テストは JUnit4 のみ。`useJUnitPlatform()` を `app/build.gradle.kts` に戻さない。Robolectric のテストは `@Config(sdk = [34], application = TestAirkastApplication::class)` を付ける(`ServiceLifecycleRobolectricTest.kt` の既存パターン)。
- Gradle 実行: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk`、`airkast_app/` で `./gradlew ... --offline`(成果物が無くて失敗したときだけ `--offline` なしで1回再試行)。`clean` は付けない(Gradle の "unable to make progress" が出ることがある)。
- コード内のコメントと画面の文言は日本語。画面の文言は `res/values/strings.xml` に置く(`MainViewModel` のスナックバー文言は既存どおり直接の日本語文字列でよい)。
- 回の識別子は `AirkastProgram.episodeKey`(`"$stationId:$startTime"`、`favorites/Favorite.kt`)。`DownloadRegistry` のキーもこれ。
- 画面の配色・余白は既存のトークンを使う(`ui/theme/`: `Spacing`、`MinTouchTarget`、`MaterialTheme.colorScheme`、`groupedItemShape`)。
- タブの順序は 0=お気に入り / 1=番組を探す / 2=ダウンロード。起動時の初期タブはお気に入り。
- テスト件数は参考値(現在 103 件)。正しさの基準は全件通過。
- コミットメッセージの末尾に `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` を付ける。プロジェクトの説明は `CLAUDE.md`(ルート)にある。

## 仕様からの調整(この計画で確定する点)

1. 手動DLが予約済みの回を押したときのスナックバー文言は、自動/手動を区別せず「この回はすでにダウンロード処理中です」とする(予約が中止処理の途中でも同じ状態になるため)。
2. 予約の解放は、`HlsDownloadService` では `Job.invokeOnCompletion` で行う(ジョブが開始前にキャンセルされても必ず解放されるため)。`FavoriteSync` では `try/finally`。
3. お気に入りの局名は `MainViewModel.stationUiState` の放送局一覧から引き、見つからなければ局IDを表示する。
4. 更新のタイミングは、タブを開いたとき(`LaunchedEffect`)、アプリ復帰時(`MainActivity` の `repeatOnLifecycle(STARTED)`)、引っ張って更新(`PullToRefreshBox`)。
5. `isProgramDownloaded` の隣に `findDownloadedFile` を追加する(再生にはファイルが必要なため)。

## Review Focus

仕様が前提としているが、どのタスクのテストにも無い、ユーザーが踏みやすい条件。

1. 予約は成功・失敗・キャンセルのどれでも必ず解放される(解放漏れで、その回が二度とダウンロードできなくなる) → Task 1・2・3 のテスト。
2. 手動ダウンロードを中止してすぐ同じ番組を再開しても、古いジョブの後始末が新しいジョブのファイルを消さない(古い予約が解放されるまで再開は断られる) → Task 3 のテスト。
3. 全角/半角・空白の違う番組名が、★の状態と一覧で同じお気に入りとして扱われる → Task 6 のテスト。
4. ある局の番組表が取得できなくても、他の局の行は表示される → Task 6 のテスト。
5. 通知権限: Android 12 以下では要求しない、許可済みでは要求しない。拒否されてもお気に入りと自動DLは有効のまま。通知の extra が不明・空でも、お気に入りタブが開く → Task 6・7 のテスト。

---

## File Structure

| ファイル | 責務 |
|---|---|
| `app/src/main/java/com/example/airkast/DownloadRegistry.kt`(新規) | 回の予約・解放・予約中の集合の公開 |
| `.../favorites/FavoriteSync.kt`(変更) | `isDownloading` を `claim`/`release` に置き換える |
| `.../favorites/FavoriteSyncWorker.kt`(変更) | 予約の配線、重複ロジックの削除、完了通知のタップ処理 |
| `.../service/HlsDownloadService.kt`(変更) | 開始時に予約、完了時に解放 |
| `.../MainViewModel.kt`(変更) | `onDownloadProgram` の事前チェック、`findDownloadedFile` |
| `.../favorites/LatestEpisode.kt`(新規) | 時刻の共通関数と `latestEpisode` |
| `.../favorites/FavoriteMatcher.kt`(変更) | 共通関数の利用 |
| `.../favorites/FavoriteSyncControl.kt`(新規) | スケジュール操作のインターフェースと WorkManager 実装 |
| `.../favorites/FavoriteSyncScheduler.kt`(変更) | `syncNow` の追加 |
| `.../favorites/FavoritesViewModel.kt`(新規) | お気に入りの操作、最新回の取得、画面用の状態 |
| `.../ui/Tabs.kt`(新規) | タブ番号と通知 extra の解釈 |
| `.../ui/NotificationPermission.kt`(新規) | 通知権限の判定と要求のゲート |
| `.../favorites/FavoriteIntents.kt`(新規) | 通知タップで開く Intent の組み立て |
| `.../ui/components/FavoriteRow.kt`(新規) | お気に入りの行 |
| `.../ui/screens/FavoritesScreen.kt`(新規) | お気に入りタブ |
| `.../ui/components/Rows.kt`、`ui/screens/FindProgramsScreen.kt`、`ui/AirkastApp.kt`、`MainActivity.kt`、`res/values/strings.xml`、`AndroidManifest.xml`(変更) | ★、3 タブ、配線、文言 |
| `CLAUDE.md`(変更) | 持ち越し項目の解消の反映 |

---

### Task 1: `DownloadRegistry`

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/DownloadRegistry.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/DownloadRegistryTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `object DownloadRegistry` の `fun tryClaim(key: String): Boolean`(アトミック。取れたら true、すでに予約済みなら false)、`fun release(key: String)`、`fun isClaimed(key: String): Boolean`、`val claimed: StateFlow<Set<String>>`、`internal fun clearForTest()`

- [ ] **Step 1: 失敗するテストを書く**

`DownloadRegistryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.DownloadRegistryTest"`
Expected: FAIL(`Unresolved reference: DownloadRegistry`)

- [ ] **Step 3: 実装する**

`DownloadRegistry.kt`:

```kotlin
package com.example.airkast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * プロセス内で「いま書き込み中の回」を予約する仕組み。
 * 手動ダウンロード([com.example.airkast.service.HlsDownloadService])と自動ダウンロード(Worker)が、
 * 同じ回を同じファイルパスへ同時に書き込んで壊し合うのを防ぐ。
 * キーは回の識別子 `AirkastProgram.episodeKey`(`局ID:開始時刻`)。
 */
object DownloadRegistry {
    private val _claimed = MutableStateFlow<Set<String>>(emptySet())

    /** いま予約されている回のキーの集合(画面の「自動取得中」表示などに使う)。 */
    val claimed: StateFlow<Set<String>> = _claimed.asStateFlow()

    /** [key] を予約する。取れたら true、すでに予約済みなら false。複数スレッドから呼んでも成功するのは 1 つだけ。 */
    fun tryClaim(key: String): Boolean {
        while (true) {
            val current = _claimed.value
            if (key in current) return false
            if (_claimed.compareAndSet(current, current + key)) return true
        }
    }

    /** [key] の予約を解放する。予約されていなくても何もしない。 */
    fun release(key: String) {
        _claimed.update { it - key }
    }

    fun isClaimed(key: String): Boolean = key in _claimed.value

    internal fun clearForTest() {
        _claimed.value = emptySet()
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.DownloadRegistryTest"`
Expected: PASS(5 件)

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/DownloadRegistry.kt \
        airkast_app/app/src/test/java/com/example/airkast/DownloadRegistryTest.kt
git commit -m "feat: Add DownloadRegistry to prevent concurrent writes of the same episode

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `FavoriteSync` を予約方式にし、Worker に配線する

**Files:**
- Modify: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSync.kt`(コンストラクタとダウンロードのループ)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncWorker.kt`(`FavoriteSync` の組み立て、`isDownloadingManually` の削除)
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncTest.kt`

**Interfaces:**
- Consumes: `DownloadRegistry`(Task 1)、`episodeKey`
- Produces: `FavoriteSync(repository, guides, downloader, downloadedFiles, claim: (AirkastProgram) -> Boolean = { true }, release: (AirkastProgram) -> Unit = {}, now: () -> Long = System::currentTimeMillis)`(旧 `isDownloading` は削除)

- [ ] **Step 1: 既存テストを新しいコンストラクタに合わせ、新しいテストを書く(失敗させる)**

`FavoriteSyncTest.kt` の `sync(...)` ヘルパーを置き換える(`isDownloading` 引数を `claim`/`release` に):

```kotlin
    private fun sync(
        guides: GuideSource,
        downloader: EpisodeDownloader,
        downloaded: List<File> = emptyList(),
        claim: (AirkastProgram) -> Boolean = { true },
        release: (AirkastProgram) -> Unit = {},
    ) = FavoriteSync(repo, guides, downloader, { downloaded }, claim, release, { now })
```

既存の2つの呼び出しを次のように直す:
- `skipsEpisodeThatIsBeingDownloadedManually` 内の `sync(guides, downloader, isDownloading = { it.id == prog().id }).run()` → `sync(guides, downloader, claim = { it.id != prog().id }).run()`
- `skipsCandidateThatStartsDownloadingManuallyDuringSync` 内の `sync(twoDayGuides, downloader, isDownloading = { it.id in busy }).run()` → `sync(twoDayGuides, downloader, claim = { it.id !in busy }).run()`

テストクラスの末尾に次を追加する:

```kotlin
    @Test
    fun releasesTheClaimAfterSuccessFailureAndCancellation() = runBlocking {
        val first = prog(start = "20260122220000", end = "20260122230000")
        val second = prog(start = "20260123220000", end = "20260123230000")
        val third = prog(start = "20260124220000", end = "20260124230000")
        val guides = FakeGuides { _, date ->
            when (date) {
                "20260122" -> listOf(first)
                "20260123" -> listOf(second)
                "20260124" -> listOf(third)
                else -> emptyList()
            }
        }
        val claimed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val downloader = FakeDownloader {
            when (it) {
                second -> throw java.io.IOException("boom")
                third -> throw CancellationException("stopped")
            }
        }

        assertFailsWith<CancellationException> {
            sync(
                guides, downloader,
                claim = { claimed += it.id; true },
                release = { released += it.id },
            ).run()
        }

        // 成功(first)・失敗(second)・キャンセル(third)のどれでも、予約した回は必ず解放される
        assertEquals(listOf(first.id, second.id, third.id), claimed)
        assertEquals(claimed, released)
    }

    @Test
    fun unclaimedEpisodeIsSkippedWithoutFailureOrRelease() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val released = mutableListOf<String>()

        val report = sync(guides, downloader, claim = { false }, release = { released += it.id }).run()

        assertTrue(downloader.downloaded.isEmpty())
        assertTrue(report.failed.isEmpty())
        assertTrue(repo.failures().isEmpty())
        assertTrue(released.isEmpty())
    }

    @Test
    fun episodeThatBecameDownloadedAfterClaimingIsReleasedAndSkipped() = runBlocking {
        val guides = FakeGuides { _, date -> if (date == "20260124") listOf(prog()) else emptyList() }
        val downloader = FakeDownloader()
        val released = mutableListOf<String>()
        // 予約を取るまでの間に別の処理が保存を終えた状況(予約後の再確認で見つかる)
        val file = File("TBS_アフター6_20260124220000_20260124230000.m4a")
        var calls = 0
        val sync = FavoriteSync(
            repo, guides, downloader,
            downloadedFiles = { if (calls++ == 0) emptyList() else listOf(file) },
            claim = { true },
            release = { released += it.id },
            now = { now },
        )

        sync.run()

        assertTrue(downloader.downloaded.isEmpty())
        assertEquals(listOf(prog().id), released)
        assertTrue(repo.failures().isEmpty())
    }
```

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteSyncTest"`
Expected: FAIL(コンストラクタの引数が合わずコンパイルエラー)

- [ ] **Step 2: `FavoriteSync` を実装する**

コンストラクタの `isDownloading` を置き換える:

```kotlin
class FavoriteSync(
    private val repository: FavoriteRepository,
    private val guides: GuideSource,
    private val downloader: EpisodeDownloader,
    private val downloadedFiles: () -> List<File>,
    /** 回の書き込み権を予約する。取れなければ(他の処理が書き込み中)その回はスキップする。 */
    private val claim: (AirkastProgram) -> Boolean = { true },
    /** [claim] で取った予約を解放する。 */
    private val release: (AirkastProgram) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
```

`candidates` の組み立てから `.filterNot { isDownloading(it) }` を削除し、ダウンロードのループを次に置き換える:

```kotlin
        val candidates = FavoriteMatcher
            .findNewEpisodes(favorites, programs, nowMs, downloadedFiles(), repository.failures())

        val downloaded = mutableListOf<AirkastProgram>()
        val failed = mutableListOf<AirkastProgram>()
        for (program in candidates) {
            // 手動ダウンロードなど他の処理が書き込み中なら、その回は次の周期に回す(失敗には数えない)
            if (!claim(program)) continue
            try {
                // 予約を取るまでの間に別の処理が保存を終えているかもしれないので、予約後に再確認する
                if (isProgramDownloaded(downloadedFiles(), program)) continue
                // 試行は開始前に数える。中断(キャンセル/プロセス終了)された試行も MAX_ATTEMPTS に含め、
                // 長すぎる回が古い順の先頭で毎回後続をブロックし続けないようにする
                repository.recordFailure(program.episodeKey)
                try {
                    downloader.download(program) { onProgress(program, it) }
                    repository.clearFailure(program.episodeKey)
                    downloaded += program
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "自動ダウンロードに失敗: ${program.title}: ${e.message}")
                    failed += program
                }
            } finally {
                release(program)
            }
        }
        return SyncReport(downloaded, failed, stations.size, stationsFailed)
```

- [ ] **Step 3: Worker を配線する**

`FavoriteSyncWorker.kt`:
- `FavoriteSync(...)` の組み立てで `isDownloading = { isDownloadingManually(it) },` を次に置き換える:
  ```kotlin
              claim = { DownloadRegistry.tryClaim(it.episodeKey) },
              release = { DownloadRegistry.release(it.episodeKey) },
  ```
- `isDownloadingManually` の関数全体を削除する。
- import に `com.example.airkast.DownloadRegistry` を追加する。未使用になった import(`HlsDownloadService.DownloadStatus` 参照だけに使っていたものがあれば)は消す。`HlsDownloadService` 自体は `getDownloadFile`/`partialFiles` で使うので残す。

- [ ] **Step 4: テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline`
Expected: PASS(全件。`FavoriteSyncTest` は 16 件)

Run: `cd airkast_app && ./gradlew assembleDebug --offline`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncTest.kt
git commit -m "feat: Claim episodes via DownloadRegistry in FavoriteSync

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `HlsDownloadService` と `MainViewModel` の予約対応

**Files:**
- Modify: `airkast_app/app/src/main/java/com/example/airkast/service/HlsDownloadService.kt`(`startDownload`)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/MainViewModel.kt`(`onDownloadProgram`、`isProgramDownloaded` の隣)
- Test: `airkast_app/app/src/test/java/com/example/airkast/MainViewModelBugfixTest.kt`、`airkast_app/app/src/test/java/com/example/airkast/ServiceLifecycleRobolectricTest.kt`、`airkast_app/app/src/test/java/com/example/airkast/DownloadedFileLookupTest.kt`(新規)

**Interfaces:**
- Consumes: `DownloadRegistry`(Task 1)、`episodeKey`(`com.example.airkast.favorites.episodeKey`)
- Produces: `internal fun findDownloadedFile(files: List<File>, program: AirkastProgram): File?`(`isProgramDownloaded` はこれを使う)。`HlsDownloadService.startDownload` は予約が取れない回を開始しない。

- [ ] **Step 1: `findDownloadedFile` の失敗するテストを書く**

`DownloadedFileLookupTest.kt`:

```kotlin
package com.example.airkast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadedFileLookupTest {

    private val program = AirkastProgram(
        "p", "TBS", "20260124220000", "20260124230000", "アフター6", "", "", null
    )

    @Test
    fun returnsTheMatchingFileForStationStartAndEnd() {
        val match = File("TBS_アフター6_20260124220000_20260124230000.m4a")
        val files = listOf(File("QRR_アフター6_20260124220000_20260124230000.m4a"), match)

        assertEquals(match, findDownloadedFile(files, program))
        assertTrue(isProgramDownloaded(files, program))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        val files = listOf(File("TBS_アフター6_20260123220000_20260123230000.m4a"))

        assertNull(findDownloadedFile(files, program))
        assertFalse(isProgramDownloaded(files, program))
    }
}
```

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.DownloadedFileLookupTest"`
Expected: FAIL(`Unresolved reference: findDownloadedFile`)

- [ ] **Step 2: `findDownloadedFile` を実装する**

`MainViewModel.kt` 末尾の既存 `isProgramDownloaded` を次に置き換える(KDoc は元のものを残す):

```kotlin
internal fun findDownloadedFile(files: List<File>, program: AirkastProgram): File? {
    val prefix = "${program.stationId.ifEmpty { "unknown" }}_"
    val suffix = "_${program.startTime}_${program.endTime}.m4a"
    return files.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(suffix) }
}

internal fun isProgramDownloaded(files: List<File>, program: AirkastProgram): Boolean =
    findDownloadedFile(files, program) != null
```

Run: 上のテストが PASS(2 件)。

- [ ] **Step 3: `MainViewModel.onDownloadProgram` の失敗するテストを書く**

`MainViewModelBugfixTest.kt` の `setUp` で `val app = mockk<AirkastApplication>(relaxed = true)` となっている `app` を、クラスのフィールド `private lateinit var app: AirkastApplication` に変え(`setUp` 内は `app = mockk(relaxed = true)` に)、`@After` で `DownloadRegistry.clearForTest()` を呼ぶ。テストを追加する:

```kotlin
    @Test
    fun onDownloadProgram_doesNothingWhenTheEpisodeIsClaimedByAnotherWriter() {
        val program = AirkastProgram(
            "p1", "TBS", "20260124220000", "20260124230000", "アフター6", "", "", null
        )
        DownloadRegistry.tryClaim(program.episodeKey)
        val messages = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.userMessage.collect { messages += it }
        }

        viewModel.onDownloadProgram(program, 60)

        verify(exactly = 0) { app.startService(any()) }
        assertTrue(messages.any { it.contains("すでにダウンロード処理中") })
        collector.cancel()
    }
```

必要な import(`io.mockk.verify`、`kotlinx.coroutines.launch`、`com.example.airkast.favorites.episodeKey`)を足す。

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.MainViewModelBugfixTest"`
Expected: FAIL(`startService` が呼ばれる)

- [ ] **Step 4: `MainViewModel.onDownloadProgram` を実装する**

関数の先頭(`val intent = ...` の前)に追加する。import に `com.example.airkast.favorites.episodeKey` を追加:

```kotlin
        // 自動ダウンロードや中止処理の途中など、他の処理が同じ回を書き込み中ならサービスを起動しない
        if (DownloadRegistry.isClaimed(program.episodeKey)) {
            viewModelScope.launch { _userMessage.emit("この回はすでにダウンロード処理中です: ${program.title}") }
            return
        }
```

Run: 上のテストが PASS。

- [ ] **Step 5: `HlsDownloadService` の失敗するテストを書く**

`ServiceLifecycleRobolectricTest.kt` の既存テストの書き方(`Robolectric.buildService(HlsDownloadService::class.java, intent)` など)に合わせて、次のテストを追加する。`@After` に `DownloadRegistry.clearForTest()` を足す。

```kotlin
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
        val controller = Robolectric.buildService(HlsDownloadService::class.java, intent).create()

        controller.startCommand(0, 1)

        // 予約が取れない回は Pending にもならず、予約も奪わない
        assertNull(HlsDownloadService.downloadStates.value[program.id])
        assertTrue(DownloadRegistry.isClaimed(program.episodeKey))
        controller.destroy()
    }
```

必要な import(`DownloadRegistry`、`AirkastProgram`、`Intent`、`RuntimeEnvironment`、`assertNull`、`assertTrue`、`com.example.airkast.favorites.episodeKey`)を足す。

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.ServiceLifecycleRobolectricTest"`
Expected: FAIL(`Pending` の状態ができてしまう)

- [ ] **Step 6: `HlsDownloadService.startDownload` を実装する**

既存の重複チェック(`downloadJobs.containsKey`)の直後、`updateState(... Pending ...)` の前に追加する(import に `com.example.airkast.DownloadRegistry` と `com.example.airkast.favorites.episodeKey`):

```kotlin
        // 自動ダウンロードや、中止処理の途中のジョブなど、他の処理が同じ回を書き込み中なら開始しない
        if (!DownloadRegistry.tryClaim(program.episodeKey)) {
            Log.d(TAG, "他の処理が書き込み中のためスキップ: ${program.id}")
            return
        }
```

`val job = serviceScope.launch { ... }` の直後(`downloadJobs[program.id] = job` の前)に追加する:

```kotlin
        // 予約の解放はジョブの完了時に行う。ジョブが開始前にキャンセルされても必ず呼ばれ、
        // 後始末(catch 内のファイル削除)が終わった後に解放される。
        job.invokeOnCompletion { DownloadRegistry.release(program.episodeKey) }
```

注意: `cancelDownload` では予約を解放しない(後始末が終わるまで同じ回の再開を断るため)。

- [ ] **Step 7: 全テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline`
Expected: PASS(全件)

Run: `cd airkast_app && ./gradlew assembleDebug --offline`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/MainViewModel.kt \
        airkast_app/app/src/main/java/com/example/airkast/service/HlsDownloadService.kt \
        airkast_app/app/src/test/java/com/example/airkast
git commit -m "feat: Claim episodes in HlsDownloadService and guard manual downloads

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `latestEpisode` と時刻の共通関数

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/LatestEpisode.kt`
- Modify: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteMatcher.kt`(時刻判定を共通関数に)
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/LatestEpisodeTest.kt`

**Interfaces:**
- Consumes: `FavoriteMatcher.END_MARGIN_MS`、`normalizeProgramTitle`、`radikoTimestampFormat()`
- Produces:
  - `internal fun AirkastProgram.timeRangeMs(): Pair<Long, Long>?`(開始/終了のミリ秒。解釈できなければ null)
  - `internal fun AirkastProgram.hasEndedForDownload(nowMs: Long): Boolean`(終了が 現在 − `END_MARGIN_MS` 以前)
  - `fun latestEpisode(programs: List<AirkastProgram>, stationId: String, titleKey: String, nowMs: Long): AirkastProgram?`

- [ ] **Step 1: 失敗するテストを書く**

`LatestEpisodeTest.kt`:

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestEpisodeTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private fun prog(
        station: String = "TBS",
        title: String = "アフター6",
        start: String = "20260124220000",
        end: String = "20260124230000",
    ) = AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val key = normalizeProgramTitle("アフター6")

    @Test
    fun picksTheMostRecentEpisodeThatHasEnded() {
        val older = prog(start = "20260123220000", end = "20260123230000")
        val newer = prog(start = "20260124220000", end = "20260124230000")

        assertEquals(newer, latestEpisode(listOf(older, newer), "TBS", key, now))
    }

    @Test
    fun ignoresEpisodesOfOtherStationsAndTitles() {
        val other = prog(station = "QRR", start = "20260124220000", end = "20260124230000")
        val otherTitle = prog(title = "別の番組", start = "20260124230000", end = "20260125000000")
        val mine = prog(start = "20260123220000", end = "20260123230000")

        assertEquals(mine, latestEpisode(listOf(other, otherTitle, mine), "TBS", key, now))
    }

    @Test
    fun matchesTitlesRegardlessOfWidthAndSpacing() {
        val program = prog(title = "ＡＢＣ　ラジオ")

        assertEquals(program, latestEpisode(listOf(program), "TBS", normalizeProgramTitle("ABC ラジオ"), now))
    }

    @Test
    fun skipsEpisodesStillOnAirOrEndedWithinTheMargin() {
        val onAir = prog(start = "20260125110000", end = "20260125121000")
        val justEnded = prog(start = "20260125105600", end = "20260125115600")
        val settled = prog(start = "20260124220000", end = "20260124230000")

        assertEquals(settled, latestEpisode(listOf(onAir, justEnded, settled), "TBS", key, now))
    }

    @Test
    fun returnsNullWhenThereIsNoMatchingEpisode() {
        assertNull(latestEpisode(emptyList(), "TBS", key, now))
        assertNull(latestEpisode(listOf(prog(station = "QRR")), "TBS", key, now))
    }

    @Test
    fun ignoresProgramsWithUnparsableTimes() {
        assertNull(latestEpisode(listOf(prog(start = "", end = "")), "TBS", key, now))
    }
}
```

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.LatestEpisodeTest"`
Expected: FAIL(`Unresolved reference: latestEpisode`)

- [ ] **Step 2: `LatestEpisode.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat

/** 番組の (開始, 終了) をミリ秒で返す。時刻を解釈できなければ null。 */
internal fun AirkastProgram.timeRangeMs(): Pair<Long, Long>? {
    val format = radikoTimestampFormat()
    val start = runCatching { format.parse(startTime)?.time }.getOrNull()
    val end = runCatching { format.parse(endTime)?.time }.getOrNull()
    return if (start != null && end != null) start to end else null
}

/** 放送終了から [FavoriteMatcher.END_MARGIN_MS] 以上経ち、タイムフリーで取得できる状態か。 */
internal fun AirkastProgram.hasEndedForDownload(nowMs: Long): Boolean {
    val (_, end) = timeRangeMs() ?: return false
    return end <= nowMs - FavoriteMatcher.END_MARGIN_MS
}

/**
 * [programs] から、[stationId] と正規化済みの番組名 [titleKey] が一致し、取得できる状態になった
 * 最新の回(放送開始が最も新しい)を返す。なければ null。お気に入り画面の「最新回」に使う。
 */
fun latestEpisode(
    programs: List<AirkastProgram>,
    stationId: String,
    titleKey: String,
    nowMs: Long,
): AirkastProgram? = programs
    .filter { it.stationId == stationId && normalizeProgramTitle(it.title) == titleKey }
    .filter { it.hasEndedForDownload(nowMs) }
    .maxByOrNull { it.startTime }
```

- [ ] **Step 3: `FavoriteMatcher` を共通関数に寄せる**

`FavoriteMatcher.kt` の時刻判定の `.filter { program -> ... }` を次に置き換える(`radikoTimestampFormat` の import と `val format = radikoTimestampFormat()` が不要になれば削除):

```kotlin
            .filter { program ->
                val (start, _) = program.timeRangeMs() ?: return@filter false
                program.hasEndedForDownload(nowMs) && start >= nowMs - WINDOW_MS
            }
```

- [ ] **Step 4: テストを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline`
Expected: PASS(`LatestEpisodeTest` 6 件、`FavoriteMatcherTest` 11 件は変わらず通る)

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites \
        airkast_app/app/src/test/java/com/example/airkast/favorites/LatestEpisodeTest.kt
git commit -m "feat: Add latestEpisode and share time rules with FavoriteMatcher

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `FavoriteSyncControl` と `syncNow`

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncControl.kt`
- Modify: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncScheduler.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncSchedulerTest.kt`

**Interfaces:**
- Consumes: `FavoriteSyncScheduler.update(context, enabled)`、`buildConstraints()`
- Produces:
  - `FavoriteSyncScheduler.UNIQUE_NOW_NAME = "favorite_sync_now"`、`internal fun buildOneTimeRequest(): OneTimeWorkRequest`、`fun syncNow(context: Context)`
  - `interface FavoriteSyncControl { fun update(enabled: Boolean); fun syncNow() }`
  - `class WorkManagerSyncControl(context: Context) : FavoriteSyncControl`

- [ ] **Step 1: 失敗するテストを書く**

`FavoriteSyncSchedulerTest.kt` に追加:

```kotlin
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
```

import に `org.junit.Assert.assertNotEquals` を足す。

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoriteSyncSchedulerTest"`
Expected: FAIL(`Unresolved reference: UNIQUE_NOW_NAME`)

(`request.workSpec` が `@RestrictTo` の lint エラーでコンパイルできない場合は、`oneTimeRequestTargetsTheSyncWorker` を削除し、報告に理由を書く。)

- [ ] **Step 2: `syncNow` を実装する**

`FavoriteSyncScheduler` に追加(import: `androidx.work.ExistingWorkPolicy`、`androidx.work.OneTimeWorkRequest`、`androidx.work.OneTimeWorkRequestBuilder`):

```kotlin
    const val UNIQUE_NOW_NAME = "favorite_sync_now"

    /** 自動DL を ON にした直後などに、定期実行を待たず 1 回だけ同期するためのリクエスト。 */
    internal fun buildOneTimeRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FavoriteSyncWorker>()
        .setConstraints(buildConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()

    /** 条件(非従量課金回線・バッテリー低下でない)が整えば、すぐ 1 回同期する。実行待ちの要求があれば重複させない。 */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NOW_NAME, ExistingWorkPolicy.KEEP, buildOneTimeRequest())
    }
```

- [ ] **Step 3: `FavoriteSyncControl.kt` を作る**

```kotlin
package com.example.airkast.favorites

import android.content.Context

/** 定期同期の登録/解除と即時同期の操作。ViewModel を JVM でテストできるよう抽象化する。 */
interface FavoriteSyncControl {
    /** 自動DL が ON のお気に入りがあるときだけ定期実行を登録する。 */
    fun update(enabled: Boolean)

    /** すぐ 1 回同期する。 */
    fun syncNow()
}

class WorkManagerSyncControl(context: Context) : FavoriteSyncControl {
    private val appContext = context.applicationContext

    override fun update(enabled: Boolean) = FavoriteSyncScheduler.update(appContext, enabled)

    override fun syncNow() = FavoriteSyncScheduler.syncNow(appContext)
}
```

- [ ] **Step 4: テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest assembleDebug --offline`
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoriteSyncSchedulerTest.kt
git commit -m "feat: Add FavoriteSyncControl and one-time syncNow

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `FavoritesViewModel`

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoritesViewModel.kt`
- Test: `airkast_app/app/src/test/java/com/example/airkast/favorites/FavoritesViewModelTest.kt`

**Interfaces:**
- Consumes: `FavoriteRepository`、`FavoriteSyncControl`(Task 5)、`GuideSource`、`ClientGuideSource`、`WorkManagerSyncControl`、`latestEpisode`(Task 4)、`recentDates`、`normalizeProgramTitle`
- Produces:
  - `enum class FavoriteRowStatus { LOADING, LOADED, NO_RECENT_EPISODE, ERROR }`
  - `data class FavoriteRow(val favorite: Favorite, val latest: AirkastProgram?, val status: FavoriteRowStatus)`
  - `fun favoriteKeyOf(stationId: String, title: String): String`(`"$stationId|${正規化した番組名}"`)
  - `open class FavoritesViewModel(application, repository, syncControl, guides, now)` と、`constructor(application: Application)`(実依存)
  - `val rows: StateFlow<List<FavoriteRow>>`、`val favoriteKeys: StateFlow<Set<String>>`
  - `fun isFavorite(stationId, title): Boolean`、`fun toggleFavorite(stationId, title)`、`fun addFavorite(...)`、`fun removeFavorite(...)`、`fun setAutoDownload(stationId, title, enabled)`、`fun refresh(force: Boolean = true)`

- [ ] **Step 1: 失敗するテストを書く**

`FavoritesViewModelTest.kt`:

```kotlin
package com.example.airkast.favorites

import android.app.Application
import com.example.airkast.AirkastProgram
import com.example.airkast.radikoTimestampFormat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val now = radikoTimestampFormat().parse("20260125120000")!!.time

    private class MapStorage : TextStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakeControl : FavoriteSyncControl {
        val updates = mutableListOf<Boolean>()
        var syncNowCount = 0
        override fun update(enabled: Boolean) {
            updates += enabled
        }

        override fun syncNow() {
            syncNowCount++
        }
    }

    private class FakeGuides(
        private val onRequest: (String, String) -> List<AirkastProgram>,
    ) : GuideSource {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun programs(stationId: String, date: String): List<AirkastProgram> {
            requests += stationId to date
            return onRequest(stationId, date)
        }
    }

    private fun prog(station: String = "TBS", title: String = "アフター6", start: String = "20260124220000", end: String = "20260124230000") =
        AirkastProgram("$station-$start", station, start, end, title, "", "", null)

    private val storage = MapStorage()
    private val repository = FavoriteRepository(storage)
    private val control = FakeControl()

    private fun viewModel(guides: GuideSource) =
        FavoritesViewModel(mockk<Application>(relaxed = true), repository, control, guides) { now }

    private val defaultGuides = FakeGuides { station, date ->
        if (date == "20260124") listOf(prog(station = station)) else emptyList()
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun addingAFavoriteSavesItSchedulesSyncAndTriggersAnImmediateRun() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "アフター6")

        assertTrue(repository.isFavorite("TBS", "アフター6"))
        assertTrue(vm.isFavorite("TBS", "アフター6"))
        assertEquals(listOf(true), control.updates)
        assertEquals(1, control.syncNowCount)
    }

    @Test
    fun addingTheSameShowAgainWithDifferentWidthDoesNothing() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "ＡＢＣ　ラジオ")

        vm.addFavorite("TBS", "ABC ラジオ")

        assertEquals(1, repository.favorites().size)
        assertEquals(1, control.syncNowCount)
        assertEquals(setOf(favoriteKeyOf("TBS", "ABC ラジオ")), vm.favoriteKeys.value)
    }

    @Test
    fun blankTitlesAreIgnored() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "  ")

        assertTrue(repository.favorites().isEmpty())
        assertTrue(control.updates.isEmpty())
    }

    @Test
    fun removingTheLastFavoriteUnschedulesWithoutSyncingNow() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "アフター6")
        control.updates.clear()

        vm.removeFavorite("TBS", "アフター6")

        assertEquals(listOf(false), control.updates)
        assertEquals(1, control.syncNowCount)
        assertTrue(vm.rows.value.isEmpty())
        assertTrue(vm.favoriteKeys.value.isEmpty())
    }

    @Test
    fun togglingAutoDownloadUpdatesTheScheduleAndOnlySyncsWhenTurnedOn() {
        val vm = viewModel(defaultGuides)
        vm.addFavorite("TBS", "アフター6")
        control.updates.clear()
        val syncsAfterAdd = control.syncNowCount

        vm.setAutoDownload("TBS", "アフター6", false)
        assertEquals(listOf(false), control.updates)
        assertEquals(syncsAfterAdd, control.syncNowCount)

        vm.setAutoDownload("TBS", "アフター6", true)
        assertEquals(listOf(false, true), control.updates)
        assertEquals(syncsAfterAdd + 1, control.syncNowCount)
    }

    @Test
    fun toggleFavoriteAddsThenRemoves() {
        val vm = viewModel(defaultGuides)

        vm.toggleFavorite("TBS", "アフター6")
        assertTrue(vm.isFavorite("TBS", "アフター6"))

        vm.toggleFavorite("TBS", "アフター6")
        assertFalse(vm.isFavorite("TBS", "アフター6"))
    }

    @Test
    fun rowsShowTheLatestEpisode() {
        val vm = viewModel(defaultGuides)

        vm.addFavorite("TBS", "アフター6")

        val row = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.LOADED, row.status)
        assertEquals(prog().id, row.latest?.id)
    }

    @Test
    fun rowShowsNoRecentEpisodeWhenTheGuideHasNone() {
        val vm = viewModel(FakeGuides { _, _ -> emptyList() })

        vm.addFavorite("TBS", "アフター6")

        val row = vm.rows.value.single()
        assertEquals(FavoriteRowStatus.NO_RECENT_EPISODE, row.status)
        assertNull(row.latest)
    }

    @Test
    fun guideFailureOfOneStationOnlyAffectsItsOwnRows() {
        val guides = FakeGuides { station, date ->
            if (station == "QRR") throw java.io.IOException("offline")
            if (date == "20260124") listOf(prog(station = station)) else emptyList()
        }
        val vm = viewModel(guides)

        vm.addFavorite("TBS", "アフター6")
        vm.addFavorite("QRR", "アフター6")

        val byStation = vm.rows.value.associateBy { it.favorite.stationId }
        assertEquals(FavoriteRowStatus.LOADED, byStation.getValue("TBS").status)
        assertEquals(FavoriteRowStatus.ERROR, byStation.getValue("QRR").status)
    }

    @Test
    fun addingAnotherFavoriteOnlyFetchesTheGuideOfThatStation() {
        val guides = FakeGuides { _, _ -> emptyList() }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "A")
        val tbsRequests = guides.requests.count { it.first == "TBS" }
        assertEquals(8, tbsRequests)

        vm.addFavorite("QRR", "B")

        assertEquals(8, guides.requests.count { it.first == "TBS" })
        assertEquals(8, guides.requests.count { it.first == "QRR" })
    }

    @Test
    fun forcedRefreshFetchesAgain() {
        val guides = FakeGuides { _, _ -> emptyList() }
        val vm = viewModel(guides)
        vm.addFavorite("TBS", "A")

        vm.refresh(force = true)

        assertEquals(16, guides.requests.count { it.first == "TBS" })
    }
}
```

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoritesViewModelTest"`
Expected: FAIL(`Unresolved reference: FavoritesViewModel`)

- [ ] **Step 2: `FavoritesViewModel.kt` を実装する**

```kotlin
package com.example.airkast.favorites

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FavoritesViewModel"

enum class FavoriteRowStatus { LOADING, LOADED, NO_RECENT_EPISODE, ERROR }

/** お気に入り画面の 1 行。 [latest] は最新の放送済みの回(見つからなければ null)。 */
data class FavoriteRow(
    val favorite: Favorite,
    val latest: AirkastProgram?,
    val status: FavoriteRowStatus,
)

/** 局ID + 正規化した番組名。★の状態の判定に使う(全角/半角・空白の違いを吸収する)。 */
fun favoriteKeyOf(stationId: String, title: String): String = "$stationId|${normalizeProgramTitle(title)}"

/**
 * お気に入りの操作、各お気に入りの最新回の取得、定期同期の更新を受け持つ。
 * 通知権限の要求は画面側([com.example.airkast.ui.rememberNotificationPermissionGate])の責務で、
 * ここでは権限の有無に関わらず登録とスケジュール更新を行う(権限が無くても自動DLは動く)。
 */
open class FavoritesViewModel(
    application: Application,
    private val repository: FavoriteRepository,
    private val syncControl: FavoriteSyncControl,
    private val guides: GuideSource,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        FavoriteRepository(PrefsTextStorage(application)),
        WorkManagerSyncControl(application),
        ClientGuideSource((application as AirkastApplication).airkastClient),
    )

    private val _rows = MutableStateFlow<List<FavoriteRow>>(emptyList())
    val rows: StateFlow<List<FavoriteRow>> = _rows.asStateFlow()

    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys.asStateFlow()

    /** 取得済みの最新回(キーは [favoriteKeyOf])。Main スレッドからのみ触る。 */
    private val latestCache = mutableMapOf<String, FavoriteRow>()

    init {
        publish()
        refresh(force = false)
    }

    fun isFavorite(stationId: String, title: String): Boolean = favoriteKeyOf(stationId, title) in _favoriteKeys.value

    fun toggleFavorite(stationId: String, title: String) {
        if (isFavorite(stationId, title)) removeFavorite(stationId, title) else addFavorite(stationId, title)
    }

    /** お気に入りに追加する(自動DL は既定で ON)。すでに登録済みなら何もしない。 */
    fun addFavorite(stationId: String, title: String) {
        if (title.isBlank() || repository.isFavorite(stationId, title)) return
        repository.add(stationId, title)
        onFavoritesChanged(syncNow = true)
    }

    fun removeFavorite(stationId: String, title: String) {
        repository.remove(stationId, title)
        onFavoritesChanged(syncNow = false)
    }

    fun setAutoDownload(stationId: String, title: String, enabled: Boolean) {
        repository.setAutoDownload(stationId, title, enabled)
        onFavoritesChanged(syncNow = enabled)
    }

    /**
     * 各お気に入りの最新回を取得し直す。 [force] が false のときは、まだ取得していないお気に入りだけ取得する。
     * 局ごとに直近 8 日分の番組表を取得し、局単位の失敗はその局の行だけに影響させる。
     */
    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            val favorites = repository.favorites()
            val targets = if (force) favorites else favorites.filter { favoriteKeyOf(it.stationId, it.title) !in latestCache }
            val nowMs = now()
            for ((station, stationFavorites) in targets.groupBy { it.stationId }) {
                val programs = mutableListOf<AirkastProgram>()
                var succeeded = 0
                for (date in recentDates(nowMs)) {
                    try {
                        programs += guides.programs(station, date)
                        succeeded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "番組表の取得に失敗: $station $date: ${e.message}")
                    }
                }
                for (favorite in stationFavorites) {
                    latestCache[favoriteKeyOf(favorite.stationId, favorite.title)] = if (succeeded == 0) {
                        FavoriteRow(favorite, null, FavoriteRowStatus.ERROR)
                    } else {
                        val latest = latestEpisode(programs, station, favorite.key, nowMs)
                        FavoriteRow(
                            favorite,
                            latest,
                            if (latest != null) FavoriteRowStatus.LOADED else FavoriteRowStatus.NO_RECENT_EPISODE,
                        )
                    }
                }
                publish()
            }
            publish()
        }
    }

    private fun onFavoritesChanged(syncNow: Boolean) {
        val hasAutoDownload = repository.hasAutoDownload()
        syncControl.update(hasAutoDownload)
        if (syncNow && hasAutoDownload) syncControl.syncNow()
        publish()
        refresh(force = false)
    }

    /** リポジトリの内容とキャッシュから、画面用の状態を組み立て直す。 */
    private fun publish() {
        val favorites = repository.favorites()
        val keys = favorites.map { favoriteKeyOf(it.stationId, it.title) }
        latestCache.keys.retainAll(keys.toSet())
        _favoriteKeys.value = keys.toSet()
        _rows.value = favorites.map { favorite ->
            latestCache[favoriteKeyOf(favorite.stationId, favorite.title)]?.copy(favorite = favorite)
                ?: FavoriteRow(favorite, null, FavoriteRowStatus.LOADING)
        }
    }
}
```

- [ ] **Step 3: テストが通ることを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.favorites.FavoritesViewModelTest"`
Expected: PASS(11 件)

Run: `cd airkast_app && ./gradlew testDebugUnitTest assembleDebug --offline`
Expected: PASS(全件)/ BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add airkast_app/app/src/main/java/com/example/airkast/favorites/FavoritesViewModel.kt \
        airkast_app/app/src/test/java/com/example/airkast/favorites/FavoritesViewModelTest.kt
git commit -m "feat: Add FavoritesViewModel for favorites actions and latest episodes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 通知権限・タブ番号・通知タップ

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/ui/Tabs.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/ui/NotificationPermission.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteIntents.kt`
- Modify: `airkast_app/app/src/main/java/com/example/airkast/favorites/FavoriteSyncWorker.kt`(`notifyDownloaded`)
- Modify: `airkast_app/app/src/main/AndroidManifest.xml`(`MainActivity` に `launchMode="singleTop"`)
- Test: `airkast_app/app/src/test/java/com/example/airkast/ui/TabsTest.kt`、`.../ui/NotificationPermissionTest.kt`、`.../favorites/FavoriteIntentsTest.kt`

**Interfaces:**
- Consumes: `MainActivity`(`com.example.airkast.MainActivity`)
- Produces:
  - `const val TAB_FAVORITES = 0`、`TAB_FIND = 1`、`TAB_DOWNLOADS = 2`、`const val EXTRA_OPEN_TAB = "open_tab"`、`const val OPEN_TAB_FAVORITES = "favorites"`、`fun tabIndexForExtra(name: String?): Int`(`ui` パッケージ)
  - `internal fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean): Boolean`
  - `@Composable fun rememberNotificationPermissionGate(onDenied: () -> Unit): () -> Unit`(戻り値を呼ぶと、必要なときだけ権限を要求する)
  - `fun openFavoritesIntent(context: Context): Intent`(`favorites` パッケージ)

- [ ] **Step 1: 失敗するテストを書く**

`ui/TabsTest.kt`:

```kotlin
package com.example.airkast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabsTest {
    @Test
    fun favoritesIsTheDefaultTab() {
        assertEquals(TAB_FAVORITES, tabIndexForExtra(null))
        assertEquals(TAB_FAVORITES, tabIndexForExtra(""))
        assertEquals(TAB_FAVORITES, tabIndexForExtra("unknown-tab"))
    }

    @Test
    fun knownNamesMapToTheirTabs() {
        assertEquals(TAB_FAVORITES, tabIndexForExtra(OPEN_TAB_FAVORITES))
        assertEquals(TAB_FIND, tabIndexForExtra("find"))
        assertEquals(TAB_DOWNLOADS, tabIndexForExtra("downloads"))
    }

    @Test
    fun tabOrderIsFavoritesFindDownloads() {
        assertEquals(listOf(0, 1, 2), listOf(TAB_FAVORITES, TAB_FIND, TAB_DOWNLOADS))
    }
}
```

`ui/NotificationPermissionTest.kt`:

```kotlin
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
```

`favorites/FavoriteIntentsTest.kt`(Robolectric):

```kotlin
package com.example.airkast.favorites

import android.content.Intent
import com.example.airkast.MainActivity
import com.example.airkast.TestAirkastApplication
import com.example.airkast.ui.OPEN_TAB_FAVORITES
import com.example.airkast.ui.EXTRA_OPEN_TAB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestAirkastApplication::class)
class FavoriteIntentsTest {
    @Test
    fun openFavoritesIntentTargetsMainActivityWithTheFavoritesTab() {
        val intent = openFavoritesIntent(RuntimeEnvironment.getApplication())

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(OPEN_TAB_FAVORITES, intent.getStringExtra(EXTRA_OPEN_TAB))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
```

(`TestAirkastApplication` が `ServiceLifecycleRobolectricTest.kt` に `class TestAirkastApplication : AirkastApplication()` として定義されていて、そのファイルのトップレベルから参照できない場合は、ファイルを分けるか、可視性を確認して同じパッケージ参照にする。)

Run: `cd airkast_app && ./gradlew testDebugUnitTest --offline --tests "com.example.airkast.ui.*" --tests "com.example.airkast.favorites.FavoriteIntentsTest"`
Expected: FAIL(`Unresolved reference`)

- [ ] **Step 2: `Tabs.kt` を実装する**

```kotlin
package com.example.airkast.ui

/** タブの番号(ナビゲーションバーの並び順と同じ)。 */
const val TAB_FAVORITES = 0
const val TAB_FIND = 1
const val TAB_DOWNLOADS = 2

/** 通知タップなどで開くタブを指定する Intent の extra。 */
const val EXTRA_OPEN_TAB = "open_tab"
const val OPEN_TAB_FAVORITES = "favorites"

/** extra の値からタブ番号を決める。未指定・不明な値はお気に入りタブ。 */
fun tabIndexForExtra(name: String?): Int = when (name) {
    "find" -> TAB_FIND
    "downloads" -> TAB_DOWNLOADS
    else -> TAB_FAVORITES
}
```

- [ ] **Step 3: `NotificationPermission.kt` を実装する**

```kotlin
package com.example.airkast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android 13 以降で、通知権限がまだ無いときだけ要求する。 */
internal fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

/**
 * 通知権限の要求ゲート。戻り値の関数を「自動DL を ON にする操作の直前」に呼ぶ。
 * 権限が必要で未許可のときだけ OS のダイアログを出し、拒否されたら [onDenied] を呼ぶ
 * (「今後表示しない」の状態では、ダイアログなしで即 [onDenied] になる)。
 * 登録や自動DL 自体は権限に関わらず有効なので、呼び出し側は結果を待たずに処理を続けてよい。
 */
@Composable
fun rememberNotificationPermissionGate(onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) currentOnDenied()
    }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (shouldRequestNotificationPermission(Build.VERSION.SDK_INT, granted)) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
```

(`rememberUpdatedState` の `by` に `import androidx.compose.runtime.getValue` が必要。)

- [ ] **Step 4: `FavoriteIntents.kt` を実装し、Worker と Manifest に配線する**

`FavoriteIntents.kt`:

```kotlin
package com.example.airkast.favorites

import android.content.Context
import android.content.Intent
import com.example.airkast.MainActivity
import com.example.airkast.ui.EXTRA_OPEN_TAB
import com.example.airkast.ui.OPEN_TAB_FAVORITES

/** 完了通知のタップで開く Intent。 MainActivity をお気に入りタブで前面に出す。 */
fun openFavoritesIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
    putExtra(EXTRA_OPEN_TAB, OPEN_TAB_FAVORITES)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}
```

`FavoriteSyncWorker.notifyDownloaded` の `NotificationCompat.Builder(...)` に、`.setAutoCancel(true)` の前後で次を追加する(import: `android.app.PendingIntent`):

```kotlin
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    openFavoritesIntent(applicationContext),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
```

`AndroidManifest.xml` の `MainActivity` の `<activity>` に `android:launchMode="singleTop"` を追加する(通知タップで `onNewIntent` に届けるため)。

- [ ] **Step 5: テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest assembleDebug --offline`
Expected: PASS(`TabsTest` 3 件、`NotificationPermissionTest` 2 件、`FavoriteIntentsTest` 1 件を含む全件)/ BUILD SUCCESSFUL

- [ ] **Step 6: コミット**

```bash
git add airkast_app/app/src/main airkast_app/app/src/test
git commit -m "feat: Add notification permission gate, tab routing and notification tap intent

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 画面(お気に入りタブ・★・3 タブ)と配線、ドキュメント

**Files:**
- Create: `airkast_app/app/src/main/java/com/example/airkast/ui/components/FavoriteRow.kt`
- Create: `airkast_app/app/src/main/java/com/example/airkast/ui/screens/FavoritesScreen.kt`
- Modify: `airkast_app/app/src/main/java/com/example/airkast/ui/components/Rows.kt`(`ProgramRow` に★)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/ui/screens/FindProgramsScreen.kt`(★の配線)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/ui/AirkastApp.kt`(3 タブ、ゲート、スナックバー)
- Modify: `airkast_app/app/src/main/java/com/example/airkast/MainActivity.kt`(`FavoritesViewModel`、`onNewIntent`、復帰時の更新)
- Modify: `airkast_app/app/src/main/res/values/strings.xml`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `FavoritesViewModel`・`FavoriteRow`・`FavoriteRowStatus`・`favoriteKeyOf`(Task 6)、`DownloadRegistry.claimed`(Task 1)、`findDownloadedFile`(Task 3)、`rememberNotificationPermissionGate`・`TAB_*`・`EXTRA_OPEN_TAB`・`tabIndexForExtra`(Task 7)、既存の `DownloadButton`・`resolveProgramDownloadState`・`MainViewModel.onPlayFile/onDownloadProgram/ongoingDownloadStates/downloadedFiles/downloadChunkSize/stationUiState`
- Produces:
  - `ProgramRow(..., isFavorite: Boolean = false, onToggleFavorite: (() -> Unit)? = null)`
  - `FavoritesScreen(favoritesViewModel, mainViewModel, contentBottomPadding, onBeforeEnableAutoDownload: () -> Unit, modifier)`
  - `FindProgramsScreen(viewModel, favoritesViewModel, contentBottomPadding, onBeforeEnableAutoDownload, modifier)`
  - `MainApp(viewModel, favoritesViewModel, openTabRequest: String?, onOpenTabHandled: () -> Unit)`

Compose の画面は JVM の単体テストを書かない。ビルドとエミュレータでの確認で検証する(Step 9)。

- [ ] **Step 1: 文言を追加する(`strings.xml`)**

既存の `nav_find`/`nav_downloads` の近くに追加する(文言はそのまま使う):

```xml
    <string name="nav_favorites">お気に入り</string>
    <string name="favorites_empty_title">お気に入りはまだありません</string>
    <string name="favorites_empty_body">番組表の★で追加できます</string>
    <string name="favorite_add">お気に入りに追加</string>
    <string name="favorite_remove">お気に入りから外す</string>
    <string name="favorite_auto_download">自動DL</string>
    <string name="favorite_loading">読み込み中…</string>
    <string name="favorite_no_recent">直近7日間に放送がありません</string>
    <string name="favorite_load_error">番組表を取得できませんでした</string>
    <string name="favorite_auto_running">自動取得中</string>
    <string name="notification_denied">通知がオフです。自動ダウンロードは動きますが、完了は通知されません</string>
    <string name="open_notification_settings">設定を開く</string>
```

- [ ] **Step 2: `ProgramRow` に★を追加する**

`Rows.kt` の `ProgramRow` に引数 `isFavorite: Boolean = false`、`onToggleFavorite: (() -> Unit)? = null` を追加し(`modifier` の前)、ダウンロードボタン(またはスケジュールのアイコン)の**前**に、`onToggleFavorite != null` のときだけ星のボタンを置く:

```kotlin
            if (onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
                        ),
                        tint = if (isFavorite) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
```

(import: `androidx.compose.material.icons.filled.Star`、`androidx.compose.material.icons.outlined.StarBorder`、`androidx.compose.material3.IconButton`。`material-icons-extended` は依存済み。)

- [ ] **Step 3: `FindProgramsScreen` に★を配線する**

- `FindProgramsScreen` に引数 `favoritesViewModel: FavoritesViewModel` と `onBeforeEnableAutoDownload: () -> Unit` を追加する。
- `ProgramGuideList` に同じ 2 つを渡し、内部で `val favoriteKeys by favoritesViewModel.favoriteKeys.collectAsState()` を読む。
- `ProgramRow(...)` の呼び出しに追加:

```kotlin
                isFavorite = favoriteKeyOf(program.stationId, program.title) in favoriteKeys,
                onToggleFavorite = {
                    val adding = favoriteKeyOf(program.stationId, program.title) !in favoriteKeys
                    // 自動DL が ON になる操作(追加)の直前にだけ通知権限を確認する
                    if (adding) onBeforeEnableAutoDownload()
                    favoritesViewModel.toggleFavorite(program.stationId, program.title)
                },
```

- [ ] **Step 4: `FavoriteRow.kt`(行のコンポーネント)を作る**

```kotlin
package com.example.airkast.ui.components

// import は ProgramRow / DownloadedFileRow と同じ流儀で必要なものを足す

/**
 * お気に入りの 1 行。番組名・局名・最新回の日時、状態に応じた操作、自動DL のスイッチ、★(解除)。
 * 状態は [downloadState] と [autoRunning] だけから描画する。
 */
@Composable
fun FavoriteRowCard(
    row: FavoriteRow,
    stationName: String,
    downloadState: ProgramDownloadState,
    autoRunning: Boolean,
    shape: Shape,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = colors.surfaceContainer) {
        Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.favorite.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stationName,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite_remove),
                        tint = colors.primary,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 最新回の見出し(日時) または 状態メッセージ
                Column(modifier = Modifier.weight(1f)) {
                    val latest = row.latest
                    when {
                        row.status == FavoriteRowStatus.LOADING ->
                            Text(stringResource(R.string.favorite_loading), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        row.status == FavoriteRowStatus.ERROR ->
                            Text(stringResource(R.string.favorite_load_error), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        latest == null ->
                            Text(stringResource(R.string.favorite_no_recent), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        else -> {
                            Text(
                                text = formatLatestHeadline(latest),
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                color = colors.onSurface,
                            )
                            if (autoRunning && downloadState is ProgramDownloadState.Idle) {
                                Text(
                                    text = stringResource(R.string.favorite_auto_running),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                }
                val latest = row.latest
                if (latest != null) {
                    when {
                        downloadState is ProgramDownloadState.Completed ->
                            // 取得済みは再生ボタン(DownloadButton の完了表示は「再生」を兼ねないため別ボタン)
                            IconButton(onClick = onPlayClick, modifier = Modifier.size(MinTouchTarget)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = latest.title)
                            }
                        autoRunning && downloadState is ProgramDownloadState.Idle ->
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else -> DownloadButton(state = downloadState, programTitle = latest.title, onClick = onDownloadClick)
                    }
                }
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.favorite_auto_download), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.xs))
                Switch(checked = row.favorite.autoDownload, onCheckedChange = onAutoDownloadChange)
            }
        }
    }
}

/** 「1/24(土) 22:00 - 23:00」のような最新回の見出し。 */
private fun formatLatestHeadline(program: AirkastProgram): String {
    val date = runCatching {
        SimpleDateFormat("M/d(E)", Locale.JAPAN).format(radikoTimestampFormat().parse(program.startTime)!!)
    }.getOrDefault("")
    return "$date ${program.formattedDisplayTime}".trim()
}
```

(`TabularNumbers`・`Spacing`・`MinTouchTarget` は既存のテーマ/コンポーネントから import する。`DownloadButton` の `Completed` 状態の見た目が「再生」を兼ねる場合は、再生ボタンへの分岐を実装に合わせて整理し、どちらを採ったか報告に書く。)

- [ ] **Step 5: `FavoritesScreen.kt` を作る**

```kotlin
package com.example.airkast.ui.screens

// import は FindProgramsScreen / DownloadsScreen と同じ流儀で必要なものを足す

/** お気に入りタブ。各番組の最新回を 1 タップで再生/ダウンロードできる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoritesViewModel: FavoritesViewModel,
    mainViewModel: MainViewModel,
    contentBottomPadding: Dp,
    onBeforeEnableAutoDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by favoritesViewModel.rows.collectAsState()
    val ongoing by mainViewModel.ongoingDownloadStates.collectAsState()
    val downloadedFiles by mainViewModel.downloadedFiles.collectAsState()
    val chunkSize by mainViewModel.downloadChunkSize.collectAsState()
    val stationState by mainViewModel.stationUiState.collectAsState()
    val claimed by DownloadRegistry.claimed.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    // タブを開くたびに最新回を更新する(キャッシュ済みでも取り直す)
    LaunchedEffect(Unit) { favoritesViewModel.refresh(force = true) }

    val stationNames = remember(stationState) {
        (stationState as? UiState.Success)?.data?.associate { it.id to it.name }.orEmpty()
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            favoritesViewModel.refresh(force = true)
            refreshing = false
        },
        modifier = modifier.fillMaxSize(),
    ) {
        if (rows.isEmpty()) {
            StateMessage(
                icon = Icons.Outlined.StarBorder,
                title = stringResource(R.string.favorites_empty_title),
                body = stringResource(R.string.favorites_empty_body),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.l, end = Spacing.l, top = Spacing.xs,
                    bottom = contentBottomPadding + Spacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> favoriteKeyOf(row.favorite.stationId, row.favorite.title) }) { index, row ->
                    val latest = row.latest
                    val state = if (latest == null) {
                        ProgramDownloadState.Idle
                    } else {
                        resolveProgramDownloadState(
                            status = ongoing[latest.id],
                            isFileDownloaded = isProgramDownloaded(downloadedFiles, latest),
                        )
                    }
                    FavoriteRowCard(
                        row = row,
                        stationName = stationNames[row.favorite.stationId] ?: row.favorite.stationId,
                        downloadState = state,
                        autoRunning = latest != null && latest.episodeKey in claimed,
                        shape = groupedItemShape(index, rows.size),
                        onDownloadClick = { latest?.let { mainViewModel.onDownloadProgram(it, chunkSize) } },
                        onPlayClick = {
                            latest?.let { program ->
                                findDownloadedFile(downloadedFiles, program)?.let { mainViewModel.onPlayFile(it, program) }
                            }
                        },
                        onAutoDownloadChange = { enabled ->
                            if (enabled) onBeforeEnableAutoDownload()
                            favoritesViewModel.setAutoDownload(row.favorite.stationId, row.favorite.title, enabled)
                        },
                        onRemove = { favoritesViewModel.removeFavorite(row.favorite.stationId, row.favorite.title) },
                    )
                }
            }
        }
    }
}
```

(`StateMessage` の引数名は既存の定義に合わせる。`PullToRefreshBox` は `androidx.compose.material3.pulltorefresh.PullToRefreshBox`。`refresh` は即座に return するので、`refreshing` は短時間で戻る。ネットワークの完了を待つ表示にしたい場合は `FavoritesViewModel` に `isRefreshing` を足してよいが、この計画では範囲外。)

- [ ] **Step 6: `AirkastApp.kt` を 3 タブにして配線する**

- 既存の private 定数 `TAB_FIND`/`TAB_DOWNLOADS` を削除し(`ui/Tabs.kt` のものを使う)、`MainApp` のシグネチャを `MainApp(viewModel: MainViewModel, favoritesViewModel: FavoritesViewModel, openTabRequest: String?, onOpenTabHandled: () -> Unit)` にする。
- 初期タブ: `var tabIndex by rememberSaveable { mutableStateOf(tabIndexForExtra(openTabRequest)) }`。さらに:

```kotlin
    LaunchedEffect(openTabRequest) {
        if (openTabRequest != null) {
            tabIndex = tabIndexForExtra(openTabRequest)
            onOpenTabHandled()
        }
    }
```

- 通知権限のゲートと、拒否時のスナックバー(設定へ誘導):

```kotlin
    val scope = rememberCoroutineScope()
    val deniedMessage = stringResource(R.string.notification_denied)
    val settingsLabel = stringResource(R.string.open_notification_settings)
    val ensureNotificationPermission = rememberNotificationPermissionGate(
        onDenied = {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = deniedMessage,
                    actionLabel = settingsLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }
        },
    )
```

- トップバーのタイトル: `tabIndex` に応じて `R.string.nav_favorites` / `nav_find` / `nav_downloads`。
- ナビゲーションバーを 3 項目(お気に入り=`Icons.Filled.Star`/`Outlined.StarBorder`、番組を探す、ダウンロード)にし、並びは お気に入り → 番組を探す → ダウンロード。
- `when (tab)` を `TAB_FAVORITES -> FavoritesScreen(favoritesViewModel, viewModel, reserved, ensureNotificationPermission)`、`TAB_FIND -> FindProgramsScreen(viewModel, favoritesViewModel, reserved, ensureNotificationPermission)`、`else -> DownloadsScreen(viewModel, reserved)` にする。

- [ ] **Step 7: `MainActivity.kt` を配線する**

```kotlin
    private val favoritesViewModel: FavoritesViewModel by viewModels()
    private var openTabRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openTabRequest = intent.getStringExtra(EXTRA_OPEN_TAB)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchDateChanges() }
        }
        // アプリ復帰時にお気に入りの最新回を更新する
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { favoritesViewModel.refresh(force = true) }
        }
        setContent {
            AirkastTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainApp(
                        viewModel = viewModel,
                        favoritesViewModel = favoritesViewModel,
                        openTabRequest = openTabRequest,
                        onOpenTabHandled = { openTabRequest = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabRequest = intent.getStringExtra(EXTRA_OPEN_TAB)
    }
```

(import: `androidx.compose.runtime.getValue`/`setValue`/`mutableStateOf`、`android.content.Intent`、`com.example.airkast.favorites.FavoritesViewModel`、`com.example.airkast.ui.EXTRA_OPEN_TAB`。)

- [ ] **Step 8: 全テストとビルドを確認する**

Run: `cd airkast_app && ./gradlew testDebugUnitTest assembleDebug --offline`
Expected: PASS(全件)/ BUILD SUCCESSFUL(コンパイル警告で新規のものがあれば報告に書く)

- [ ] **Step 9: エミュレータで確認する**

エミュレータ(例: `Pixel_9_Pro` の AVD)があれば起動して、次を確認し、観察結果を報告に書く(スクリーンショットは `adb exec-out screencap -p > <ファイル>` で保存し、読んで確認する)。エミュレータが使えない場合は、その旨と未確認の項目を明記する。

```bash
export ANDROID_HOME=~/Library/Android/sdk
$ANDROID_HOME/platform-tools/adb devices
$ANDROID_HOME/platform-tools/adb install -r airkast_app/app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.example.airkast/.MainActivity
```

確認項目: (1) 起動時にお気に入りタブが開き、0 件の案内が出る。(2) 「番組を探す」の番組行に★があり、タップで塗りつぶしになる(初回は Android 13 以降なら通知権限ダイアログが出る)。(3) お気に入りタブに行が増え、最新回・自動DL スイッチ・★が出る。(4) スイッチの ON/OFF と★での解除が反映される。(5) ダウンロード済みの回は再生ボタンになる。(6) 通知権限を拒否したとき、スナックバーと「設定を開く」が出る。ライト/ダークの両方で崩れていないこと。

- [ ] **Step 10: `CLAUDE.md` を更新する**

「お気に入り」の節の「UIが入るまで休眠」と「UI計画で必須」の記述を、実装された内容に置き換える(`DownloadRegistry` による衝突対策、`FavoritesViewModel` が変更のたびにスケジュールを更新し自動DL ON 時に `syncNow` を呼ぶこと、通知権限は自動DL を ON にする操作の直前に要求すること、通知タップでお気に入りタブが開くこと)。まだ残る制約(定期同期は実機で未確認、Worker はフォアグラウンド昇格なしで実行枠に制約される、実機でのバックグラウンド動作は手動確認が必要)は書き残す。

- [ ] **Step 11: コミット**

```bash
git add airkast_app/app/src/main CLAUDE.md
git commit -m "feat: Add favorites tab, star toggle and 3-tab navigation

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. 仕様の網羅:** §2 構成(Task 1〜8)、§3 衝突対策(`DownloadRegistry`=Task 1、`FavoriteSync`・Worker=Task 2、サービス・`MainViewModel`=Task 3、画面の「自動取得中」=Task 8)、§4 画面(`latestEpisode`=Task 4、`FavoritesViewModel`=Task 6、お気に入りタブ・★・3 タブ=Task 8)、§5 通知権限(Task 7 のゲート・Task 8 の配線・`contentIntent`)、§6 スケジュール(`syncNow`=Task 5、変更のたびの更新=Task 6)、§7 エラー処理(Task 6 の局単位の失敗、Task 2・3 の予約失敗のスキップ)、§8 テスト(Task 1〜7 の単体テストとエミュレータ確認)、§9 段階(Task 1〜3=段階1、Task 4〜6=段階2、Task 7=段階3、Task 8=段階4・5)。
**2. プレースホルダ:** なし。Task 8 の Compose コードの import は既存ファイルの流儀に従う旨を明記(実装者が既存ファイルを見て足す)。
**3. 型の整合:** `FavoriteSync` の引数順(repository, guides, downloader, downloadedFiles, claim, release, now)は Task 2 の実装・テスト・Worker で一致。`FavoritesViewModel` のコンストラクタ(application, repository, syncControl, guides, now)は Task 6 の実装・テストで一致。`favoriteKeyOf`、`FavoriteRow`、`FavoriteRowStatus` は Task 6 で定義し Task 8 が使う。`TAB_*`/`EXTRA_OPEN_TAB`/`tabIndexForExtra` は Task 7 で定義し Task 8 が使う。
**4. Review Focus:** 5 項目すべてに、所有タスクのテストがある(解放漏れ=Task 2・3、再開の競合=Task 3、正規化=Task 6、局単位の失敗=Task 6、権限・不明 extra=Task 6・7)。
