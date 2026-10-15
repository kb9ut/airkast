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

export interface AuthState {
  authToken: string;
  areaId: string;
}

/** Parses a Radiko timestamp (YYYYMMDDHHmmss, local time) into epoch milliseconds. */
export function parseRadikoTime(t: string): number {
  const y = parseInt(t.slice(0, 4));
  const m = parseInt(t.slice(4, 6)) - 1;
  const d = parseInt(t.slice(6, 8));
  const h = parseInt(t.slice(8, 10));
  const min = parseInt(t.slice(10, 12));
  const s = parseInt(t.slice(12, 14));
  return new Date(y, m, d, h, min, s).getTime();
}

/** Formats epoch milliseconds as a Radiko timestamp (YYYYMMDDHHmmss, local time). */
export function toRadikoTime(ms: number): string {
  const d = new Date(ms);
  return (
    d.getFullYear().toString() +
    String(d.getMonth() + 1).padStart(2, "0") +
    String(d.getDate()).padStart(2, "0") +
    String(d.getHours()).padStart(2, "0") +
    String(d.getMinutes()).padStart(2, "0") +
    String(d.getSeconds()).padStart(2, "0")
  );
}

export function formatTime(timeStr: string): string {
  if (timeStr.length < 12) return "";
  return `${timeStr.slice(8, 10)}:${timeStr.slice(10, 12)}`;
}

export function formatDate(timeStr: string): string {
  if (timeStr.length < 8) return "";
  return `${timeStr.slice(4, 6)}/${timeStr.slice(6, 8)}`;
}

export function formatDateForApi(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}${m}${d}`;
}

export function formatDateDisplay(date: Date): string {
  const days = ["日", "月", "火", "水", "木", "金", "土"];
  const m = date.getMonth() + 1;
  const d = date.getDate();
  const day = days[date.getDay()];
  return `${m}/${d}(${day})`;
}

export function formatDuration(startTime: string, endTime: string): string {
  const mins = Math.round((parseRadikoTime(endTime) - parseRadikoTime(startTime)) / 60000);
  if (mins < 60) return `${mins}min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

export function getPastDates(count: number): Date[] {
  const dates: Date[] = [];
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    d.setHours(0, 0, 0, 0);
    dates.push(d);
  }
  return dates;
}

export function isProgramPast(endTime: string): boolean {
  if (endTime.length < 14) return false;
  return parseRadikoTime(endTime) < Date.now();
}
