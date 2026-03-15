import { NextResponse } from "next/server";
import { authenticate } from "@/lib/radiko";

/**
 * POST /api/auth
 * Performs Radiko auth1 + auth2 and returns the auth token and area ID.
 */
export async function POST() {
  try {
    const session = await authenticate();
    return NextResponse.json({
      authToken: session.authToken,
      areaId: session.areaId,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Authentication failed";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
