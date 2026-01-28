# Airkast アプリ開発ガイド

このドキュメントは、Radikoダウンローダーアプリ「Airkast」の開発者が、プロジェクトの全体像を把握し、効率的に開発を進めるためのガイドです。

---

## 1. プロジェクト概要と目標

radikoから過去番組（タイムフリー）をダウンロードできる、広告なしの個人利用向けAndroidアプリを開発することを目標としています。

### 主な技術的発見
- radikoの認証は、2段階の認証フロー（`auth1`, `auth2`）で行われる。
- **PC版（html5）認証**を採用。JavaScriptに公開されているキー（`bcd151073c03b352e1ef2fd66c32209da9ca0afa`）を使用するため、外部ファイルへの依存がない。
- PC版認証はIPアドレスで位置を判定するため、日本国内からのアクセスが必要。

### 実装済みの主な機能
- **UI:** Jetpack ComposeによるUI。ボトムナビゲーション（Spotifyスタイル）で「番組を探す」と「ダウンロード」を切り替え。ダークモード対応（システム設定に追従）。
- **認証:** アプリ起動後にPC版認証を実行。認証トークンはAirkastClient内部で管理。IPアドレスからエリアを自動判定。エリア手動変更機能あり。
- **番組表:**
    - エリア内の放送局を自動で取得し、選択可能。
    - 過去7日間の日付を選択可能。
    - 時間帯フィルター（早朝/昼/夜/深夜）で絞り込み。
- **ダウンロード (バックグラウンド対応):**
    - 過去の番組にのみ「ダウンロード」ボタンを表示。
    - `HlsDownloadService` (Foreground Service) により、バックグラウンドでのダウンロード継続。
    - HLSセグメントをダウンロード→復号→M4Aにmuxing。
    - ダウンロード進捗はアプリの通知として表示。
- **再生 (バックグラウンド対応):**
    - ダウンロード済みファイルは「ダウンロード」タブに一覧表示。
    - `PlayerService` (MediaLibraryService) + ExoPlayerでバックグラウンド再生。
    - 倍速再生対応（0.5x〜2.0x）。
    - **5段階スキップ:** 30秒戻り / 15秒戻り / 1分送り / 2分送り
    - **再生位置の自動保存・復元**
- **削除:**
    - 「削除」ボタンでファイルを削除。

## 2. 開発環境のセットアップ

本プロジェクトを開発・実行するためには、以下の環境が必要です。

- **Android Studio:** 最新版のインストールを推奨します。
- **Java Development Kit (JDK):** Version 17以上
- **Android SDK:** API Level 34 (Android 14) 以上
    - `minSdk = 26`
    - `targetSdk = 34`

### 依存関係
`app/build.gradle.kts` にて管理されています。主要なライブラリは以下の通りです。
- **UI:** Jetpack Compose, Material3
- **Networking:** Ktor (ktor-client-android, ktor-client-content-negotiation, ktor-serialization-kotlinx-json)
- **Concurrency:** Kotlin Coroutines
- **Architecture:** AndroidX ViewModel, Lifecycle
- **Media:** AndroidX Media3 (media3-exoplayer, media3-session), AndroidX Media (MediaBrowserCompat, MediaControllerCompat)

## 3. プロジェクトのビルドと実行

### ビルド
Android Studioを使用する場合、GUIから「Build」メニューのオプションを選択します。
コマンドラインからビルドする場合は、プロジェクトのルートディレクトリで以下のGradleコマンドを実行します。

```bash
cd airkast_app
./gradlew assembleDebug
```

### 実行
Android Studioを使用し、エミュレータまたは実機にデプロイして実行します。

## 4. 主要なアーキテクチャとコンポーネント

本アプリは、モダンなAndroid開発のベストプラクティスに従い、MVVM (Model-View-ViewModel) パターンを採用しています。

- **`MainActivity.kt` (View):** Jetpack Composeで構築されたUIのエントリポイント。ユーザー操作を受け取り、`MainViewModel` にイベントを伝達します。
- **`MainViewModel.kt` (ViewModel):** UIの状態を管理し、ビジネスロジックをカプセル化します。`AirkastClient` やサービス (`HlsDownloadService`, `PlayerService`) と連携し、データの取得や操作を行います。
- **`AirkastApplication.kt` (Application):** アプリケーションのライフサイクル全体で共有されるシングルトンオブジェクト (`AirkastClient` など) の初期化と管理を行います。
- **`AirkastClient.kt` (Model/Data Source):** Radiko APIとの通信を担当するコアコンポーネントです。認証、番組情報の取得、ダウンロードURLの解決などを行います。
- **`HlsDownloadService.kt` (Service):** バックグラウンドでHLSストリームのダウンロードを管理するForeground Serviceです。アプリが閉じられてもダウンロードを継続します。
- **`PlayerService.kt` (Service):** `MediaLibraryService` として実装されており、`ExoPlayer` を使用して音声コンテンツの再生を行います。OSのメディアコントロールとの連携を提供し、バックグラウンド再生を可能にします。
- **`PlaybackPositionManager.kt`:** 再生位置（シーク位置）の永続化を担当します。SharedPreferencesを使用して、番組ごとの再生位置を保存・復元します。
- **`HlsDownloader.kt`:** HLSストリームの具体的なダウンロードロジックを実装します。`HlsDownloadService` から利用されます。

## 5. ディレクトリ構成とファイルの役割

`app/src/main/java/com/example/airkast` 以下の主要なディレクトリとファイルは以下の役割を持っています。

