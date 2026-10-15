# Airkast Web

radikoのタイムフリー番組をブラウザからダウンロードするための Next.js (App Router) アプリです。
ブラウザから radiko へ直接アクセスできないため、Next.js の API Routes が認証・番組表取得・HLS中継を代行します。
プロジェクト全体は [ルートの README](../README.md) と [DEVELOPMENT_GUIDE.md](../DEVELOPMENT_GUIDE.md) を参照してください。

## 開発

```bash
npm ci
npm run dev            # http://localhost:3000
npm run lint
npx tsc --noEmit
npm run build
```

radikoのPC版認証はIPアドレスで位置を判定するため、**日本国内のネットワークから実行**する必要があります。

## API Routes (`src/app/api`)

| エンドポイント | 説明 |
|----------------|------|
| `POST /api/auth` | radiko の auth1 + auth2 を実行し、`authToken` と `areaId` を返す |
| `GET /api/stations?areaId=JP13` | エリア内の放送局一覧 |
| `GET /api/programs?stationId=TBS&date=YYYYMMDD` | 放送局・日付ごとの番組表 |
| `GET /api/playlist?url=...&authToken=...` | HLSプレイリスト (マスター → メディア) を解決し、セグメントURLの一覧を返す |
| `GET /api/stream?url=...&authToken=...` | radiko/SmartStream へ認証ヘッダー付きでリクエストを中継 (プレイリストはURLを書き換え) |

## 構成

- `src/lib/radiko.ts` — サーバー専用の radiko クライアント (認証・放送局・番組表)
- `src/lib/hls.ts` — タイムフリーHLSのURL生成
- `src/lib/types.ts` — 型と、時刻 (`YYYYMMDDHHmmss`) の変換・表示ヘルパー
- `src/components/DownloadButton.tsx` — 5分単位でプレイリストを取得し、セグメントを順に取得して `.aac` として保存
- `src/app/page.tsx` — 番組検索 UI

ダウンロードは生の AAC (`.aac`) で保存されます。M4A への変換は Android アプリ側のみで行います。

## デプロイ

Vercel にデプロイします。`vercel.json` で関数のリージョンを東京 (`hnd1`) に固定しています (日本国内IPが必要なため)。
