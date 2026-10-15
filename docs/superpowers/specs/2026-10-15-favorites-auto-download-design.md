# お気に入り番組と自動ダウンロード 設計

作成日: 2026-10-15 / 対象: `airkast_app`(Android)のみ / 状態: レビュー待ち

## 1. 目的

普段聴く番組が決まっているユーザーが、毎回「局→日付→番組を探す→ダウンロード→再生」と辿らずに済むようにする。

- **高速アクセス:** お気に入り番組の最新回へ1タップで到達し、取得済みなら再生、未取得ならダウンロードできる。
- **聴き逃しゼロ:** お気に入り番組の新しい回は、放送終了後に Wi-Fi 接続時のみ自動でダウンロードされ、完了が通知される。

### 成功基準
1. 番組カードの★で、放送局と番組名の組をお気に入りに登録・解除できる。
2. 「お気に入り」画面に番組ごとの最新回が並び、状態(取得済み / 未取得 / ダウンロード中)に応じた操作ができる。
3. 自動ダウンロードをONにした番組の新しい回が、アプリを開かなくても取得される(Wi-Fi 等の非従量課金回線のときのみ)。
4. 取得済みの回を重複してダウンロードしない。失敗を無限に再試行しない。

### 非目標(YAGNI)
- 古い録音の自動削除、放送時刻を指定した予約、Wi-Fi 以外での自動取得、iOS / Web 対応。

## 2. 全体構成

```
UI (Compose)  ──▶ FavoritesViewModel ──▶ FavoriteRepository (SharedPreferences + タブ区切りテキスト)
                                         │
                                         ▼
FavoriteSyncWorker (WorkManager, 約6時間ごと)
   ├─ AirkastClient.getProgramGuide (お気に入りの局 × 直近7日)
   ├─ FavoriteMatcher.findNewEpisodes (純粋関数)
   └─ DownloadRunner (HlsDownloadService から切り出した共通のダウンロード本体)
```

| ユニット | 役割 | 依存 |
|---|---|---|
| `Favorite` / `FavoriteRepository` | お気に入りの保存・取得・自動DL設定・失敗回数の保持 | SharedPreferences |
| `FavoriteMatcher` | 番組表とお気に入りから「新しい回」を判定する純粋関数 | なし |
| `DownloadRunner` | 1番組をダウンロードして m4a にするコア処理(進捗コールバック付き) | `AirkastClient`, `HlsDownloader` |
| `HlsDownloadService` | 既存。`DownloadRunner` を呼ぶ薄いフォアグラウンドサービスにする | `DownloadRunner` |
| `FavoriteSyncWorker` | 定期実行。新しい回を見つけ、`DownloadRunner` で順次ダウンロードし、通知する(`setForeground`(dataSync) は試みるが、失敗しても続行する) | 上記すべて, WorkManager |
| `FavoritesViewModel` と画面 | ★操作、お気に入り一覧、自動DLスイッチ | `FavoriteRepository`, 既存 `MainViewModel` |

## 3. データモデル

```kotlin
data class Favorite(
    val stationId: String,
    val title: String,            // 表示用の元の番組名
    val autoDownload: Boolean = true,
) {
    val key: String               // 正規化済み番組名(照合用)。title から計算する
}
```

失敗回数は `Favorite` に持たせず、`FavoriteRepository` が「回のキー(`episodeKey` = `局ID:開始時刻`)→回数」として別に保存する。

- **お気に入りの同一性:** `stationId + key`。`key` は番組名の前後空白除去、連続空白の圧縮、全角英数記号の半角化(NFKC)で作る。曜日・時刻は持たない。
- **保存:** SharedPreferences にタブ区切りの行テキストで保存する(JSON は kotlinx-serialization のプラグインが無く、JVM テストで `org.json` が使えないため採用しない)。件数は数十を想定し、Room は使わない。失敗回数は `Favorite` ではなく `FavoriteRepository` が「回のキー(`局ID:開始時刻`)→回数」として別に保存する。
- 失敗回数は回のキー(`局ID:開始時刻`)単位で数え、上限は 3 回(`MAX_ATTEMPTS`)。上限に達した回は自動では再試行しない(画面から手動で再開できる)。

## 4. 新しい回の判定(`FavoriteMatcher`)

入力: お気に入り一覧、局ごとの番組表(直近7日分)、現在時刻、取得済みファイルの一覧。
次の条件をすべて満たす番組を返す。

