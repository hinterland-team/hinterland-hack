import { NextResponse } from "next/server";
import { iotLog } from "@/lib/iot-log";

export const dynamic = "force-dynamic";

// dashboard: what the IoT hook received
export async function GET() {
  return NextResponse.json(iotLog());
}
