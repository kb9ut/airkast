# Airkast - AIエージェント向けコンテキスト

radikoのタイムフリー番組をダウンロードし、倍速再生・秒送りで聴くための個人利用ツール。
詳細な仕様・構成・認証/ダウンロードのフローは **[DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)** を参照してください (このファイルには重複して書かない)。

## 作業上の前提

- 実際に使われているクライアントは **Androidアプリ (`airkast_app/`)** のみ。Web版 (`airkast_web/`) は補助、`airkast_ios/` は未使用・未メンテナンス (削除しない)。
- ドキュメントと UI の文言は日本語。コミットメッセージは conventional style (`feat:` / `fix:` / `refactor:` / `docs:` / `chore:`)。
- 認証は radiko の **PC版 (html5)** 方式。日本国内IPからのアクセスが必要。

## よく使うコマンド

```bash
# Android (JDK 17、または Android Studio 同梱JBR。JDK 24 では Gradle 8.11 が動かない)
cd airkast_app
./gradlew assembleDebug
./gradlew testDebugUnitTest      # JUnit4。useJUnitPlatform() は有効にしない

# Web
cd airkast_web
npm ci
npm run lint && npx tsc --noEmit && npm run build
npm run dev
```

## 変更時の注意

- `MainViewModel` ↔ `PlayerService` のカスタムコマンド名・Bundleキーは `service/PlayerCommands.kt` に集約している。文字列を直書きしない。
- HLSセグメント (ADTS AAC) はそのまま結合して `MediaMuxer` で M4A 化している。コードにAES復号処理はない。
- radiko側の仕様変更時の対応方針は DEVELOPMENT_GUIDE.md の「radiko側の仕様変更への対応」を参照。
