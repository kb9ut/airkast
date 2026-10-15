# Airkast 開発ガイド

radikoのタイムフリー番組をダウンロード・再生する個人利用向けツール「Airkast」の開発者向けガイドです。
リポジトリ全体の概要は [README.md](README.md) を参照してください。

## 1. プロジェクト概要

| ディレクトリ | 内容 | 状態 |
|--------------|------|------|
| `airkast_app/` | Android アプリ (Kotlin / Jetpack Compose) | **メイン。実際に使用している唯一のクライアント** |
| `airkast_web/` | Next.js のWeb版 (radiko へのプロキシ API + UI)。Vercel にデプロイ | 補助的に保守 |
| `airkast_ios/` | iOS アプリ (Swift) | **現在は未使用・未メンテナンス** (削除はしていない) |

### 技術的な前提
- radikoの認証は2段階 (`auth1` → `auth2`)。**PC版 (html5) 認証**を使用し、JavaScriptに公開されている鍵 (`bcd151073c03b352e1ef2fd66c32209da9ca0afa`) から部分キーを生成する。外部ファイル (APK等) への依存はない。
- PC版認証はIPアドレスで位置を判定するため、**日本国内からのアクセスが必要**。
- Web版を Vercel で動かす場合、リージョンを東京 (`hnd1`, `airkast_web/vercel.json`) に固定している。

## 2. Androidアプリの機能

- **UI:** Jetpack Compose + Material3、ダークテーマ、日本語UI。ボトムナビは「その他」「番組を探す」「ダウンロード」。「その他」メニューからエリア変更・ダウンロード分割サイズ設定・ライセンス表示を行う。
- **認証・エリア:** 起動時にPC版認証を実行し、IPアドレスからエリアを自動判定。エリアは手動変更も可能 (`AreaSelectionDialog` に定義した12エリア)。
- **番組表:** エリア内の放送局を選択 → 過去7日間から日付を選択 → 時間帯フィルター (早朝 5-9 / 昼 9-17 / 夜 17-24 / 深夜 24-29) で絞り込み。アプリを開いたまま日付が変わった場合は日付リストを更新する。選択した放送局・時間帯は `SharedPreferences` に保存される。
- **ダウンロード:** 放送終了済みの番組のみ対象。`HlsDownloadService` (Foreground Service, `dataSync`) がバックグラウンドで実行し、通知に進捗を表示する。同時実行は最大3件。分割サイズ (15秒 / 60秒(推奨・既定) / 5分 / 一括) を選択可能。
- **再生:** ダウンロード済みファイルを `PlayerService` (MediaLibraryService + ExoPlayer) で再生。倍速 (0.5x〜2.0x)、スキップ (戻し: 60秒 / 30秒 / 15秒、送り: 60秒 / 2分 / 3分)、再生位置の自動保存・復元に対応。ストリーミング再生 (`MainViewModel.playStream` → `PlayerCommands.PLAY_STREAM`) の実装もある。
- **削除:** ダウンロードタブから個別に削除。

## 3. 開発環境とビルド

- **JDK 17** (`airkast_app/.tool-versions`)。Gradle 8.11.1 は JDK 24 では動作しない (確認済み)。Android Studio 同梱の JBR (JDK 21) でも可。
- **Android SDK:** `compileSdk = 36` / `targetSdk = 36` / `minSdk = 26` (`airkast_app/app/build.gradle.kts`)。`local.properties` に `sdk.dir` を設定する (Git管理外)。
- 主な依存: Compose BOM, Ktor Client (Android エンジン), Kotlin Coroutines, AndroidX Lifecycle/ViewModel, Media3 (ExoPlayer / HLS / Session), play-services-oss-licenses。

```bash
cd airkast_app
./gradlew assembleDebug        # APKビルド
./gradlew testDebugUnitTest    # 単体テスト (JUnit4 / Ktor MockEngine / MockK)
```

テストは JUnit4 で統一している。`useJUnitPlatform()` は有効にしないこと。

## 4. アーキテクチャ (Android)

MVVM構成です。`app/src/main/java/com/example/airkast/` 以下:

