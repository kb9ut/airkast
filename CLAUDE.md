# Airkast

radikoのタイムフリー番組をダウンロードし、倍速再生・秒送りで聴くための個人利用向け Android アプリ。
AIエージェントと開発者の共通の指示書・仕様書は、このファイルに集約する(重複するドキュメントを増やさない)。

## 作業上の前提

- 対象は **Androidアプリ (`airkast_app/`) のみ**。iOS版・Web版は削除済みで、復活の予定はない。
- ドキュメントとUI文言は日本語。コミットメッセージは conventional style (`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:` / `redesign:`)。
- 認証は radiko の **PC版 (html5)** 方式。radiko はIPアドレスで位置を判定するため、**日本国内のネットワーク**から利用する。
- main へ直接 push する運用(個人プロジェクト)。

## コマンド

```bash
cd airkast_app
./gradlew assembleDebug        # デバッグAPK
./gradlew testDebugUnitTest    # 単体テスト (JUnit4 / Ktor MockEngine / MockK / Robolectric)
```

- **JDK 17** (`airkast_app/.tool-versions`)。Android Studio 同梱の JBR (JDK 21) でも可。**既定の JDK 24 では Gradle 8.11.1 が動かない**。
  例: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk`
- SDK: `compileSdk = 36` / `targetSdk = 36` / `minSdk = 26`。`local.properties` に `sdk.dir` を設定する (Git管理外)。
- テストは **JUnit4 で統一**。`useJUnitPlatform()` を有効にすると JUnit4 のテストが実行されなくなるので、有効にしない。
- 主な依存: Compose BOM `2025.06.00` (Material3 1.3.x)、Ktor Client (Android エンジン)、Kotlin Coroutines、AndroidX Lifecycle/ViewModel、Media3 (ExoPlayer / HLS / Session)、play-services-oss-licenses。

## 機能

- **UI:** Jetpack Compose + Material3。システム設定に追従するライト/ダーク、Android 12+ は Dynamic Color。ナビゲーションは「お気に入り」「番組を探す」「ダウンロード」の3つ。トップバーのメニューからエリア変更・ダウンロード分割サイズ設定・ライセンス表示を行う。デザインの方針は [`airkast_app/DESIGN.md`](airkast_app/DESIGN.md)。
- **認証・エリア:** 起動時にPC版認証を実行し、IPアドレスからエリアを自動判定。手動変更も可能 (`AreaSelectionDialog` に定義した12エリア)。
- **番組表:** 放送局を選択 → 過去7日間から日付を選択 → 時間帯フィルター (早朝 5-9 / 昼 9-17 / 夜 17-24 / 深夜 24-29)。アプリを開いたまま日付が変わった場合は日付リストを更新する (`MainViewModel.refreshDateIfChanged`)。選択した放送局・時間帯は `SharedPreferences` に保存される。
- **ダウンロード:** 放送終了済みの番組のみ。`HlsDownloadService` (Foreground Service, `dataSync`) が実行し、通知に進捗を表示。同時実行は最大3件。分割サイズ (15秒 / 60秒(既定) / 5分 / 一括) を選べる。
- **再生:** `PlayerService` (MediaLibraryService + ExoPlayer) でダウンロード済みファイルを再生。倍速 (0.5x〜2.0x)、スキップ (戻し: 60秒 / 30秒 / 15秒、送り: 60秒 / 2分 / 3分)、再生位置の自動保存・復元。ストリーミング再生 (`MainViewModel.playStream` → `PlayerCommands.PLAY_STREAM`) の実装もある。
- **お気に入り:** 番組を探すの★で番組(局 + 正規化した番組名)を登録。自動DL が ON のお気に入りは、WorkManager の定期同期(6時間ごと、非従量課金回線・バッテリー低下でないとき)と、★追加/スイッチ ON 直後の 1 回同期で、新しい回を自動ダウンロードする。お気に入りタブで各番組の最新回を再生/ダウンロードできる。
- **削除:** ダウンロード画面から個別に削除。

## アーキテクチャ

MVVM。`airkast_app/app/src/main/java/com/example/airkast/` 以下:

| パス | 役割 |
|------|------|
| `MainActivity.kt` | Activity のみ。`ui/AirkastApp.kt` を表示し、表示中(STARTED)は日付変更の監視 (`watchDateChanges`)・ダウンロード済みファイルの再スキャン・お気に入りの最新回の更新を行う。通知タップなどのタブ指定は `onCreate`(コールドスタート)と `onNewIntent` で受ける |
| `ui/` | Compose UI。`theme/` (色・文字・形・モーション)、`components/` (ボタン・行・プレイヤー等)、`screens/` (お気に入り・番組を探す・ダウンロード)、`ProgramDownloadState.kt` (ダウンロードボタンの状態) |
| `MainViewModel.kt` | UI状態 (StateFlow) の管理。`AirkastClient`、`HlsDownloadService`、`PlayerService` (MediaController経由) と連携 |
| `UiState.kt` | `UiState<T>` (Idle/Loading/Success/Error) と `TimeFilter` |
| `AirkastApplication.kt` | `AirkastClient` と `PlaybackPositionManager` のシングルトン保持、`PlayerService` からのエラー通知用 SharedFlow |
| `AirkastClient.kt` | radiko API クライアント (認証、放送局、番組表、HLS URL生成)。認証トークン・エリアIDを保持 |
| `AirkastProgram.kt` / `AirkastStation.kt` | 番組・放送局のデータクラス (`yyyyMMddHHmmss` 形式の時刻の補助を含む) |
| `ApiException.kt` | API系エラーの sealed クラス階層 |
| `HlsDownloader.kt` | HLSのプレイリスト解析・セグメント取得・M4Aへのmuxing |
| `PlaybackPositionManager.kt` | 番組ごとの再生位置を `SharedPreferences` に保存・復元 |
| `ParcelableCompat.kt` | API 33 前後の `getParcelable` 差異を吸収する拡張関数 |
| `DownloadRegistry.kt` | 回(`局ID:開始時刻`)ごとの「取得中」予約のプロセス全体のレジストリ。手動ダウンロード・`HlsDownloadService`・`FavoriteSync` が同じファイルへ同時に書き込まないようにする。`claimed` を UI も読む |
| `favorites/` | お気に入りと自動ダウンロード。`FavoritesViewModel`(★・自動DL の操作、最新回の取得)、`FavoriteRepository`(保存)、`FavoriteMatcher`・`LatestEpisode.kt`(新しい回の判定)、`FavoriteSync`(同期の手順)、`FavoriteSyncWorker`(WorkManager の Worker)、`FavoriteSyncScheduler` / `FavoriteSyncControl`(定期・1 回だけの同期の登録)、`FavoriteIntents.kt`(通知タップでお気に入りタブを開く) |
| `service/HlsDownloadService.kt` | ダウンロード用 Foreground Service。状態を静的な `downloadStates` (StateFlow) で公開 |
| `service/PlayerService.kt` | 再生用 MediaLibraryService (ExoPlayer、位置の定期保存、通知) |
| `service/PlayerCommands.kt` | `MainViewModel` ↔ `PlayerService` 間のカスタムコマンド名と Bundle キー |

### 認証フロー (PC版)

1. `auth1`: `X-Radiko-Authtoken` / `X-Radiko-KeyLength` / `X-Radiko-KeyOffset` を取得。
2. PC版JavaScriptに公開されている鍵 (`bcd151073c03b352e1ef2fd66c32209da9ca0afa`) の `offset` から `length` バイトを切り出して Base64 → 部分キー。外部ファイル (APK等) への依存はない。
3. `auth2`: トークンと部分キーを送り、`JP13,東京都,tokyo` 形式のエリア情報を取得 (`OUT` は圏外)。
4. radiko.jp で得た Cookie を SmartStream (`*.smartstream.ne.jp`) のホストへ複製する (タイムフリー配信に必要)。

### 番組表

`MainViewModel` が `AirkastClient.getProgramGuide(stationId, yyyyMMdd)` を呼び、XMLを `AirkastProgram` のリストに変換して `UiState` に反映する。

### ダウンロード

1. ダウンロードボタン → `MainViewModel.onDownloadProgram` が `HlsDownloadService` を `startService` で起動。
2. サービスは番組の開始〜終了を分割サイズごとのチャンクに分け、チャンクごとにタイムフリーのプレイリストURLを生成して `HlsDownloader.downloadChunked` に渡す。
3. セグメント (ADTS AAC) をそのまま一時ファイル (`*.adts`) に追記する。**コードにAES復号処理はない**。セグメントは重複排除 (クエリを除いたURLで判定) しながら結合される。
4. 全チャンク完了後、`MediaMuxer` で M4A に変換 (進捗は「ダウンロード中」「変換中」の2段階)。変換に失敗した場合は ADTS のままファイルとして残す。
5. 保存先は `getExternalFilesDir("downloads")`、ファイル名は `{stationId}_{title}_{startTime}_{endTime}.m4a`。ダウンロード済みの判定は局IDと開始・終了時刻の一致 (`isProgramDownloaded`)。
6. 進捗の状態 (`downloadStates`) には完了・失敗・中止も残るため、UI の「ダウンロード中」判定は `MainViewModel.ongoingDownloadStates` (進行中のみ) を使う。

タイムフリーHLS URLの形式 (`AirkastClient.generateHlsUrl`):

```
https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8
  ?station_id={station_id}&l={duration_sec}
  &ft={YYYYMMDDHHmmss}&to={YYYYMMDDHHmmss}
  &start_at={ft}&end_at={to}
  &lsid={uuid(ハイフンなし)}&type=b

