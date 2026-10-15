# お気に入り画面(UI)と周辺の仕組み 設計

作成日: 2026-10-15 / 対象: `airkast_app`(Android)のみ / 状態: レビュー待ち
前提: [お気に入りと自動ダウンロードの設計](2026-10-15-favorites-auto-download-design.md)(データ層・判定・Worker は実装済み)の続き。この文書は、その §6 の画面と、実装時に持ち越した項目を扱う。

## 1. 目的

実装済みの自動ダウンロードを、ユーザーが実際に使えるようにする。

- お気に入り番組を番組表の★で登録・解除できる。
- 「お気に入り」タブで、番組ごとの最新回を 1 タップで再生・ダウンロードできる。
- 自動ダウンロードの ON/OFF を番組ごとに切り替えられ、切り替えがすぐ反映される。
- 手動ダウンロードと自動ダウンロードが、同じ回で衝突しない。
- 完了通知が Android 13 以降でも届き、タップでお気に入りタブが開く。

### 成功基準
1. ★の操作がお気に入り一覧に反映され、アプリを再起動しても残る。
2. お気に入りの行に最新の放送済みの回が出て、状態(未取得 / 取得中 / 変換中 / 取得済み / 失敗 / 自動取得中)に応じた操作ができる。
3. 同じ回を、手動と自動が同時に書き込むことがない。片方の失敗や中止で、もう片方のファイルが消えることがない。
4. 自動 DL を初めて ON にした時点で通知権限を求め、拒否されても自動 DL は動く。
5. お気に入りの追加・削除・自動 DL の切り替えのたびに、定期同期の登録/解除が更新される。自動 DL を ON にした直後に 1 回同期が走る。

### 非目的(YAGNI)
- 直近 7 日分の回の一覧・展開表示(各行は最新の 1 回だけ)。
- 失敗した回の再試行カウントを戻す専用ボタン(行から手動でダウンロードできれば足りる)。
- お気に入りの並べ替え、グループ化、バックアップ。

## 2. 構成

```
UI (Compose)
 ├─ FavoritesScreen (新・先頭タブ) ──┐
 ├─ FindProgramsScreen / ProgramRow (★を追加) ──┤
 └─ 通知権限ランチャ ──────────────┤
                                   ▼
                          FavoritesViewModel (新)
                           ├─ FavoriteRepository (実装済み)
                           ├─ latestEpisode(...) (新・純粋関数)
                           ├─ AirkastClient.getProgramGuide
                           ├─ FavoriteSyncScheduler.update / syncNow
                           └─ DownloadRegistry (新)

HlsDownloadService ─┐
FavoriteSync/Worker ─┴─ DownloadRegistry で回を予約してから書き込む
```

| ユニット | 役割 | 依存 |
|---|---|---|
| `DownloadRegistry`(新) | プロセス内で「いま書き込み中の回」を予約・解放し、予約中の集合を `StateFlow` で公開する | なし |
| `latestEpisode`(新) | 番組表と番組名・局から、放送終了から余裕時間を過ぎた最新の回を選ぶ純粋関数 | `normalizeProgramTitle` |
| `FavoritesViewModel`(新) | お気に入りの操作、最新回の取得、画面用の状態、スケジュール更新 | 上記すべて |
| `FavoritesScreen`(新) | お気に入り一覧のタブ | `FavoritesViewModel`, `MainViewModel` |
| `ProgramRow`(変更) | ★ボタンの追加 | なし |
| `HlsDownloadService`(変更) | 開始時に予約、終了時に解放 | `DownloadRegistry` |
| `FavoriteSync`(変更) | `isDownloading` を予約(`claim`/`release`)に置き換える | `DownloadRegistry` |
| `FavoriteSyncScheduler`(変更) | `syncNow` を追加 | WorkManager |
| `FavoriteSyncWorker`(変更) | 完了通知にタップ処理を付ける | |

## 3. 手動 DL と自動 DL の衝突対策(`DownloadRegistry`)

- キーは回の識別子 `episodeKey`(`局ID:開始時刻`)。ファイル名と同じ単位。
- API: `tryClaim(key): Boolean`(取れたら true、すでに予約済みなら false。同期済みでアトミック)、`release(key)`、`isClaimed(key)`、`claimed: StateFlow<Set<String>>`。
- **`HlsDownloadService.startDownload`:** 既存の重複チェックの後、ジョブを起動する前に `tryClaim`。取れなければログを出してスキップ。予約はジョブの `finally` の**最後**(ファイルの後始末が終わった後)で解放する。これで「中止して直ちに再開」したときの、古いジョブの後始末が新しいジョブのファイルを消す競合も閉じる。
- **`MainViewModel.onDownloadProgram`:** サービスを起動する前に `isClaimed` を見て、予約中なら「この回は自動ダウンロード中です」とスナックバーで知らせる(サービス側の `tryClaim` が正本)。
- **`FavoriteSync`:** コンストラクタの `isDownloading` を `claim: (AirkastProgram) -> Boolean = { true }` と `release: (AirkastProgram) -> Unit = {}` に置き換える。ループ内の既存のスキップ判定(保存済みファイル)の後、`claim` が false ならスキップ(失敗に数えない)。`recordFailure` → `download` → `finally` で `release`。Worker は `DownloadRegistry` を渡す。
- 実装済みの `FavoriteSyncWorker.isDownloadingManually`(`downloadStates` を読む重複ロジック)は不要になるので削除する。
- 画面: 予約中の回は「自動取得中」と表示する(進捗の割合は出さない。通知にはこれまでどおり出る)。

## 4. 画面

