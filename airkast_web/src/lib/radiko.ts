/**
 * Radiko API client for server-side use.
 * Handles PC-version authentication and API calls.
 */

const PC_FULL_KEY = "bcd151073c03b352e1ef2fd66c32209da9ca0afa";

const DEFAULT_HEADERS: Record<string, string> = {
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

export interface Station {
  id: string;
  name: string;
}

export interface Program {
  id: string;
  stationId: string;
  startTime: string;
  endTime: string;
  title: string;
  description: string;
  performer: string;
  imageUrl: string | null;
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

  // Collect cookies from auth1
  const cookies: string[] = [];
  auth1Res.headers.forEach((value, key) => {
    if (key.toLowerCase() === "set-cookie") {
      cookies.push(value);
    }
  });

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

  // Collect cookies from auth2 too
  auth2Res.headers.forEach((value, key) => {
    if (key.toLowerCase() === "set-cookie") {
      cookies.push(value);
    }
  });

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

/**
 * Generate HLS stream URL for a timefree program.
 */
export function generateHlsUrl(program: Program, lsid?: string): string {
  const finalLsid = lsid || crypto.randomUUID().replace(/-/g, "");
  const ft = program.startTime;
  const to = program.endTime;

  // Parse duration
  const parseTime = (t: string) => {
    const y = parseInt(t.slice(0, 4));
    const m = parseInt(t.slice(4, 6)) - 1;
    const d = parseInt(t.slice(6, 8));
    const h = parseInt(t.slice(8, 10));
    const min = parseInt(t.slice(10, 12));
    const s = parseInt(t.slice(12, 14));
    return new Date(y, m, d, h, min, s).getTime();
  };

  const durationSeconds = Math.floor((parseTime(to) - parseTime(ft)) / 1000);

  return (
    `https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8` +
    `?station_id=${program.stationId}` +
    `&l=${durationSeconds}` +
    `&ft=${ft}` +
    `&to=${to}` +
    `&start_at=${ft}` +
    `&end_at=${to}` +
    `&lsid=${finalLsid}` +
    `&type=b`
  );
}

/**
 * Area list for Japan.
 */
export const AREA_LIST = [
  { id: "JP1", name: "北海道" },
  { id: "JP2", name: "青森" },
  { id: "JP3", name: "岩手" },
  { id: "JP4", name: "宮城" },
  { id: "JP5", name: "秋田" },
  { id: "JP6", name: "山形" },
  { id: "JP7", name: "福島" },
  { id: "JP8", name: "茨城" },
  { id: "JP9", name: "栃木" },
  { id: "JP10", name: "群馬" },
  { id: "JP11", name: "埼玉" },
  { id: "JP12", name: "千葉" },
  { id: "JP13", name: "東京" },
  { id: "JP14", name: "神奈川" },
  { id: "JP15", name: "新潟" },
  { id: "JP16", name: "富山" },
  { id: "JP17", name: "石川" },
  { id: "JP18", name: "福井" },
  { id: "JP19", name: "山梨" },
  { id: "JP20", name: "長野" },
  { id: "JP21", name: "岐阜" },
  { id: "JP22", name: "静岡" },
  { id: "JP23", name: "愛知" },
  { id: "JP24", name: "三重" },
  { id: "JP25", name: "滋賀" },
  { id: "JP26", name: "京都" },
  { id: "JP27", name: "大阪" },
  { id: "JP28", name: "兵庫" },
  { id: "JP29", name: "奈良" },
  { id: "JP30", name: "和歌山" },
  { id: "JP31", name: "鳥取" },
  { id: "JP32", name: "島根" },
  { id: "JP33", name: "岡山" },
  { id: "JP34", name: "広島" },
  { id: "JP35", name: "山口" },
  { id: "JP36", name: "徳島" },
  { id: "JP37", name: "香川" },
  { id: "JP38", name: "愛媛" },
  { id: "JP39", name: "高知" },
  { id: "JP40", name: "福岡" },
  { id: "JP41", name: "佐賀" },
  { id: "JP42", name: "長崎" },
  { id: "JP43", name: "熊本" },
  { id: "JP44", name: "大分" },
  { id: "JP45", name: "宮崎" },
  { id: "JP46", name: "鹿児島" },
  { id: "JP47", name: "沖縄" },
];
