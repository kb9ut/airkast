# Airkast

radikoのタイムフリー番組をダウンロードし、倍速再生・秒送りで聴くための個人利用向け Android アプリ (Kotlin / Jetpack Compose) です。

## クイックスタート

```bash
# JDK 17 (Android Studio 同梱のJBRでも可)
cd airkast_app
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

radikoの認証はIPアドレスで位置を判定するため、日本国内のネットワークから利用してください。

## ドキュメント

- [CLAUDE.md](CLAUDE.md) — 仕様、アーキテクチャ、認証・ダウンロード・再生のフロー、開発上の注意、既知の課題
- [airkast_app/DESIGN.md](airkast_app/DESIGN.md) — UIデザインの方針

## 免責

個人利用を目的としたプロジェクトです。radikoの利用規約に従い、取得した音声を再配布しないでください。