1. `autoDownload` が ON のお気に入りと、`stationId` が一致し正規化した番組名が一致する。
2. 放送終了時刻 ≤ 現在時刻 − 余裕(`END_MARGIN` = 5分。タイムフリーの反映待ち)。
3. 放送開始が現在時刻から7日以内(タイムフリー期限内)。
4. `isProgramDownloaded`(局ID・開始・終了が一致)で未取得。
5. 失敗回数が `MAX_ATTEMPTS` 未満。

返り値は放送開始の古い順。現在時刻は引数で受け取り、テストで固定できるようにする。

## 5. 自動ダウンロード(`FavoriteSyncWorker`)

- `PeriodicWorkRequest` 6 時間間隔、制約: `NetworkType.UNMETERED`、`requiresBatteryNotLow`。バックオフは指数。ユニーク名で重複登録を防ぎ、自動DLが ON のお気に入りが1件でもあるときだけ登録する(0件になったら解除)。
- 処理: 局ごとに直近7日の番組表を取得 → `FavoriteMatcher` → 各番組を `DownloadRunner` で順次(同時実行は1)ダウンロード。成功は通知。試行はダウンロード開始の直前に失敗回数へ加算し(中断された試行も `MAX_ATTEMPTS` に数える。古い順の先頭にある長い回が毎回後続をブロックしないため)、成功したらクリアする。ダウンロードが正常終了しても出力ファイルが無い/空の場合は失敗として扱う(`requireOutputFile`。手動ダウンロードも同様にエラー表示になる)。
- **フォアグラウンド実行:** Worker は `setForeground(ForegroundInfo, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` を試みる。Android 12 以降のバックグラウンド起動では昇格に失敗しうるため、失敗した場合は通常の WorkManager の実行枠(約10分)の中でそのまま続行する。枠で打ち切られた場合はキャンセルとして扱い、部分ファイルを削除して次の周期に回す(その試行も失敗回数に数える)。進捗・完了は通常の通知で出す。
- 認証(auth1 / auth2)は Worker 実行ごとに行う。取得できない(エリア外など)場合は、その回を失敗として数えず、次の周期に回す。
- Worker は画面のダウンロード状態(`downloadStates`)を更新しない。進捗は通知のみで、取得済みの反映は画面復帰時のファイル再走査に任せる。手動ダウンロード中の回を重複して始めないよう、`downloadStates` を読んで除外する。

## 6. 画面

- **★ボタン:** 番組カードに追加する。押すとお気に入りの登録/解除。
- **お気に入り画面:** 起動時に最初に開く。お気に入りごとに1行で、番組名、局名、最新回の日時、状態に応じたボタン(取得済み→再生 / 未取得→ダウンロード / 進行中→進捗)、自動DLのスイッチ。最新回の特定は、直近7日の番組表から同じ番組名の最新の放送済みの回を選ぶ。
- 実装の順序は、現在進行中のデザイン刷新(`MainActivity.kt` の再構成)が main に入った後とする。衝突を避けるため。

## 7. エラー処理と制約

- 番組表の取得失敗、ネットワーク断: Worker は `Result.retry()`(バックオフ)。
- 空き容量不足やファイル書き込み失敗: その回を失敗として数える。
- 注意(Android 15): dataSync のフォアグラウンド実行には1日あたりの時間上限がある。1回の実行は新しい回の件数(通常は数件)に限られるため問題ない想定だが、上限に達したら次の周期に回す。
- 放送中の回は取得しない(条件2)。番組名の変更・特番はお気に入りに一致しない(仕様)。

## 8. テスト

- `FavoriteMatcher`: 名前正規化、7日の境界、放送終了前後、重複取得、失敗回数上限、`autoDownload` OFF を、時刻を固定した単体テストで網羅する。
- `FavoriteRepository`: テキストの保存/復元、同一性(局+正規化名)での重複排除。
- `DownloadRunner` への切り出しは、既存の `HlsDownloadServiceTest` / `HlsDownloaderTest` を通すこと(挙動不変)。
- `FavoriteSync`(番組表取得→判定→ダウンロードの手順)を、番組表の取得元とダウンローダを差し替えられる形で単体テストする。`FavoriteSyncWorker` は薄い接続のみとし、`work-testing` は使わない。実際の radiko 接続と実機のバックグラウンド動作は手動確認とする。

## 9. 実装の段階

1. `DownloadRunner` の切り出し(挙動不変のリファクタ)。
2. `Favorite` / `FavoriteRepository` / `FavoriteMatcher` とそのテスト。
3. `FavoriteSyncWorker` と WorkManager 依存の追加。
4. UI(★、お気に入り画面、自動DLスイッチ)。デザイン刷新の後。