Headers: X-Radiko-Authtoken: {token}
```

### 再生

`MainViewModel` は Media3 `MediaController` で `PlayerService` に接続し、カスタムコマンド (`PlayerCommands`) で再生を指示する。AAC-ADTS では ExoPlayer が長さを返さないことがあるため、番組の開始・終了時刻から算出した長さを `GET_DURATION` で取得して UI に反映する。

## 変更時の注意

- `MainViewModel` ↔ `PlayerService` のカスタムコマンド名・Bundleキーは `service/PlayerCommands.kt` に集約している。文字列を直書きしない。
- UIを変更するときは `MainActivity.kt` ではなく `ui/` 以下を編集する。ダウンロードボタンは `ProgramDownloadState` だけから描画する。
- `MainViewModel` の `dateList` / `ongoingDownloadStates` / `downloadedFiles` / `skipBackward` / `skipForward` / `setPlaybackSpeed` / `userMessage` は UI が依存している。名前を変えるとビルドが壊れる。
- お気に入り番組の自動ダウンロードは `favorites/` パッケージ。呼び出しは `AirkastApplication.onCreate` → `FavoriteSyncScheduler` → `FavoriteSyncWorker` → `FavoriteSync` → `FavoriteMatcher` / `FavoriteRepository` → `DownloadRunner`。設計と計画は `docs/superpowers/specs/` と `docs/superpowers/plans/`。
- UI は実装済み。お気に入りタブ(`ui/screens/FavoritesScreen.kt`、行は `ui/components/FavoriteRow.kt`)と、番組を探すの各行の★(`ProgramRow` の `onToggleFavorite`)で追加・解除・自動DLの切り替えができる。タブは お気に入り → 番組を探す → ダウンロード の 3 つ(番号は `ui/Tabs.kt`)。起動時と通知タップ(`EXTRA_OPEN_TAB`、`MainActivity` の `onCreate` と `onNewIntent`)ではお気に入りタブが開く。
- 手動ダウンロードと Worker の書き込み衝突は `DownloadRegistry`(プロセス全体の「取得中」レジストリ。`tryClaim`/`release`)で防ぐ。`MainViewModel.onDownloadProgram` と `HlsDownloadService`、`FavoriteSync` がこれを使い、お気に入り画面の「自動取得中」表示も `DownloadRegistry.claimed` を読む。
- `FavoritesViewModel` は★・自動DLの変更のたびに `FavoriteSyncControl.update` で定期同期のスケジュールを更新し、自動DL が ON になる操作(追加・スイッチ ON)では `syncNow` で 1 回だけ即時同期する。最新回の取得は `refresh`(タブを開くとき・アプリ復帰時に強制更新)。
- 通知権限(Android 13 以降の `POST_NOTIFICATIONS`)は、自動DL が ON になる操作の直前に `rememberNotificationPermissionGate` で要求する。拒否されても登録と自動DL は有効で、スナックバーから通知設定へ誘導する。
- 未確認・残る制約: 定期同期(WorkManager)の実機での長期動作とバックグラウンド実行は未確認で、手動確認が必要。Worker は `setForeground` を試みるが失敗しうるので昇格に頼らず、通常の実行枠に収まる範囲で動くため、1 回の実行で取り切れない場合は次回に持ち越される。お気に入りタブの「更新」は取得の完了を待たずにインジケータが戻る。
- Worker は Android 12 以降ではフォアグラウンド昇格(`setForeground`)が失敗しうるため、試みるだけで昇格に頼らず、WorkManager の実行枠(約10分)に収まる範囲で処理する。Worker は UI と別の `AirkastClient` を使う。

## 既知の課題

- お気に入り: 毎日放送の番組を★にすると、最初の同期で直近7日分(最大7回分)をまとめてダウンロードする。
- お気に入り: 最新回が直近7日間の範囲より古い番組は「最新回なし」になる(範囲外の回はダウンロードに失敗する)。
- お気に入り: タブのプルして更新は取得の完了を待たないため、読み込み中の表示が出ない。起動・タブを開くたびに、局ごとに8日分の番組表を取得し直す。
- 認証トークンの有効期限切れ時の再認証は未実装。
- 複数ダウンロードの優先度管理は未実装 (同時実行数の上限のみ)。
- `HlsDownloadService` / `PlayerService` は、切り出した純粋なロジック(状態の整理・通知文言・エラーメッセージ・再生位置の保存条件など)と、Robolectric による生成・破棄の確認までは単体テスト済み。実際のダウンロード実行・再生・通知まで含む Service 全体の動作は未テスト。

## radiko側の仕様変更への対応

- **認証方式の変更:** PCブラウザ版のJSを解析して新しい鍵・手順を特定する。必要に応じて MITM Proxy で通信をキャプチャする。
- **DRM強化:** Widevine 等が導入された場合、個人アプリでの対応は困難。
- **IP制限の強化:** アプリ側での対策は限定的 (VPN等の利用はユーザーの責任)。
