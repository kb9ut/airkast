/** Host that serves Radiko timefree HLS playlists. */
const TIMEFREE_PLAYLIST_URL = "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8";

/**
 * Builds the timefree HLS playlist URL for `stationId` between `ft` and `to`
 * (both Radiko timestamps, YYYYMMDDHHmmss).
 */
export function buildTimefreeHlsUrl(
  stationId: string,
  ft: string,
  to: string,
  durationSeconds: number,
  lsid: string
): string {
  return (
    `${TIMEFREE_PLAYLIST_URL}` +
    `?station_id=${stationId}` +
    `&l=${durationSeconds}` +
    `&ft=${ft}&to=${to}` +
    `&start_at=${ft}&end_at=${to}` +
    `&lsid=${lsid}&type=b`
  );
}
