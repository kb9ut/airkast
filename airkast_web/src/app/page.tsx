"use client";

import { useState, useEffect } from "react";
import type { Station, Program, AuthState } from "@/lib/types";
import {
  formatTime,
  formatDateForApi,
  formatDateDisplay,
  formatDuration,
  getPastDates,
  isProgramPast,
} from "@/lib/types";
import DownloadButton from "@/components/DownloadButton";

const AREA_LIST = [
  { id: "JP1", name: "北海道" }, { id: "JP2", name: "青森" }, { id: "JP3", name: "岩手" },
  { id: "JP4", name: "宮城" }, { id: "JP5", name: "秋田" }, { id: "JP6", name: "山形" },
  { id: "JP7", name: "福島" }, { id: "JP8", name: "茨城" }, { id: "JP9", name: "栃木" },
  { id: "JP10", name: "群馬" }, { id: "JP11", name: "埼玉" }, { id: "JP12", name: "千葉" },
  { id: "JP13", name: "東京" }, { id: "JP14", name: "神奈川" }, { id: "JP15", name: "新潟" },
  { id: "JP16", name: "富山" }, { id: "JP17", name: "石川" }, { id: "JP18", name: "福井" },
  { id: "JP19", name: "山梨" }, { id: "JP20", name: "長野" }, { id: "JP21", name: "岐阜" },
  { id: "JP22", name: "静岡" }, { id: "JP23", name: "愛知" }, { id: "JP24", name: "三重" },
  { id: "JP25", name: "滋賀" }, { id: "JP26", name: "京都" }, { id: "JP27", name: "大阪" },
  { id: "JP28", name: "兵庫" }, { id: "JP29", name: "奈良" }, { id: "JP30", name: "和歌山" },
  { id: "JP31", name: "鳥取" }, { id: "JP32", name: "島根" }, { id: "JP33", name: "岡山" },
  { id: "JP34", name: "広島" }, { id: "JP35", name: "山口" }, { id: "JP36", name: "徳島" },
  { id: "JP37", name: "香川" }, { id: "JP38", name: "愛媛" }, { id: "JP39", name: "高知" },
  { id: "JP40", name: "福岡" }, { id: "JP41", name: "佐賀" }, { id: "JP42", name: "長崎" },
  { id: "JP43", name: "熊本" }, { id: "JP44", name: "大分" }, { id: "JP45", name: "宮崎" },
  { id: "JP46", name: "鹿児島" }, { id: "JP47", name: "沖縄" },
];

const TIME_FILTERS = [
  { label: "早朝", sub: "5-9", start: 5, end: 9 },
  { label: "午前", sub: "9-12", start: 9, end: 12 },
  { label: "午後", sub: "12-17", start: 12, end: 17 },
  { label: "夜", sub: "17-21", start: 17, end: 21 },
  { label: "深夜", sub: "21-29", start: 21, end: 29 },
  { label: "全て", sub: "", start: 0, end: 29 },
];

