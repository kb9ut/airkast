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

      const chunkDurationMs = 300 * 1000;
      const allSegmentUrls: string[] = [];
      let currentStart = parseTime(ft);
      const endMs = parseTime(to);

      while (currentStart < endMs) {
        const currentEnd = Math.min(currentStart + chunkDurationMs, endMs);
        const durationSeconds = Math.floor((currentEnd - currentStart) / 1000);
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

        for (const seg of playlistData.segments) {
          const segId = seg.split("?")[0];
          if (!allSegmentUrls.some((s) => s.split("?")[0] === segId)) {
            allSegmentUrls.push(seg);
          }
        }
        currentStart = currentEnd;
      }

      if (allSegmentUrls.length === 0) {
        throw new Error("No segments found");
      }

      setState("downloading");

      const chunks: Uint8Array[] = [];
      for (let i = 0; i < allSegmentUrls.length; i++) {
        const segUrl = allSegmentUrls[i];
        const res = await fetch(
          `/api/stream?url=${encodeURIComponent(segUrl)}&authToken=${encodeURIComponent(authToken)}`
        );
        if (!res.ok) throw new Error(`Segment ${i + 1} failed`);
        const data = new Uint8Array(await res.arrayBuffer());
        chunks.push(data);
        setProgress(Math.round(((i + 1) / allSegmentUrls.length) * 100));
      }

      const totalLength = chunks.reduce((sum, c) => sum + c.length, 0);
      const result = new Uint8Array(totalLength);
      let offset = 0;
      for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.length;
      }

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
      setError(e instanceof Error ? e.message : "Download failed");
      setState("error");
    }
  }, [program, authToken]);

  // Idle - download icon button
  if (state === "idle") {
    return (
      <button
        onClick={download}
        className="size-10 rounded-full flex items-center justify-center transition-all duration-200 active:scale-90"
        style={{
          background: "var(--accent)",
          color: "#fff",
          boxShadow: "0 2px 8px rgba(0, 122, 255, 0.3)",
        }}
        aria-label="Download"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="7 10 12 15 17 10" />
          <line x1="12" y1="15" x2="12" y2="3" />
        </svg>
      </button>
    );
  }

  // Preparing
  if (state === "fetching_playlist") {
    return (
      <div className="size-10 rounded-full flex items-center justify-center" style={{ background: "var(--bg-tertiary)" }}>
        <div className="size-5 rounded-full border-2 border-t-transparent animate-spin" style={{ borderColor: "var(--accent)", borderTopColor: "transparent" }} />
      </div>
    );
  }

  // Downloading - glassmorphism circular progress
  if (state === "downloading") {
    const circumference = 2 * Math.PI * 16;
    const strokeDashoffset = circumference - (progress / 100) * circumference;
    return (
      <div className="size-10 relative flex items-center justify-center">
        <svg className="size-10 -rotate-90" viewBox="0 0 40 40">
          <circle cx="20" cy="20" r="16" fill="none" stroke="var(--border-color-strong)" strokeWidth="3" />
          <circle
            cx="20" cy="20" r="16" fill="none"
            stroke="var(--accent)"
            strokeWidth="3"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            className="transition-all duration-300"
            style={{ filter: "drop-shadow(0 0 4px rgba(0,122,255,0.4))" }}
          />
        </svg>
        <span className="absolute text-[9px] font-semibold" style={{ color: "var(--text-secondary)" }}>
          {progress}
        </span>
      </div>
    );
  }

  // Done - checkmark
  if (state === "done") {
    return (
      <div
        className="size-10 rounded-full flex items-center justify-center"
        style={{ background: "var(--success-bg)", color: "var(--success)" }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </div>
    );
  }

  // Error - retry
  return (
    <button
      onClick={() => { setState("idle"); setError(null); }}
      className="size-10 rounded-full flex items-center justify-center transition-all active:scale-90"
      style={{ background: "var(--error-bg)", color: "var(--error)" }}
      title={error || ""}
      aria-label="Retry"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="23 4 23 10 17 10" />
        <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
      </svg>
    </button>
  );
}
