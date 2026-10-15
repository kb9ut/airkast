# Airkast

radikoのタイムフリー番組をダウンロードし、倍速再生・秒送りで聴くための個人利用向けツールです。

| ディレクトリ | 内容 | 状態 |
|--------------|------|------|
| [`airkast_app/`](airkast_app) | Android アプリ (Kotlin / Jetpack Compose) | メイン。実際に使用している |
| [`airkast_web/`](airkast_web) | Web版 (Next.js、Vercel にデプロイ) | 補助 |
| [`airkast_ios/`](airkast_ios) | iOS アプリ (Swift) | 現在は未使用・未メンテナンス |

## クイックスタート

```bash
# Android (JDK 17。Android Studio 同梱のJBRでも可)
cd airkast_app
./gradlew assembleDebug
./gradlew testDebugUnitTest

# Web
cd airkast_web
npm ci
npm run dev
```

radikoの認証はIPアドレスで位置を判定するため、日本国内のネットワークから利用してください。

## ドキュメント

- [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) — 仕様、アーキテクチャ、認証・ダウンロード・再生のフロー、既知の課題
- [airkast_web/README.md](airkast_web/README.md) — Web版のAPIと構成
- [GEMINI.md](GEMINI.md) — AIエージェント向けの作業メモ

## 免責

個人利用を目的としたプロジェクトです。radikoの利用規約に従い、取得した音声を再配布しないでください。