```
airkast_app/app/src/main/java/com/example/airkast/
├───service/
│   ├───HlsDownloadService.kt  # HLSダウンロード処理をバックグラウンドで行うForeground Service
│   └───PlayerService.kt     # 音声再生をバックグラウンドで行うMediaLibraryService (ExoPlayer利用)
├───ApiException.kt          # API関連のエラーをラップするデータクラス
├───HlsDownloader.kt         # HLSダウンロードのコアロジックを実装
├───MainActivity.kt          # アプリのメインアクティビティ (Jetpack Compose UIのホスト)
├───MainViewModel.kt         # MainActivityとUIの状態管理、ビジネスロジックの連携
├───PlaybackPositionManager.kt # 再生位置の保存・復元管理
├───Theme.kt                 # アプリのテーマ定義
├───AirkastApplication.kt  # アプリケーションクラス。依存性注入 (AirkastClientのシングルトン管理)
├───AirkastClient.kt       # Radiko APIクライアント。PC版認証、番組情報取得、ストリームURL解決
├───AirkastProgram.kt      # Radiko番組情報を表すデータクラス
├───AirkastStation.kt      # Radiko放送局情報を表すデータクラス
└───UiState.kt               # UIの状態を表現するための汎用的なデータクラス
```

## 6. Radiko認証フロー（PC版）

PC版Radikoの認証は2段階で行われます。

1. **`auth1`**: アプリ起動時に `AirkastClient` がRadikoサーバーに認証リクエストを送信します。
   - レスポンスヘッダーから `X-Radiko-Authtoken`、`X-Radiko-KeyLength`、`X-Radiko-KeyOffset` を取得。
   
2. **Partial Key生成**: 
   - 公開キー `bcd151073c03b352e1ef2fd66c32209da9ca0afa` の `offset` から `length` バイトを切り出し、Base64エンコード。
   
3. **`auth2`**: `auth1` で取得したトークンと部分キーを使用して認証を完了。
   - レスポンスボディから `JP13,東京都,tokyo` 形式でエリア情報を取得。
   - IPアドレスで位置を判定するため、日本国内からのアクセスが必要。

認証トークンは `AirkastClient` 内部でキャッシュされ、有効期限内は再利用されます。

## 7. 番組表取得機能

1. ユーザーが日付と放送局を選択すると、`MainViewModel` は `AirkastClient` を介してRadiko APIから指定された条件の番組表データを取得します。
2. `AirkastClient` はAPIからのXMLレスポンスを `AirkastProgram` オブジェクトのリストに変換し、`MainViewModel` に渡します。
3. `MainViewModel` は取得した番組表データをUIの状態として更新し、Jetpack Composeで表示されている番組表に反映します。

## 8. ダウンロード機能 (HlsDownloadService)

1. ユーザーが番組の「ダウンロード」ボタンをタップすると、`MainViewModel` は `HlsDownloadService` に対してダウンロード開始の指示を出します。
2. `HlsDownloadService` はForeground Serviceとして起動し、ダウンロード処理を開始します。
3. 内部的に `HlsDownloader` を利用してHLSストリーム (.m3u8) を解析し、AES暗号化されたセグメントを復号しながらダウンロードします。
4. **Muxing処理:** 復号済みのAACセグメントを `MediaMuxer` を使用してM4A形式に結合します。進捗は「ダウンロード中」と「変換中（Muxing）」の2段階で表示されます。
5. ダウンロード完了後、ファイルはM4A形式でローカルストレージに保存されます。

### タイムフリーHLS URL形式

```
https://tf-rpaa.smartstream.ne.jp/tf/playlist.m3u8
  ?station_id={station_id}
  &start_at={YYYYMMDDHHmmss}
  &end_at={YYYYMMDDHHmmss}
  &l=15
  &lsid={uuid}
  &type=b

Headers:
  X-Radiko-Authtoken: {token}
```

## 9. 再生機能 (PlayerService, ExoPlayer)

1. ユーザーがダウンロード済みの番組の「再生」ボタンをタップすると、`MainViewModel` は `PlayerService` に対して再生開始の指示を出します。
2. `PlayerService` は `MediaLibraryService` として起動し、内部で `ExoPlayer` インスタンスを生成して指定されたオーディオファイルの再生を開始します。
3. `MediaLibraryService` はAndroidのメディアフレームワークと連携し、OSの通知、ロック画面、Bluetoothデバイスなどからのメディアコントロール操作を可能にします。
4. 再生中の状態（再生/一時停止、再生位置、バッファリングなど）は、OSのメディアコントロールとアプリのUIにリアルタイムで同期されます。

## 10. 既存の課題と今後の改善点

- **認証有効期限の考慮:** Radikoの認証トークンには有効期限がある。トークンが期限切れになった場合の再認証ロジックを検討する必要がある。
- **ダウンロードファイル名の重複:** `program.id_program.title.m4a`の形式でファイル名を生成しているが、ファイル名が衝突する可能性がある。より堅牢なファイル命名規則を検討すべき。
- **テストカバレッジ:** 大きな機能に対するテスト（特に`HlsDownloadService`と`PlayerService`の動作検証）の追加が必要。
- **UIのさらなる改善:** ダウンロードと再生の進捗をより視覚的に分かりやすく表示するUI（例: プログレスバー、再生時間表示など）。
- **複数ダウンロードの管理:** 現在は複数のダウンロードをキューイングできるが、同時実行数や優先度管理などの高度な制御は未実装。

## 11. Radiko対策への対応

将来的にRadiko側が非公式アプリへの対策（API変更、キー変更、仕様変更など）を行った場合の対応策については、プロジェクトルートの `GEMINI.md` の「Radiko側の対策への対抗策」セクションを参照してください。