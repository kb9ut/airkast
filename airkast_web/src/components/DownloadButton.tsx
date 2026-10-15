"use client";

import { useState, useCallback } from "react";
import { buildTimefreeHlsUrl } from "@/lib/hls";
import { parseRadikoTime, toRadikoTime } from "@/lib/types";
import type { Program } from "@/lib/types";

interface DownloadButtonProps {
  program: Program;
  authToken: string;
}

type DownloadState = "idle" | "fetching_playlist" | "downloading" | "done" | "error";

/** Timefree playlists are requested in chunks of this many milliseconds. */
const CHUNK_DURATION_MS = 300 * 1000;

function newLsid(): string {
  return crypto.randomUUID().replace(/-/g, "");
}

async function fetchChunkSegments(hlsUrl: string, authToken: string): Promise<string[]> {
  const res = await fetch(
    `/api/playlist?url=${encodeURIComponent(hlsUrl)}&authToken=${encodeURIComponent(authToken)}`
  );
  const data = await res.json();
  if (data.error) throw new Error(data.error);
  return data.segments;
}

/**
 * Collects the segment URLs of the whole program by requesting the timefree
 * playlist in fixed-size chunks. Segments repeated across chunks are skipped.
 */
async function fetchAllSegmentUrls(program: Program, authToken: string): Promise<string[]> {
  const lsid = newLsid();
  const endMs = parseRadikoTime(program.endTime);
  const segmentUrls: string[] = [];
  const seen = new Set<string>();

  let chunkStart = parseRadikoTime(program.startTime);
  while (chunkStart < endMs) {
    const chunkEnd = Math.min(chunkStart + CHUNK_DURATION_MS, endMs);
    const hlsUrl = buildTimefreeHlsUrl(
      program.stationId,
      toRadikoTime(chunkStart),
      toRadikoTime(chunkEnd),
      Math.floor((chunkEnd - chunkStart) / 1000),
      lsid
    );

    for (const seg of await fetchChunkSegments(hlsUrl, authToken)) {
      const segId = seg.split("?")[0];
      if (!seen.has(segId)) {
        seen.add(segId);
        segmentUrls.push(seg);
      }
    }
    chunkStart = chunkEnd;
  }
  return segmentUrls;
}

async function downloadSegments(
  segmentUrls: string[],
  authToken: string,
  onProgress: (percent: number) => void
): Promise<Blob> {
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  for (let i = 0; i < segmentUrls.length; i++) {
    const res = await fetch(
      `/api/stream?url=${encodeURIComponent(segmentUrls[i])}&authToken=${encodeURIComponent(authToken)}`
    );
    if (!res.ok) throw new Error(`Segment ${i + 1} failed`);
    chunks.push(new Uint8Array(await res.arrayBuffer()));
    onProgress(Math.round(((i + 1) / segmentUrls.length) * 100));
  }
  return new Blob(chunks, { type: "audio/aac" });
}

function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function downloadFileName(program: Program): string {
  const safeTitle = (program.title || program.id).replace(/[/\\:*?"<>|]/g, "_");
  return `${program.stationId}_${program.startTime}_${safeTitle}.aac`;
}

export default function DownloadButton({ program, authToken }: DownloadButtonProps) {
  const [state, setState] = useState<DownloadState>("idle");
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const download = useCallback(async () => {
    setState("fetching_playlist");
    setProgress(0);
    setError(null);

    try {
      const segmentUrls = await fetchAllSegmentUrls(program, authToken);
      if (segmentUrls.length === 0) {
        throw new Error("No segments found");
      }

      setState("downloading");
      const blob = await downloadSegments(segmentUrls, authToken, setProgress);
      saveBlob(blob, downloadFileName(program));

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
