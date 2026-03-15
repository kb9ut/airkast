"use client";

import { useState, useEffect } from "react";
import type { Station, Program, AuthState } from "@/lib/types";
import { formatTime, formatDateForApi, formatDateDisplay, getPastDates, isProgramPast } from "@/lib/types";
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
  { label: "早朝 (5-9)", start: 5, end: 9 },
  { label: "午前 (9-12)", start: 9, end: 12 },
  { label: "午後 (12-17)", start: 12, end: 17 },
  { label: "夜 (17-21)", start: 17, end: 21 },
  { label: "深夜 (21-29)", start: 21, end: 29 },
  { label: "全て", start: 0, end: 29 },
];

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

  // Auth on mount
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch("/api/auth", { method: "POST" });
        const data = await res.json();
        if (data.error) throw new Error(data.error);
        setAuth(data);
        setSelectedAreaId(data.areaId);
      } catch (e) {
        setAuthError(e instanceof Error ? e.message : "認証に失敗しました");
      } finally {
        setAuthLoading(false);
      }
    })();
  }, []);

  // Fetch stations when area changes
  useEffect(() => {
    if (!selectedAreaId) return;
    (async () => {
      try {
        const res = await fetch(`/api/stations?areaId=${selectedAreaId}`);
        const data = await res.json();
        if (data.error) throw new Error(data.error);
        setStations(data);
      } catch (e) {
        setMessage(e instanceof Error ? e.message : "放送局の取得に失敗");
      }
    })();
  }, [selectedAreaId]);

  // Fetch programs when station or date changes
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
        setMessage(e instanceof Error ? e.message : "番組表の取得に失敗");
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

  if (authLoading) {
    return (
      <div className="min-h-screen bg-gray-950 text-white flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
          <p className="text-gray-400">Radikoに認証中...</p>
        </div>
      </div>
    );
  }

  if (authError) {
    return (
      <div className="min-h-screen bg-gray-950 text-white flex items-center justify-center">
        <div className="text-center">
          <p className="text-red-400 text-lg mb-2">認証エラー</p>
          <p className="text-gray-400 text-sm">{authError}</p>
          <button
            onClick={() => window.location.reload()}
            className="mt-4 px-4 py-2 bg-blue-600 rounded hover:bg-blue-500"
          >
            再試行
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-950 text-white">
      {/* Header */}
      <header className="bg-gray-900 border-b border-gray-800 p-4">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <h1 className="text-xl font-bold">Airkast Web</h1>
          <span className="text-xs text-gray-500">
            エリア: {AREA_LIST.find((a) => a.id === selectedAreaId)?.name || selectedAreaId}
          </span>
        </div>
      </header>

      <main className="max-w-4xl mx-auto p-4">
        {/* Message */}
        {message && (
          <div className="mb-4 p-3 bg-yellow-900/50 border border-yellow-700 rounded text-sm text-yellow-200">
            {message}
            <button onClick={() => setMessage(null)} className="ml-2 text-yellow-400">
              &times;
            </button>
          </div>
        )}

        {/* Area Selector */}
        <div className="mb-4">
          <label className="block text-xs text-gray-500 mb-1">エリア</label>
          <select
            value={selectedAreaId}
            onChange={(e) => {
              setSelectedAreaId(e.target.value);
              setSelectedStationId("");
              setPrograms([]);
            }}
            className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"
          >
            {AREA_LIST.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </div>

        {/* Station Selector */}
        <div className="mb-4">
          <label className="block text-xs text-gray-500 mb-1">放送局</label>
          <select
            value={selectedStationId}
            onChange={(e) => setSelectedStationId(e.target.value)}
            className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"
          >
            <option value="">-- 放送局を選択 --</option>
            {stations.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </div>

        {/* Date Selector */}
        <div className="mb-4">
          <label className="block text-xs text-gray-500 mb-1">日付</label>
          <div className="flex gap-1 overflow-x-auto">
            {dates.map((d) => (
              <button
                key={d.toISOString()}
                onClick={() => setSelectedDate(d)}
                className={`px-3 py-1 rounded text-sm whitespace-nowrap ${
                  formatDateForApi(d) === formatDateForApi(selectedDate)
                    ? "bg-blue-600 text-white"
                    : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                }`}
              >
                {formatDateDisplay(d)}
              </button>
            ))}
          </div>
        </div>

        {/* Time Filter */}
        <div className="mb-4">
          <label className="block text-xs text-gray-500 mb-1">時間帯</label>
          <div className="flex gap-1 overflow-x-auto">
            {TIME_FILTERS.map((f, i) => (
              <button
                key={f.label}
                onClick={() => setSelectedTimeFilter(i)}
                className={`px-3 py-1 rounded text-xs whitespace-nowrap ${
                  i === selectedTimeFilter
                    ? "bg-blue-600 text-white"
                    : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                }`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {/* Program List */}
        {programsLoading ? (
          <div className="flex justify-center py-12">
            <div className="animate-spin w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full" />
          </div>
        ) : selectedStationId && filteredPrograms.length === 0 ? (
          <p className="text-gray-500 text-center py-8">番組がありません</p>
        ) : (
          <div className="space-y-2">
            {filteredPrograms.map((p) => {
              const past = isProgramPast(p.endTime);
              return (
                <div
                  key={p.id}
                  className="bg-gray-900 border border-gray-800 rounded-lg p-3 hover:border-gray-600 transition-colors"
                >
                  <div className="flex items-start gap-3">
                    <div className="text-xs text-gray-500 w-20 shrink-0 pt-0.5">
                      {formatTime(p.startTime)} - {formatTime(p.endTime)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="font-medium text-sm truncate">{p.title || "(タイトルなし)"}</p>
                      {p.performer && (
                        <p className="text-xs text-gray-400 mt-0.5 truncate">{p.performer}</p>
                      )}
                    </div>
                    {past && auth && (
                      <DownloadButton program={p} authToken={auth.authToken} />
                    )}
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
