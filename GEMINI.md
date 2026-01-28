# Airkast - Radikoダウンローダーアプリ

## 概要
radikoから過去番組をダウンロードし、倍速再生・秒送りが可能なAndroidアプリ。

---

## 実装済み機能

### UI
- Jetpack Compose + Material3
- **ボトムナビゲーション**（Spotifyスタイル）：「番組を探す」「ダウンロード」タブ
- **ダークモード対応**（システム設定に自動追従）
- 日本語UI

### 認証
- PC版radiko認証（公開キー `bcd151073c03b352e1ef2fd66c32209da9ca0afa` 使用）
- IPアドレスからエリアを自動判定
- **エリア手動変更機能**（その他メニューから選択可能）

### 番組表
- エリア内の放送局を取得（12エリア対応）
- 過去7日間の日付選択
- 時間帯フィルター（早朝/昼/夜/深夜）

### ダウンロード
- HlsDownloadService (Foreground Service)
- バックグラウンドダウンロード対応
- 進捗を通知とUIでリアルタイム表示
- **M4A形式で保存**（muxing処理）

### 再生
- PlayerService (MediaLibraryService) + ExoPlayer
- バックグラウンド再生
- 倍速再生（0.5x〜2.0x）
- **5段階スキップ**：30秒戻り / 15秒戻り / 1分送り / 2分送り
- 再生位置の自動保存・復元

---

## HLSダウンロード＆Muxing処理

radikoのタイムフリー音声はHLS形式（AES-128暗号化）で配信されます。

```
ダウンロード処理フロー:
1. playlist.m3u8 を取得
2. 暗号化キーURLを抽出し、AESキーを取得
3. 各セグメント (.aac) をダウンロード・復号
4. 復号済みAACセグメントを一時ファイルに保存
5. MediaMuxer で M4A にmuxing
6. 一時ファイルを削除
```

### 重要ポイント
- セグメントは暗号化されているため、キー取得→復号が必須
- muxing処理により複数AACセグメントを単一M4Aファイルに結合
- 進捗は「ダウンロード中」と「変換中（Muxing）」の2段階で表示

---

## 認証フロー（PC版）

```
1. GET /v2/api/auth1
   → X-Radiko-Authtoken, KeyLength, KeyOffset を取得

2. Partial Key生成
   partial_key = base64(FULL_KEY[offset:offset+length])

3. GET /v2/api/auth2
   → エリアID (例: "JP13,東京都,tokyo") を取得
   ※ radikoサーバーがIPアドレスで位置を判定
```

---

## プロジェクト構造

```
airkast_app/app/src/main/java/com/example/airkast/
├── MainActivity.kt          # UI (Jetpack Compose)
├── MainViewModel.kt         # 状態管理 (MVVM)
├── AirkastClient.kt         # Radiko APIクライアント
├── HlsDownloader.kt         # HLSダウンロード＆復号ロジック
├── PlaybackPositionManager.kt # 再生位置永続化
├── Theme.kt                 # ダークモード対応テーマ
└── service/
    ├── HlsDownloadService.kt  # ダウンロードサービス
    └── PlayerService.kt       # 再生サービス
```

---

## 技術スタック

| カテゴリ | 技術 |
|---------|------|
| 言語 | Kotlin |
| UI | Jetpack Compose, Material3 |
| アーキテクチャ | MVVM |
| ネットワーク | Ktor Client |
| メディア再生 | AndroidX Media3 (ExoPlayer) |
| サービス | Foreground Service, MediaLibraryService |

---

## 今後の改善点

- [ ] 認証トークン期限切れ時の再認証
- [ ] テストカバレッジ向上
- [ ] 複数ダウンロードの優先度管理

---

## Radiko対策への対抗策

### 認証方式変更時
- PCブラウザ版JS / APKを解析して新キーを特定
- MITM Proxyで通信キャプチャ

### DRM強化時
- Widevine等が導入された場合、個人アプリでの対応は困難
- 現状のHLS+AES-128なら通信解析で対応可能

### IP制限強化時
- アプリ側での対策は限定的（VPN等はユーザー責任）

---

## リポジトリ

```
git@github.com:kb9ut/airkast.git
```