### お気に入りタブ
- ナビゲーションは 3 項目(お気に入り / 番組を探す / ダウンロード)。起動時の初期タブはお気に入り。通知のタップから開く場合もお気に入り。
- 行の内容: 番組名、局名、最新回の放送日時、状態に応じたボタン(取得済み→再生 / 未取得→ダウンロード / 取得中・変換中→進捗 / 失敗→再試行のダウンロード / 自動取得中→スピナー)、自動 DL のスイッチ、★(押すと解除)。状態の解決は既存の `resolveProgramDownloadState()` を使う。
- 最新回がまだ見つからない(番組表の取得中、7 日以内に放送がない、取得失敗)ときは、それぞれ「読み込み中」「直近 7 日に放送がありません」「番組表を取得できませんでした」を行に出す。
- 0 件のときは「番組表の★で追加できます」と案内する。
- 更新のタイミング: タブを開いたとき、アプリ復帰時、引っ張って更新。ダウンロード完了(ファイル再走査)でも状態が変わる。

### 最新回の特定
- `latestEpisode(programs, stationId, key, nowMs)`: 局と正規化した番組名が一致し、放送終了が (現在 − `END_MARGIN_MS`) 以前のうち、放送開始が最も新しい回。なければ null。判定条件は `FavoriteMatcher` と同じ時刻の定義を使い、重複させない(共通の小さな関数を `FavoriteMatcher` から切り出して両方で使う)。
- 取得: お気に入りの局ごとに `recentDates(nowMs)` の 8 日分(実装済み)。局ごとの結果は `FavoritesViewModel` が保持し、画面を開いている間は再利用する。

### ★ボタン
- `ProgramRow` に `isFavorite: Boolean` と `onToggleFavorite: () -> Unit` を追加。お気に入りの判定は `FavoritesViewModel` の集合(局ID + 正規化名)から。
- 追加時の自動 DL は既定で ON(実装済みの仕様)。このとき通知権限の確認(§5)とスケジュール更新(§6)が走る。

## 5. 通知権限

- Android 13 以降(`SDK_INT >= 33`)で、**自動 DL が OFF→ON になる操作**(★で追加、または行のスイッチを ON)のときに `POST_NOTIFICATIONS` を要求する。すでに許可済みなら何もしない。アプリ起動時には要求しない。
- 拒否された場合も、お気に入りの登録と自動 DL は有効のまま。スナックバーで「通知がオフです。自動ダウンロードは動きますが、完了は通知されません」と知らせ、「設定を開く」アクションで通知設定へ誘導する。
- 拒否が「今後表示しない」相当のときは、再要求せずに設定への導線だけを出す。
- 完了通知(`notifyDownloaded`)に `contentIntent` を付ける。`MainActivity` を開き、エクストラ `open_tab=favorites` でお気に入りタブを選ぶ。

## 6. スケジュール更新

- `FavoritesViewModel` が、お気に入りの追加・削除・自動 DL の切り替えのたびに、`FavoriteSyncScheduler.update(app, repository.hasAutoDownload())` を呼ぶ(変更の直後、保存の後)。
- `FavoriteSyncScheduler.syncNow(context)` を追加する: 同じ制約(非従量課金回線・バッテリー低下でない)の 1 回限りの `OneTimeWorkRequest`、ユニーク名 `favorite_sync_now`、`ExistingWorkPolicy.KEEP`。自動 DL が OFF→ON になったときと、お気に入りを自動 DL ON で追加したときに呼ぶ。
- 期待: 追加直後から、条件が整えば数分以内に最初の取得が走る。条件を満たさない(モバイル回線のみ等)ときは、満たされた時点で実行される。

## 7. エラー処理

- 番組表の取得失敗: その局の行だけ「取得できませんでした」。他の行は表示する。
- 予約が取れない(手動とぶつかった): 手動側は案内の文言を出し、自動側は静かにスキップして次の周期に回す。
- ストレージ(`FavoriteRepository`)の壊れた行は無視される(実装済み)。

## 8. テスト

- `latestEpisode`: 一致・不一致、終了前、余裕時間の境界、複数あるときの最新、正規化(全角/半角)。
- `DownloadRegistry`: 取得・解放、二重取得の拒否、並行(複数スレッドから同じキーを取って、成功は 1 つだけ)、`claimed` の更新。
- `FavoriteSync`: `claim` が false の回は取得も失敗計上もしない、`release` が成功・失敗・キャンセルのいずれでも呼ばれる。
- `FavoritesViewModel`: 追加・削除・スイッチの切り替えでリポジトリとスケジューラが期待どおりに呼ばれる(スケジューラは差し替え可能にする)、最新回の合成、局ごとの取得失敗の扱い。`MainViewModelBugfixTest` と同様に、プレイヤー接続を差し替えられる形にする。
- `HlsDownloadService`: Robolectric の既存テストの枠組みで、予約中の回は開始されないこと。
- 画面(Compose)はエミュレータで確認する: 一覧の各状態、★、スイッチ、権限ダイアログ、通知タップ。実機でのバックグラウンド実行(定期同期・`syncNow`)は手動確認。

## 9. 実装の段階

1. `DownloadRegistry`、サービス・`FavoriteSync` への組み込み、Worker の重複ロジック削除(挙動の衝突対策が先に入る)。
2. `latestEpisode`、`FavoriteSyncScheduler.syncNow`、`FavoritesViewModel`。
3. 通知権限の要求フロー、`contentIntent`、`open_tab` の受け取り。
4. UI: お気に入りタブ、`ProgramRow` の★、ナビゲーション 3 項目、文言(`strings.xml`)。
5. `CLAUDE.md` の更新(持ち越し項目の解消、使い方)。
