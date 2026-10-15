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
./gradlew testDebugUnitTest    # 単体テスト (JUnit4 / Ktor MockEngine / MockK)
```

- **JDK 17** (`airkast_app/.tool-versions`)。Android Studio 同梱の JBR (JDK 21) でも可。**既定の JDK 24 では Gradle 8.11.1 が動かない**。
  例: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk`
- SDK: `compileSdk = 36` / `targetSdk = 36` / `minSdk = 26`。`local.properties` に `sdk.dir` を設定する (Git管理外)。
- テストは **JUnit4 で統一**。`useJUnitPlatform()` を有効にすると JUnit4 のテストが実行されなくなるので、有効にしない。
- 主な依存: Compose BOM `2025.06.00` (Material3 1.3.x)、Ktor Client (Android エンジン)、Kotlin Coroutines、AndroidX Lifecycle/ViewModel、Media3 (ExoPlayer / HLS / Session)、play-services-oss-licenses。

## 機能

- **UI:** Jetpack Compose + Material3。システム設定に追従するライト/ダーク、Android 12+ は Dynamic Color。ナビゲーションは「番組を探す」「ダウンロード」の2つ。トップバーのメニューからエリア変更・ダウンロード分割サイズ設定・ライセンス表示を行う。デザインの方針は [`airkast_app/DESIGN.md`](airkast_app/DESIGN.md)。
- **認証・エリア:** 起動時にPC版認証を実行し、IPアドレスからエリアを自動判定。手動変更も可能 (`AreaSelectionDialog` に定義した12エリア)。
- **番組表:** 放送局を選択 → 過去7日間から日付を選択 → 時間帯フィルター (早朝 5-9 / 昼 9-17 / 夜 17-24 / 深夜 24-29)。アプリを開いたまま日付が変わった場合は日付リストを更新する (`MainViewModel.refreshDateIfChanged`)。選択した放送局・時間帯は `SharedPreferences` に保存される。
- **ダウンロード:** 放送終了済みの番組のみ。`HlsDownloadService` (Foreground Service, `dataSync`) が実行し、通知に進捗を表示。同時実行は最大3件。分割サイズ (15秒 / 60秒(既定) / 5分 / 一括) を選べる。
- **再生:** `PlayerService` (MediaLibraryService + ExoPlayer) でダウンロード済みファイルを再生。倍速 (0.5x〜2.0x)、スキップ (戻し: 60秒 / 30秒 / 15秒、送り: 60秒 / 2分 / 3分)、再生位置の自動保存・復元。ストリーミング再生 (`MainViewModel.playStream` → `PlayerCommands.PLAY_STREAM`) の実装もある。
- **削除:** ダウンロード画面から個別に削除。

## アーキテクチャ

MVVM。`airkast_app/app/src/main/java/com/example/airkast/` 以下:

| パス | 役割 |
|------|------|
| `MainActivity.kt` | Activity のみ。`ui/AirkastApp.kt` を表示し、日付変更の監視 (`watchDateChanges`) を起動する |
| `ui/` | Compose UI。`theme/` (色・文字・形・モーション)、`components/` (ボタン・行・プレイヤー等)、`screens/` (番組を探す・ダウンロード)、`ProgramDownloadState.kt` (ダウンロードボタンの状態) |
| `MainViewModel.kt` | UI状態 (StateFlow) の管理。`AirkastClient`、`HlsDownloadService`、`PlayerService` (MediaController経由) と連携 |
| `UiState.kt` | `UiState<T>` (Idle/Loading/Success/Error) と `TimeFilter` |
| `AirkastApplication.kt` | `AirkastClient` と `PlaybackPositionManager` のシングルトン保持、`PlayerService` からのエラー通知用 SharedFlow |
| `AirkastClient.kt` | radiko API クライアント (認証、放送局、番組表、HLS URL生成)。認証トークン・エリアIDを保持 |
| `AirkastProgram.kt` / `AirkastStation.kt` | 番組・放送局のデータクラス (`yyyyMMddHHmmss` 形式の時刻の補助を含む) |
| `ApiException.kt` | API系エラーの sealed クラス階層 |
| `HlsDownloader.kt` | HLSのプレイリスト解析・セグメント取得・M4Aへのmuxing |
| `PlaybackPositionManager.kt` | 番組ごとの再生位置を `SharedPreferences` に保存・復元 |
| `ParcelableCompat.kt` | API 33 前後の `getParcelable` 差異を吸収する拡張関数 |
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
- 追加予定の機能(お気に入り番組の自動ダウンロード)の設計と実装計画は `docs/superpowers/specs/` と `docs/superpowers/plans/` にある。

## 既知の課題

- 認証トークンの有効期限切れ時の再認証は未実装。
- 複数ダウンロードの優先度管理は未実装 (同時実行数の上限のみ)。
- `HlsDownloadService` / `PlayerService` の自動テストが不足している。
- 有効な音声トラックが見つからない場合、M4A変換は例外を出さず出力ファイルが生成されないが、ダウンロードは完了扱いになる。
- AndroidManifest に `FileProvider` の宣言があるが、コードからは使用していない。
- `airkast_app/LICENSE` はプレースホルダーのまま。

## radiko側の仕様変更への対応

- **認証方式の変更:** PCブラウザ版のJSを解析して新しい鍵・手順を特定する。必要に応じて MITM Proxy で通信をキャプチャする。
- **DRM強化:** Widevine 等が導入された場合、個人アプリでの対応は困難。
- **IP制限の強化:** アプリ側での対策は限定的 (VPN等の利用はユーザーの責任)。
