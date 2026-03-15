"use client";

import { useState, useCallback } from "react";
import type { Program } from "@/lib/types";

interface DownloadButtonProps {
  program: Program;
  authToken: string;
}

type DownloadState = "idle" | "fetching_playlist" | "downloading" | "done" | "error";

export default function DownloadButton({ program, authToken }: DownloadButtonProps) {
  const [state, setState] = useState<DownloadState>("idle");
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const download = useCallback(async () => {
    setState("fetching_playlist");
    setProgress(0);
    setError(null);

    try {
      // Generate HLS URL
      const lsid = crypto.randomUUID().replace(/-/g, "");
      const ft = program.startTime;
      const to = program.endTime;

      const parseTime = (t: string) => {
        const y = parseInt(t.slice(0, 4));
        const m = parseInt(t.slice(4, 6)) - 1;
        const d = parseInt(t.slice(6, 8));
        const h = parseInt(t.slice(8, 10));
        const min = parseInt(t.slice(10, 12));
        const s = parseInt(t.slice(12, 14));
        return new Date(y, m, d, h, min, s).getTime();
      };

      const totalDurationMs = parseTime(to) - parseTime(ft);
      const chunkDurationMs = 300 * 1000; // 5 minutes per chunk
      const allSegmentUrls: string[] = [];

      // Fetch playlists in chunks (like the Android app)
      let currentStart = parseTime(ft);
      const endMs = parseTime(to);

      while (currentStart < endMs) {
        const currentEnd = Math.min(currentStart + chunkDurationMs, endMs);
        const durationSeconds = Math.floor((currentEnd - currentStart) / 1000);

        // Format times back to yyyyMMddHHmmss
        const fmtTs = (ms: number) => {
          const d = new Date(ms);
          return (
            d.getFullYear().toString() +
            String(d.getMonth() + 1).padStart(2, "0") +
            String(d.getDate()).padStart(2, "0") +
            String(d.getHours()).padStart(2, "0") +
            String(d.getMinutes()).padStart(2, "0") +
            String(d.getSeconds()).padStart(2, "0")
          );
        };

        const chunkFt = fmtTs(currentStart);
        const chunkTo = fmtTs(currentEnd);

        const hlsUrl =
          `https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8` +
          `?station_id=${program.stationId}` +
          `&l=${durationSeconds}` +
          `&ft=${chunkFt}&to=${chunkTo}` +
          `&start_at=${chunkFt}&end_at=${chunkTo}` +
          `&lsid=${lsid}&type=b`;

        const playlistRes = await fetch(
          `/api/playlist?url=${encodeURIComponent(hlsUrl)}&authToken=${encodeURIComponent(authToken)}`
        );
        const playlistData = await playlistRes.json();
        if (playlistData.error) throw new Error(playlistData.error);

        // Deduplicate segments
        for (const seg of playlistData.segments) {
          const segId = seg.split("?")[0];
          if (!allSegmentUrls.some((s) => s.split("?")[0] === segId)) {
            allSegmentUrls.push(seg);
          }
        }

        currentStart = currentEnd;
      }

      if (allSegmentUrls.length === 0) {
        throw new Error("セグメントが見つかりませんでした");
      }

      setState("downloading");

      // Download segments and accumulate
      const chunks: Uint8Array[] = [];
      for (let i = 0; i < allSegmentUrls.length; i++) {
        const segUrl = allSegmentUrls[i];
        const res = await fetch(
          `/api/stream?url=${encodeURIComponent(segUrl)}&authToken=${encodeURIComponent(authToken)}`
        );
        if (!res.ok) throw new Error(`セグメント ${i + 1} のダウンロードに失敗`);
        const data = new Uint8Array(await res.arrayBuffer());
        chunks.push(data);
        setProgress(Math.round(((i + 1) / allSegmentUrls.length) * 100));
      }

      // Concatenate all chunks
      const totalLength = chunks.reduce((sum, c) => sum + c.length, 0);
      const result = new Uint8Array(totalLength);
      let offset = 0;
      for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.length;
      }

      // Create download link
      const blob = new Blob([result], { type: "audio/aac" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const safeTitle = (program.title || program.id).replace(/[/\\:*?"<>|]/g, "_");
      a.download = `${program.stationId}_${program.startTime}_${safeTitle}.aac`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      setState("done");
      setTimeout(() => setState("idle"), 3000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "ダウンロードに失敗しました");
      setState("error");
    }
  }, [program, authToken]);

  if (state === "idle") {
    return (
      <button
        onClick={download}
        className="shrink-0 px-3 py-1.5 bg-green-700 hover:bg-green-600 rounded text-xs font-medium"
      >
        DL
      </button>
    );
  }

  if (state === "fetching_playlist") {
    return (
      <div className="shrink-0 px-3 py-1.5 bg-gray-700 rounded text-xs text-gray-300">
        準備中...
      </div>
    );
  }

  if (state === "downloading") {
    return (
      <div className="shrink-0 flex items-center gap-2">
        <div className="w-16 bg-gray-700 rounded-full h-2">
          <div
            className="bg-blue-500 h-2 rounded-full transition-all"
            style={{ width: `${progress}%` }}
          />
        </div>
        <span className="text-xs text-gray-400">{progress}%</span>
      </div>
    );
  }

  if (state === "done") {
    return (
      <div className="shrink-0 px-3 py-1.5 bg-green-900 rounded text-xs text-green-300">
        完了
      </div>
    );
  }

  // error
  return (
    <button
      onClick={() => { setState("idle"); setError(null); }}
      className="shrink-0 px-3 py-1.5 bg-red-900 rounded text-xs text-red-300"
      title={error || ""}
    >
      失敗 (再試行)
    </button>
  );
}