| ファイル | 役割 |
|----------|------|
| `MainActivity.kt` / `Theme.kt` | Compose UIとテーマ。ユーザー操作を `MainViewModel` に伝える |
| `MainViewModel.kt` | UI状態 (StateFlow) の管理。`AirkastClient`、`HlsDownloadService`、`PlayerService` (MediaController経由) と連携 |
| `UiState.kt` | `UiState<T>` (Idle/Loading/Success/Error) と `TimeFilter` |
| `AirkastApplication.kt` | `AirkastClient` と `PlaybackPositionManager` のシングルトン保持、`PlayerService` からのエラー通知用 SharedFlow |
| `AirkastClient.kt` | radiko API クライアント (認証、放送局、番組表、HLS URL生成)。認証トークン・エリアIDを保持 |
| `AirkastProgram.kt` / `AirkastStation.kt` | 番組・放送局のデータクラス (`yyyyMMddHHmmss` 形式の時刻を扱う補助も含む) |
| `ApiException.kt` | API系エラーの sealed クラス階層 |
| `HlsDownloader.kt` | HLSのプレイリスト解析・セグメント取得・M4Aへのmuxing |
| `PlaybackPositionManager.kt` | 番組ごとの再生位置を `SharedPreferences` に保存・復元 |
| `ParcelableCompat.kt` | API 33 前後の `getParcelable` 差異を吸収する拡張関数 |
| `service/HlsDownloadService.kt` | ダウンロード用 Foreground Service。状態を静的な `downloadStates` (StateFlow) で公開 |
| `service/PlayerService.kt` | 再生用 MediaLibraryService (ExoPlayer、位置の定期保存、通知) |
| `service/PlayerCommands.kt` | `MainViewModel` ↔ `PlayerService` 間のカスタムコマンド名と Bundle キー |

### 認証フロー (PC版)
1. `auth1`: `X-Radiko-Authtoken` / `X-Radiko-KeyLength` / `X-Radiko-KeyOffset` を取得。
2. 公開鍵の `offset` から `length` バイトを切り出して Base64 → 部分キー。
3. `auth2`: トークンと部分キーを送り、`JP13,東京都,tokyo` 形式のエリア情報を取得 (`OUT` は圏外)。
4. radiko.jp で得た Cookie を SmartStream (`*.smartstream.ne.jp`) のホストへ複製する (タイムフリー配信に必要)。

### 番組表取得
`MainViewModel` が `AirkastClient.getProgramGuide(stationId, yyyyMMdd)` を呼び、XMLを `AirkastProgram` のリストに変換して `UiState` に反映する。

### ダウンロード
1. 「ダウンロード」ボタン → `MainViewModel.onDownloadProgram` が `HlsDownloadService` を `startService` で起動。
2. サービスは番組の開始〜終了を分割サイズごとのチャンクに分け、チャンクごとにタイムフリーのプレイリストURLを生成して `HlsDownloader.downloadChunked` に渡す。
3. セグメント (ADTS AAC) をそのまま一時ファイル (`*.adts`) に追記する。**現在のコードにAES復号処理はない**。セグメントは重複排除 (クエリを除いたURLで判定) しながら結合される。
4. 全チャンク完了後、`MediaMuxer` で M4A に変換 (進捗は「ダウンロード中」「変換中」の2段階)。変換に失敗した場合は ADTS のままファイルとして残す。
5. 保存先は `getExternalFilesDir("downloads")`、ファイル名は `{stationId}_{title}_{startTime}_{endTime}.m4a`。

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

## 5. Web版 (`airkast_web`)

詳細は [airkast_web/README.md](airkast_web/README.md)。ブラウザからは radiko に直接アクセスできないため、Next.js の API Routes がプロキシとして認証・番組表・HLS中継を行う。ダウンロードはブラウザ側でセグメントを順次取得し、`.aac` として保存する (M4A変換は行わない)。

## 6. 既知の課題・今後の改善点

- 認証トークンの有効期限切れ時の再認証は未実装。
- 複数ダウンロードの優先度管理は未実装 (同時実行数の上限のみ)。
- `HlsDownloadService` / `PlayerService` の自動テストが不足している。
- 有効な音声トラックが見つからない場合、M4A変換は例外を出さず出力ファイルが生成されない。
- AndroidManifest に `FileProvider` の宣言があるが、コードからは使用していない。
- `airkast_app/LICENSE` はプレースホルダーのまま。

## 7. radiko側の仕様変更への対応

- **認証方式の変更:** PCブラウザ版のJSを解析して新しい鍵・手順を特定する。必要に応じて MITM Proxy で通信をキャプチャする。
- **DRM強化:** Widevine 等が導入された場合、個人アプリでの対応は困難。
- **IP制限の強化:** アプリ側での対策は限定的 (VPN等の利用はユーザーの責任)。
