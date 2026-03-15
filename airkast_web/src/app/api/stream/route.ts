import { NextRequest, NextResponse } from "next/server";

const DEFAULT_HEADERS: Record<string, string> = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "X-Radiko-App": "pc_html5",
  "X-Radiko-App-Version": "0.0.1",
  "X-Radiko-User": "dummy_user",
  "X-Radiko-Device": "pc",
};

/**
 * GET /api/stream?url=...&authToken=...
 * Proxies HLS stream requests to Radiko/SmartStream, adding auth headers.
 * Handles both .m3u8 playlists and .aac segments.
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
    };

    const res = await fetch(url, { headers });
    if (!res.ok) {
      return NextResponse.json(
        { error: `Upstream error: HTTP ${res.status}` },
        { status: res.status }
      );
    }

    const contentType = res.headers.get("content-type") || "application/octet-stream";

    // For m3u8 playlists, rewrite URLs to go through our proxy
    if (contentType.includes("mpegurl") || url.includes(".m3u8")) {
      let body = await res.text();

      // Rewrite absolute URLs in playlist to go through our proxy
      body = body.replace(
        /^(https?:\/\/[^\s]+)$/gm,
        (match) => `/api/stream?url=${encodeURIComponent(match)}&authToken=${encodeURIComponent(authToken)}`
      );

      // Rewrite relative URLs
      const baseUrl = url.substring(0, url.lastIndexOf("/") + 1);
      body = body.replace(
        /^(?!#)(?!https?:\/\/)(?!\/api\/stream)([^\s]+)$/gm,
        (match) =>
          `/api/stream?url=${encodeURIComponent(baseUrl + match)}&authToken=${encodeURIComponent(authToken)}`
      );

      return new NextResponse(body, {
        headers: {
          "Content-Type": "application/vnd.apple.mpegurl",
          "Access-Control-Allow-Origin": "*",
        },
      });
    }

    // For binary segments (AAC, key files, etc.), stream through
    const responseHeaders = new Headers({
      "Content-Type": contentType,
      "Access-Control-Allow-Origin": "*",
    });
    const contentLength = res.headers.get("content-length");
    if (contentLength) {
      responseHeaders.set("Content-Length", contentLength);
    }

    return new NextResponse(res.body, { headers: responseHeaders });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Stream proxy error";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
