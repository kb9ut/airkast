import { NextRequest, NextResponse } from "next/server";
import { getStations } from "@/lib/radiko";

export const maxDuration = 30;

/**
 * GET /api/stations?areaId=JP13
 * Returns station list for the given area.
 */
export async function GET(request: NextRequest) {
  const areaId = request.nextUrl.searchParams.get("areaId");
  if (!areaId) {
    return NextResponse.json({ error: "areaId is required" }, { status: 400 });
  }

  try {
    const stations = await getStations(areaId);
    return NextResponse.json(stations);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Failed to fetch stations";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
