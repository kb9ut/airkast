import { NextRequest, NextResponse } from "next/server";
import { getProgramGuide } from "@/lib/radiko";

/**
 * GET /api/programs?stationId=TBS&date=20260315
 * Returns program guide for the given station and date.
 */
export async function GET(request: NextRequest) {
  const stationId = request.nextUrl.searchParams.get("stationId");
  const date = request.nextUrl.searchParams.get("date");

  if (!stationId || !date) {
    return NextResponse.json({ error: "stationId and date are required" }, { status: 400 });
  }

  try {
    const programs = await getProgramGuide(stationId, date);
    return NextResponse.json(programs);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Failed to fetch programs";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
