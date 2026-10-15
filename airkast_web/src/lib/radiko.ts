/**
 * Radiko API client for server-side use.
 * Handles PC-version authentication and API calls.
 */

import type { Program, Station } from "./types";

const PC_FULL_KEY = "bcd151073c03b352e1ef2fd66c32209da9ca0afa";

export const DEFAULT_HEADERS: Record<string, string> = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "X-Radiko-App": "pc_html5",
  "X-Radiko-App-Version": "0.0.1",
  "X-Radiko-User": "dummy_user",
  "X-Radiko-Device": "pc",
};

const AUTH1_URL = "https://radiko.jp/v2/api/auth1";
const AUTH2_URL = "https://radiko.jp/v2/api/auth2";

export interface AuthSession {
  authToken: string;
  areaId: string;
  cookies: string[];
}

function collectSetCookies(res: Response, into: string[]): void {
  res.headers.forEach((value, key) => {
    if (key.toLowerCase() === "set-cookie") {
      into.push(value);
    }
  });
}

/**
 * Perform full Radiko authentication (auth1 + auth2).
 */
export async function authenticate(): Promise<AuthSession> {
  // Step 1: auth1
  const auth1Res = await fetch(AUTH1_URL, { headers: DEFAULT_HEADERS });
  if (!auth1Res.ok) {
    throw new Error(`auth1 failed: HTTP ${auth1Res.status}`);
  }

  const authToken = auth1Res.headers.get("X-Radiko-Authtoken");
  const keyLength = parseInt(auth1Res.headers.get("X-Radiko-KeyLength") || "0");
  const keyOffset = parseInt(auth1Res.headers.get("X-Radiko-KeyOffset") || "0");

  if (!authToken) throw new Error("Missing X-Radiko-Authtoken header");

  const cookies: string[] = [];
  collectSetCookies(auth1Res, cookies);

  // Step 2: Generate partial key
  const keyBytes = Buffer.from(PC_FULL_KEY, "utf-8");
  const partialBytes = keyBytes.subarray(keyOffset, Math.min(keyOffset + keyLength, keyBytes.length));
  const partialKey = partialBytes.toString("base64");

  // Step 3: auth2
  const auth2Headers: Record<string, string> = {
    ...DEFAULT_HEADERS,
    "X-Radiko-Authtoken": authToken,
    "X-Radiko-Partialkey": partialKey,
  };
  if (cookies.length > 0) {
    auth2Headers["Cookie"] = cookies.map((c) => c.split(";")[0]).join("; ");
  }

  const auth2Res = await fetch(AUTH2_URL, { headers: auth2Headers });
  if (!auth2Res.ok) {
    throw new Error(`auth2 failed: HTTP ${auth2Res.status}`);
  }
  collectSetCookies(auth2Res, cookies);

  const body = (await auth2Res.text()).trim();
  if (body === "OUT") {
    throw new Error("エリア外です。日本国内からアクセスしてください。");
  }

  const areaId = body.split(",")[0];
  if (!areaId) throw new Error(`Failed to parse area ID: ${body}`);

  return { authToken, areaId, cookies };
}

/**
 * Parse stations XML response.
 */
function parseStationsXml(xml: string): Station[] {
  const stations: Station[] = [];
  const stationRegex = /<station>[\s\S]*?<\/station>/g;
  let match;
  while ((match = stationRegex.exec(xml)) !== null) {
    const block = match[0];
    const id = block.match(/<id>(.*?)<\/id>/)?.[1];
    const name = block.match(/<name>(.*?)<\/name>/)?.[1];
    if (id && name) {
      stations.push({ id, name });
    }
  }
  return stations;
}

/**
 * Fetch station list for the given area.
 */
export async function getStations(areaId: string): Promise<Station[]> {
  const url = `https://radiko.jp/v3/station/list/${areaId}.xml`;
  const res = await fetch(url, { headers: DEFAULT_HEADERS });
  if (!res.ok) throw new Error(`getStations failed: HTTP ${res.status}`);
  return parseStationsXml(await res.text());
}

/**
 * Parse program guide XML response.
 */
function parseProgramGuideXml(xml: string, stationId: string): Program[] {
  const programs: Program[] = [];
  const progRegex = /<prog\s[^>]*>[\s\S]*?<\/prog>/g;
  let match;
  while ((match = progRegex.exec(xml)) !== null) {
    const block = match[0];
    const id = block.match(/id="([^"]+)"/)?.[1] || "";
    const ft = block.match(/ft="([^"]+)"/)?.[1] || "";
    const to = block.match(/to="([^"]+)"/)?.[1] || "";
    const title = block.match(/<title>([\s\S]*?)<\/title>/)?.[1]?.trim() || "";
    const desc = block.match(/<desc>([\s\S]*?)<\/desc>/)?.[1]?.trim() || "";
    const pfm = block.match(/<pfm>([\s\S]*?)<\/pfm>/)?.[1]?.trim() || "";
    const img = block.match(/<img>([\s\S]*?)<\/img>/)?.[1]?.trim() || null;
    programs.push({
      id,
      stationId,
      startTime: ft,
      endTime: to,
      title,
      description: desc,
      performer: pfm,
      imageUrl: img,
    });
  }
  return programs;
}

/**
 * Fetch program guide for a station on a given date.
 */
export async function getProgramGuide(stationId: string, date: string): Promise<Program[]> {
  const url = `https://radiko.jp/v3/program/station/date/${date}/${stationId}.xml`;
  const res = await fetch(url, { headers: DEFAULT_HEADERS });
  if (!res.ok) throw new Error(`getProgramGuide failed: HTTP ${res.status}`);
  return parseProgramGuideXml(await res.text(), stationId);
}
