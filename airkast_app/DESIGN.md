# Airkast Android デザイン設計書

対象: `airkast_app`(Jetpack Compose / Material 3)。radiko タイムフリー番組の「探す → 保存する → 聴く」を最短にする、コンテンツファーストの UI。

## 1. 方針(Principles)

| 原則 | 具体 |
|---|---|
| コンテンツファースト | 番組タイトルと放送時刻が主役。装飾(ガラス・ブラー・グラデーション・色付きバー)は廃止し、トーン差のあるサーフェスだけで階層を作る。 |
| システムに従う | ライト/ダークは OS 設定に追従。Android 12+ は Dynamic Color(壁紙由来)、それ以外は下記の静的パレット。 |
| 状態が一目で分かる | ダウンロードボタンは「未取得 / 待機 / 取得中(進捗) / 変換中 / 取得済み / 失敗」を形・色・アイコンで区別。色だけに頼らない。 |
| 意味のあるモーション | 位置・サイズの変化はバネ(spatial spring)、色・透明度の変化は減衰バネ(effects spring)。装飾目的のアニメーションはしない。 |
| 誰でも押せる | タッチターゲット 48dp 以上、本文コントラスト 4.5:1 以上、フォント拡大(〜200%)で崩れない。 |
| 端から端まで | Edge-to-Edge。システムバー/ジェスチャーバーの inset はコンポーネント側で処理。 |

Material 3 Expressive の考え方(大きな角丸、強弱のはっきりした文字、バネの動き、トーンによる階層)を採用するが、採用中の Compose BOM `2025.06.00`(material3 1.3.x)には Expressive 専用 API(`MaterialExpressiveTheme`, `LoadingIndicator`, `ButtonGroup` 等)が無いため、**安定版 API のみで同等の表現**を実装する(依存関係の更新はしない)。

## 2. カラーロール

静的パレットは「インディゴ(信号/電波)+ テラコッタ(On Air の温かみ)」。Material Theme Builder 相当のトーナルパレットを手で構成し、全ペアのコントラストを計算して検証済み(下記)。

| ロール | Light | Dark | 用途 |
|---|---|---|---|
| primary / onPrimary | `#4355B9` / `#FFFFFF` | `#B9C3FF` / `#08218A` | 主操作(再生ボタン、選択中の日付) |
| primaryContainer / on | `#DDE1FF` / `#00164E` | `#293CA0` / `#DDE1FF` | 取得済み、再生アイコン、強調コンテナ |
| secondaryContainer / on | `#DFE1F9` / `#171B2C` | `#424659` / `#DFE1F9` | 選択中ナビ、フィルタチップ |
| tertiary / container | `#8F4C38` / `#FFDBD0` | `#FFB5A0` / `#723523` | 変換中、ターボ、再生中アクセント |
| error / container | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` | 失敗、中止、警告 |
| surface 系 | `#FBF8FF`(+ container 5 段階) | `#121318`(+ container 5 段階) | 背景〜カード。elevation は影でなく surfaceContainer* の段階で表す |
| onSurface / onSurfaceVariant | `#1B1B21` / `#45464F` | `#E3E1E9` / `#C6C5D0` | 本文 / 補助テキスト |
| outline / outlineVariant | `#767680` / `#C6C5D0` | `#90909A` / `#45464F` | 境界(outline は非テキスト 3:1 以上) |

検証結果(WCAG 比、最小値): 本文系ペア Light 4.98 以上 / Dark 7.2 以上。`onSurfaceVariant` on 全 surfaceContainer: Light 7.2 以上 / Dark 7.2 以上。`outline` on `surface`: Light 4.27 / Dark 5.87。Dynamic Color 時はシステムの tonal ルールが同等の比を保証する。

## 3. タイポグラフィ

フォントは端末標準(Roboto / Noto Sans CJK JP)のみ。ネットワーク取得フォントは使わない。強弱は **サイズ差とウェイト差**で付ける(Expressive の「強調」の考え方)。日本語は行間を広め(約 1.5 倍)、見出しは `LineBreak.Heading`、本文は `LineBreak.Paragraph` で禁則・文節折り返しを改善。時刻・進捗など数字は等幅(`tnum`)。

| スタイル | サイズ/行 | ウェイト | 使い所 |
|---|---|---|---|
| headlineMedium | 28/36 | Bold | 画面見出し(大きなタイトル) |
| titleLarge | 22/30 | SemiBold | 空状態/エラーの見出し、ダイアログの値 |
| titleMedium | 16/24 | SemiBold | 番組タイトル、セクション見出し |
| titleSmall | 14/20 | SemiBold | ミニプレイヤーのタイトル |
| bodyLarge/Medium | 16/24, 14/22 | Normal | 説明、出演者 |
| labelLarge/Medium/Small | 14, 12, 11 | Medium | ボタン、チップ、時刻、メタ情報 |

