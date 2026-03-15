import { NextRequest, NextResponse } from "next/server";

const DEFAULT_HEADERS: Record<string, string> = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "X-Radiko-App": "pc_html5",
  "X-Radiko-App-Version": "0.0.1",
  "X-Radiko-User": "dummy_user",
  "X-Radiko-Device": "pc",
};

function resolveUrl(baseUrl: string, relativeUrl: string): string {
  if (relativeUrl.startsWith("http")) return relativeUrl;
  const baseWithoutQuery = baseUrl.split("?")[0];
  const lastSlash = baseWithoutQuery.lastIndexOf("/");
  const baseDir = lastSlash !== -1 ? baseWithoutQuery.substring(0, lastSlash + 1) : "/";
  if (relativeUrl.startsWith("/")) {
    const url = new URL(baseUrl);
    return `${url.protocol}//${url.host}${relativeUrl}`;
  }
  return baseDir + relativeUrl;
}

function parseMediaPlaylistUrl(content: string): string | null {
  const lines = content.split("\n");
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
      if (i + 1 < lines.length && lines[i + 1].trim()) {
        return lines[i + 1].trim();
      }
    }
  }
  return lines.find((l) => l.trim() && !l.startsWith("#") && l.startsWith("http"))?.trim() || null;
}

function parseSegmentUrls(content: string, playlistUrl: string): string[] {
  return content
    .split("\n")
    .filter((l) => l.trim() && !l.startsWith("#"))
    .map((l) => resolveUrl(playlistUrl, l.trim()));
}

/**
 * GET /api/playlist?url=...&authToken=...
 * Fetches and parses HLS playlist, returns list of segment URLs.
 * Handles master → media playlist resolution.
 */
export async function GET(request: NextRequest) {
  const url = request.nextUrl.searchParams.get("url");
  const authToken = request.nextUrl.searchParams.get("authToken");

  if (!url || !authToken) {
    return NextResponse.json({ error: "url and authToken are required" }, { status: 400 });
  }

  try {
    const headers: Record<string, string> = {
      ...DEFAULT_HEADERS,
      "X-Radiko-Authtoken": authToken,
      Referer: "https://radiko.jp/",
      Origin: "https://radiko.jp",
    };

    // Fetch the playlist
    const res = await fetch(url, { headers });
    if (!res.ok) {
      return NextResponse.json({ error: `Playlist fetch failed: HTTP ${res.status}` }, { status: res.status });
    }

    let content = await res.text();
    let currentUrl = url;

    // If it's a master playlist, resolve to media playlist
    if (content.includes("#EXT-X-STREAM-INF")) {
      const mediaUrl = parseMediaPlaylistUrl(content);
      if (!mediaUrl) {
        return NextResponse.json({ error: "Failed to parse media playlist URL" }, { status: 500 });
      }
      const resolvedUrl = resolveUrl(currentUrl, mediaUrl);
      const mediaRes = await fetch(resolvedUrl, { headers });
      if (!mediaRes.ok) {
        return NextResponse.json({ error: `Media playlist fetch failed: HTTP ${mediaRes.status}` }, { status: mediaRes.status });
      }
      content = await mediaRes.text();
      currentUrl = resolvedUrl;
    }

    const segmentUrls = parseSegmentUrls(content, currentUrl);

    return NextResponse.json({ segments: segmentUrls });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Playlist parse error";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
