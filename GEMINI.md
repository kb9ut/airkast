# Airkast - Radikoダウンローダーアプリ

## 概要
radikoから過去番組をダウンロードし、倍速再生・秒送りが可能なAndroidアプリ。

## 最終目標
- radikoのタイムフリー番組をダウンロード
- ダウンロードした番組を倍速再生（0.5x〜2.0x）
- 秒送り・秒戻し機能
- バックグラウンド再生対応

---

## 認証方式

### PC版（html5）認証を採用

radikoのPC版認証はJavaScriptに公開されているキーを使用するため、外部ファイルへの依存なく認証が可能です。

```
認証フロー:
1. GET /v2/api/auth1
   → X-Radiko-Authtoken, KeyLength, KeyOffset を取得

2. Partial Key生成
   PC_FULL_KEY = "bcd151073c03b352e1ef2fd66c32209da9ca0afa"
   partial_key = base64(PC_FULL_KEY[offset:offset+length])

3. GET /v2/api/auth2
   → エリアID (例: "JP13,東京都,tokyo") を取得
   → IPアドレスで位置を判定
```

> **注意**: PC版認証はIPアドレスで位置を判定するため、日本国内からのアクセスが必要です。

### タイムフリー (HLS) URL生成
- 2026年1月現在、以下のエンドポイントを使用:
  `https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8`
- 必須パラメータ: `station_id`, `start_at`, `end_at`, `ft`, `to`, `l=15`, **`lsid` (Session ID)**
- `lsid` は32文字のランダムなHEX文字列 (UUID等から生成)。


---

## 実装済み機能

### UI
- Jetpack ComposeによるUI
- 「番組を探す」と「ダウンロード」の2つのタブ
- 日本語UI

### 認証
- PC版radiko認証（公開キー使用）
- 認証トークンはAirkastClient内部で管理
- IPアドレスからエリアを自動判定

### 番組表
- IPから判定されたエリア内の放送局を自動取得
- 過去7日間の日付を選択可能
- 選択された放送局・日付の番組表を表示

### ダウンロード（バックグラウンド対応）
- 過去番組のみダウンロード可能
- HlsDownloadService (Foreground Service) によるバックグラウンドダウンロード
- ダウンロード進捗を通知で表示
- 「ダウンロード」タブでリアルタイム進捗確認

### 再生（バックグラウンド対応）
- PlayerService (MediaLibraryService) + ExoPlayer
- アプリを閉じても再生継続
- OSのメディアコントロールから制御可能
- 倍速再生対応
- 秒送り・秒戻し機能
- **再生位置の自動保存・復元**（アプリを閉じても途中から再開）

### 削除
- ダウンロードしたファイルを削除

---

## プロジェクト構造

```
airkast/
├── GEMINI.md              # このファイル（プロジェクトサマリー）
├── DEVELOPMENT_GUIDE.md   # 開発ガイド
├── radiko_8.3.10_APKPure.xapk  # 参考用radiko公式アプリ
└── airkast_app/         # Androidアプリ本体
    ├── app/
    │   └── src/main/java/com/example/airkast/
    │       ├── MainActivity.kt        # UIエントリポイント
    │       ├── MainViewModel.kt       # UI状態管理
    │       ├── AirkastApplication.kt # Applicationクラス
    │       ├── AirkastClient.kt     # Radiko APIクライアント
    │       ├── AirkastProgram.kt    # 番組データクラス
    │       ├── AirkastStation.kt    # 放送局データクラス
    │       ├── HlsDownloader.kt       # HLSダウンロードロジック
    │       ├── PlaybackPositionManager.kt # 再生位置管理
    │       ├── Theme.kt               # テーマ定義
    │       ├── ApiException.kt        # 例外定義
    │       ├── UiState.kt             # UI状態定義
    │       └── service/
    │           ├── HlsDownloadService.kt  # ダウンロードサービス
    │           └── PlayerService.kt       # 再生サービス
    ├── build.gradle.kts
    └── settings.gradle.kts
```


---

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose, Material3
- **アーキテクチャ**: MVVM
- **ネットワーク**: Ktor Client
- **非同期処理**: Kotlin Coroutines
- **メディア再生**: AndroidX Media3 (ExoPlayer)
- **サービス**: Foreground Service, MediaLibraryService

---

## 今後の改善点

- [ ] 認証トークン有効期限の管理（期限切れ時の再認証）
- [ ] ダウンロードファイル名の衝突対策
- [ ] テストカバレッジの向上
- [ ] 複数ダウンロードの優先度管理

---

## Radiko側の対策への対抗策（将来のリスク管理）

今後、Radiko側が非公式アプリへの対策を強化した場合の対応アプローチ案です。

### 1. 認証方式の変更 (Auth1/Auth2)
- **現状**: 固定の`Full Key`とオフセット計算によるPartial Key認証。
- **対策**:
  - キーが変更された場合: PCブラウザ版のJSソースコード、またはAndroid公式アプリ（APK）を解析して新しいキーを特定する。
  - プロトコル変更: Charles ProxyやMITM Proxyを使用して公式アプリの通信をキャプチャし、新しい認証フロー（ヘッダー、パラメータ順序など）を解析・模倣する。

### 2. User-Agent / ヘッダー検証の強化
- **現状**: 一般的なHTTPクライアントを使用。
- **対策**:
  - 公式アプリ（Android版）またはPCブラウザ（Chrome/Edge）と**完全に一致する**User-AgentおよびHTTPヘッダーを設定する。
  - TLS Fingerprinting対策が必要な場合は、OkHttpの低レベル設定や特殊なHTTPクライアントライブラリの利用を検討する。

### 3. リクエスト署名 (Signature) の導入
- **現状**: URLパラメータとヘッダーのみ。
- **対策**:
  - リクエストパラメータにハッシュ値や署名が必須となった場合、APKをデコンパイルし、署名生成ロジック（多くの場合JNI/Native層にある）を解析する。
  - 難易度が高い場合は、Fridaなどのフックツールを用いて署名生成関数を特定・検証する。

### 4. DRM (Digital Rights Management) の強化
- **現状**: HLS (m3u8) + AES-128暗号化（キーはサーバーから取得）。
- **対策**:
  - Google Widevine等のハードウェアレベルDRMが導入された場合、個人開発アプリでの対応は極めて困難になる（再生・保存が不可能になる可能性が高い）。
  - 標準的なHLS暗号化のキー取得ロジック変更程度であれば、通信解析でキーの取得URLを特定にて対応可能。

### 5. IPアドレス制限 / VPN検知
- **現状**: 日本国内IPのみ許可。
- **対策**:
  - アプリ側での技術的対策は限定的。ユーザー側で適切な日本国内IP（家庭用ISP等）を利用する必要がある。

---

## リポジトリ

```
git@github.com:kb9ut/airkast.git
```