// Deterministic color from station ID for thumbnail backgrounds
function stationColor(stationId: string): string {
  let hash = 0;
  for (let i = 0; i < stationId.length; i++) {
    hash = stationId.charCodeAt(i) + ((hash << 5) - hash);
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue}, 45%, 55%)`;
}

function getHour(timeStr: string): number {
  if (timeStr.length < 10) return 0;
  return parseInt(timeStr.slice(8, 10));
}

export default function Home() {
  const [auth, setAuth] = useState<AuthState | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [authError, setAuthError] = useState<string | null>(null);

  const [stations, setStations] = useState<Station[]>([]);
  const [selectedAreaId, setSelectedAreaId] = useState<string>("");
  const [selectedStationId, setSelectedStationId] = useState<string>("");

  const dates = getPastDates(7);
  const [selectedDate, setSelectedDate] = useState<Date>(dates[dates.length - 1]);

  const [programs, setPrograms] = useState<Program[]>([]);
  const [programsLoading, setProgramsLoading] = useState(false);
  const [selectedTimeFilter, setSelectedTimeFilter] = useState(0);

  const [message, setMessage] = useState<string | null>(null);

  const selectedStation = stations.find((s) => s.id === selectedStationId);

  useEffect(() => {
    (async () => {
      try {
        const res = await fetch("/api/auth", { method: "POST" });
        const data = await res.json();
        if (data.error) throw new Error(data.error);
        setAuth(data);
        setSelectedAreaId(data.areaId);
      } catch (e) {
        setAuthError(e instanceof Error ? e.message : "Authentication failed");
      } finally {
        setAuthLoading(false);
      }
    })();
  }, []);

  useEffect(() => {
    if (!selectedAreaId) return;
    (async () => {
      try {
        const res = await fetch(`/api/stations?areaId=${selectedAreaId}`);
        const data = await res.json();
        if (data.error) throw new Error(data.error);
        setStations(data);
      } catch (e) {
        setMessage(e instanceof Error ? e.message : "Failed to load stations");
      }
    })();
  }, [selectedAreaId]);

  useEffect(() => {
    if (!selectedStationId || !selectedDate) return;
    (async () => {
      setProgramsLoading(true);
      try {
        const dateStr = formatDateForApi(selectedDate);
        const res = await fetch(`/api/programs?stationId=${selectedStationId}&date=${dateStr}`);
        const data = await res.json();
        if (data.error) throw new Error(data.error);
        setPrograms(data);
      } catch (e) {
        setMessage(e instanceof Error ? e.message : "Failed to load programs");
      } finally {
        setProgramsLoading(false);
      }
    })();
  }, [selectedStationId, selectedDate]);

  const filteredPrograms = programs.filter((p) => {
    const filter = TIME_FILTERS[selectedTimeFilter];
    const hour = getHour(p.startTime);
    return hour >= filter.start && hour < filter.end;
  });

  // Loading
  if (authLoading) {
    return (
      <div className="min-h-dvh flex items-center justify-center" style={{ background: "var(--bg-secondary)" }}>
        <div className="text-center">
          <div
            className="size-12 rounded-full border-[3px] border-t-transparent animate-spin mx-auto mb-5"
            style={{ borderColor: "var(--border-color-strong)", borderTopColor: "transparent" }}
          />
          <p className="text-sm font-medium tracking-tight" style={{ color: "var(--text-tertiary)" }}>
            Connecting to Radiko...
          </p>
        </div>
      </div>
    );
  }

  // Auth Error
  if (authError) {
    return (
      <div className="min-h-dvh flex items-center justify-center px-6" style={{ background: "var(--bg-secondary)" }}>
        <div className="text-center max-w-sm">
          <div
            className="size-16 rounded-full flex items-center justify-center mx-auto mb-5"
            style={{ background: "var(--error-bg)" }}
          >
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--error)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10" />
              <line x1="15" y1="9" x2="9" y2="15" />
              <line x1="9" y1="9" x2="15" y2="15" />
            </svg>
          </div>
          <h2 className="text-lg font-semibold tracking-tight mb-2" style={{ color: "var(--text-primary)" }}>
            Connection Failed
          </h2>
          <p className="text-sm mb-6" style={{ color: "var(--text-tertiary)" }}>{authError}</p>
          <button
            onClick={() => window.location.reload()}
            className="px-6 py-2.5 rounded-full text-sm font-medium text-white transition-all active:scale-95"
            style={{ background: "var(--accent)" }}
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-dvh" style={{ background: "var(--bg-secondary)" }}>
      {/* Header */}
      <header className="glass-strong sticky top-0 z-40" style={{ borderBottom: "1px solid var(--border-color)" }}>
        <div className="max-w-lg mx-auto px-4 py-3 flex items-center justify-between">
          <h1 className="text-[22px] font-bold tracking-tight" style={{ color: "var(--text-primary)" }}>
            Airkast
          </h1>
          <div
            className="px-3 py-1 rounded-full text-xs font-medium"
            style={{ background: "var(--accent-subtle)", color: "var(--accent)" }}
          >
            {AREA_LIST.find((a) => a.id === selectedAreaId)?.name || selectedAreaId}
          </div>
        </div>
      </header>

      <main className="max-w-lg mx-auto px-4 pt-4 pb-12">
        {/* Message */}
        {message && (
          <div
            className="mb-4 px-4 py-3 rounded-2xl flex items-center justify-between text-sm"
            style={{ background: "var(--error-bg)", color: "var(--error)" }}
          >
            <span>{message}</span>
            <button onClick={() => setMessage(null)} className="ml-2 opacity-60 hover:opacity-100 text-lg leading-none">&times;</button>
          </div>
        )}

        {/* Selectors */}
        <div className="grid grid-cols-2 gap-3 mb-5">
          <div>
            <label className="block text-[11px] font-semibold uppercase tracking-wider mb-1.5" style={{ color: "var(--text-tertiary)" }}>
              Area
            </label>
            <select
              value={selectedAreaId}
              onChange={(e) => {
                setSelectedAreaId(e.target.value);
                setSelectedStationId("");
                setPrograms([]);
              }}
              className="w-full rounded-xl px-3 py-2.5 text-sm font-medium transition-all"
              style={{
                background: "var(--bg-card)",
                border: "1px solid var(--border-color)",
                color: "var(--text-primary)",
              }}
            >
              {AREA_LIST.map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-[11px] font-semibold uppercase tracking-wider mb-1.5" style={{ color: "var(--text-tertiary)" }}>
              Station
            </label>
            <select
              value={selectedStationId}
              onChange={(e) => setSelectedStationId(e.target.value)}
              className="w-full rounded-xl px-3 py-2.5 text-sm font-medium transition-all"
              style={{
                background: "var(--bg-card)",
                border: "1px solid var(--border-color)",
                color: selectedStationId ? "var(--text-primary)" : "var(--text-tertiary)",
              }}
            >
              <option value="">Select</option>
              {stations.map((s) => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          </div>
        </div>

        {/* Date pills */}
        <div className="mb-4">
          <div className="flex gap-2 overflow-x-auto no-scrollbar pb-1">
            {dates.map((d) => {
              const active = formatDateForApi(d) === formatDateForApi(selectedDate);
              return (
                <button
                  key={d.toISOString()}
                  onClick={() => setSelectedDate(d)}
                  className="flex-shrink-0 px-4 py-2 rounded-full text-sm font-medium transition-all active:scale-95"
                  style={{
                    background: active ? "var(--accent)" : "var(--bg-card)",
                    color: active ? "#fff" : "var(--text-secondary)",
                    border: active ? "none" : "1px solid var(--border-color)",
                    boxShadow: active ? "0 2px 8px rgba(0, 122, 255, 0.25)" : "none",
                  }}
                >
                  {formatDateDisplay(d)}
                </button>
              );
            })}
          </div>
        </div>

        {/* Time filter chips */}
        <div className="mb-6">
          <div className="flex gap-1.5 overflow-x-auto no-scrollbar pb-1">
            {TIME_FILTERS.map((f, i) => {
              const active = i === selectedTimeFilter;
              return (
                <button
                  key={f.label}
                  onClick={() => setSelectedTimeFilter(i)}
                  className="flex-shrink-0 px-3 py-1.5 rounded-full text-xs font-medium transition-all active:scale-95"
                  style={{
                    background: active ? "var(--text-primary)" : "transparent",
                    color: active ? "var(--bg-primary)" : "var(--text-tertiary)",
                    border: active ? "none" : "1px solid var(--border-color)",
                  }}
                >
                  {f.label}{f.sub && <span className="ml-0.5 opacity-60">{f.sub}</span>}
                </button>
              );
            })}
          </div>
        </div>

        {/* Program Grid */}
        {programsLoading ? (
          <div className="flex justify-center py-20">
            <div
              className="size-8 rounded-full border-[3px] border-t-transparent animate-spin"
              style={{ borderColor: "var(--border-color-strong)", borderTopColor: "transparent" }}
            />
          </div>
        ) : !selectedStationId ? (
          <div className="text-center py-20">
            <div className="size-16 rounded-full flex items-center justify-center mx-auto mb-4" style={{ background: "var(--bg-tertiary)" }}>
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6" />
                <line x1="2" y1="20" x2="2.01" y2="20" />
              </svg>
            </div>
            <p className="text-sm font-medium" style={{ color: "var(--text-tertiary)" }}>
              Select a station to browse programs
            </p>
          </div>
        ) : filteredPrograms.length === 0 ? (
          <p className="text-center py-20 text-sm" style={{ color: "var(--text-tertiary)" }}>
            No programs found
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-3">
            {filteredPrograms.map((p) => {
              const past = isProgramPast(p.endTime);
              const color = stationColor(p.stationId);
              return (
                <div
                  key={p.id}
                  className="rounded-2xl overflow-hidden transition-all duration-200"
                  style={{
                    background: "var(--bg-card)",
                    border: "1px solid var(--border-color)",
                    boxShadow: "var(--shadow-sm)",
                  }}
                >
                  <div className="flex">
                    {/* Thumbnail area */}
                    {p.imageUrl ? (
                      <div className="w-24 h-24 flex-shrink-0 relative overflow-hidden">
                        <img
                          src={p.imageUrl}
                          alt=""
                          className="w-full h-full object-cover"
                          loading="lazy"
                        />
                      </div>
                    ) : (
                      <div
                        className="w-24 h-24 flex-shrink-0 flex items-center justify-center"
                        style={{ background: `${color}18` }}
                      >
                        <span className="text-2xl font-bold opacity-30" style={{ color }}>
                          {p.stationId.slice(0, 2)}
                        </span>
                      </div>
                    )}

                    {/* Content */}
                    <div className="flex-1 min-w-0 p-3 flex flex-col justify-between">
                      <div>
                        <h3
                          className="text-[15px] font-semibold leading-tight tracking-tight line-clamp-2"
                          style={{ color: "var(--text-primary)" }}
                        >
                          {p.title || "Untitled"}
                        </h3>
                        {p.performer && (
                          <p className="text-xs mt-1 truncate" style={{ color: "var(--text-tertiary)" }}>
                            {p.performer}
                          </p>
                        )}
                      </div>
                      <div className="flex items-center gap-2 mt-2">
                        <span className="text-[11px] font-medium" style={{ color: "var(--text-tertiary)" }}>
                          {formatTime(p.startTime)}&ndash;{formatTime(p.endTime)}
                        </span>
                        <span
                          className="text-[10px] px-1.5 py-0.5 rounded-md font-medium"
                          style={{ background: "var(--bg-tertiary)", color: "var(--text-tertiary)" }}
                        >
                          {formatDuration(p.startTime, p.endTime)}
                        </span>
                      </div>
                    </div>

                    {/* Download action */}
                    <div className="flex items-center pr-3">
                      {past && auth ? (
                        <DownloadButton program={p} authToken={auth.authToken} />
                      ) : (
                        <div
                          className="size-10 rounded-full flex items-center justify-center"
                          style={{ background: "var(--bg-tertiary)" }}
                        >
                          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" opacity="0.4">
                            <circle cx="12" cy="12" r="10" />
                            <polyline points="12 6 12 12 16 14" />
                          </svg>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
}