最小サイズは 11sp。すべて `sp`、レイアウトは `minHeight`/`heightIn` 基準で、フォント拡大時に切れない。

## 4. シェイプとエレベーション

シェイプトークン(`MaterialTheme.shapes`): extraSmall 8 / small 12 / medium 16 / large 24 / extraLarge 32 dp。ボタン・チップはピル型(完全な丸)。

- リストは「グルーブ型」: 同一コンテナ内で項目間 2dp、先頭/末尾は 20dp、中間は 6dp の角丸(`groupedItemShape`)。
- エレベーションは **トーン差が基本**: 背景 `surface` → カード `surfaceContainerLow/Container` → 浮遊要素(ミニプレイヤー) `surfaceContainerHigh`。影は浮遊するミニプレイヤーだけ 6dp。ガラス/ブラー/アルファ重ねは使わない。

## 5. スペーシング

4dp グリッド: 4 / 8 / 12 / 16 / 24 / 32。画面左右マージン 16dp、カード内余白 16dp、リスト項目の最小高さ 72dp。タッチターゲットは全アイコンボタン・チップ・行で 48dp 以上。

## 6. モーション

- `spatialSpring`(減衰比 0.8, stiffness 380): サイズ、位置、スケール、プレイヤー展開。
- `effectsSpring`(減衰比 1.0, stiffness 1600): 色、透明度、進捗値。
- 押下: 対象を 0.96 倍へバネで縮め、離すと戻る(`pressScale`)+ リップル。
- 状態遷移: ダウンロードボタンは `AnimatedContent` でアイコンが拡縮クロスフェード。取得済みはバネで「ポン」と現れる。再生/一時停止アイコンもクロスフェード。
- 画面切替: タブ方向に応じた水平スライド + フェード。ミニプレイヤー ⇄ 展開表示は `animateContentSize`(spatial spring)。再生中はイコライザー風の 3 本バーのみ無限アニメーション。
- Compose の標準挙動により、システムの「アニメーション削除」設定(`animator_duration_scale = 0`)に従う。

## 7. コンポーネント一覧

| コンポーネント | 概要 |
|---|---|
| `AirkastTheme` | ライト/ダーク追従、Dynamic Color 対応、静的フォールバック、Typography/Shapes |
| `AirkastTopBar` | 画面タイトル + オーバーフロー(エリア変更/ダウンロード設定/ライセンス)。スクロールでトーンが上がる |
| ナビゲーション | `NavigationBar` 2 項目(番組を探す / ダウンロード)。選択時にアイコンが塗りに切替 |
| `StationSelector` | 放送局選択のトーナルフィールド(ラベル付きドロップダウン) |
| `DateSelector` | 横スクロールの日付チップ(曜日・M/d、今日表示、選択中へ自動スクロール) |
| `TimeFilterChips` | `FilterChip` による時間帯フィルタ |
| `ProgramRow` | 時刻(左)/ タイトル + 出演者 / ダウンロードボタン。グルーブ型リスト |
| `DownloadButton` | 状態(`ProgramDownloadState`)のみから描画: 未取得 / 待機 / 取得中(円形進捗) / 変換中 / 取得済み / 失敗(再試行) |
| `DownloadProgressCard` | ダウンロード画面の進行中カード(ステータス文、線形進捗、中止) |
| `DownloadedFileRow` | 再生アイコン + タイトル + 局/日時 + 削除 |
| `MiniPlayer` | 浮遊カード。タップで展開。タイトル、イコライザー、再生/一時停止、細い進捗線 |
| `ExpandedPlayer` | シークバー、時間、±スキップ、再生/一時停止(大)、速度、ターボ(長押し) |
| `TurboButton` | 長押しでN倍速、タップで設定(既存の操作を維持、セマンティクス付き) |
| `StateMessage` | 空/エラー/認証の状態表示(アイコン + 見出し + 説明 + アクション) |
| ダイアログ | エリア選択 / ダウンロード設定 / ターボ設定(M3 既定の `surfaceContainerHigh`、選択行は 48dp 以上のラジオ行) |
| ランチャーアイコン | 新パレットのインディゴ背景 + 白の「A」+ 電波アーク。Android 13 のテーマアイコン(monochrome)対応 |

## 8. アクセシビリティ

- すべてのアイコンボタンに `contentDescription`(番組名入り: 「<番組名>をダウンロード」)。装飾アイコンは `null`。
- 進捗は `stateDescription` と `ProgressBarRangeInfo` で読み上げ。選択状態は `selected` / `Role` を付与。
- 色だけに依存しない(アイコン形状とテキストでも状態を表す)。
- フォント 200% でも日付チップ・フィルタは横スクロールで逃がし、行は 2 行まで折り返し。
- 文字列は `strings.xml` に集約。
