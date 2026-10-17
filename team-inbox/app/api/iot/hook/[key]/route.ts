import { NextResponse } from "next/server";
import { checkIn } from "@/lib/clinic";
import { baseUrl, text } from "@/lib/http";
import { recordIot } from "@/lib/iot-log";

export const dynamic = "force-dynamic";

// target for the portal's IoT Device Center ("API" voice command). it can't send a bearer
// header, so the secret is in the path (IOT_KEY). everything that arrives is logged,
// and if it names a doctor it becomes a check-in
async function handle(req: Request, { params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  const expected = process.env.IOT_KEY;
  if (!expected || key !== expected) return NextResponse.json({ error: "unknown hook" }, { status: 404 });

  const url = new URL(req.url);
  const raw = req.method === "GET" ? "" : (await req.text()).slice(0, 4000);
  let body: Record<string, unknown> = {};
  try {
    const parsed = raw ? JSON.parse(raw) : {};
    if (parsed && typeof parsed === "object") body = parsed as Record<string, unknown>;
  } catch {
    // not json - keep the raw text in the log
  }
  for (const [k, v] of url.searchParams) if (!(k in body)) body[k] = v;

  const entry = recordIot({
    method: req.method,
    query: url.search,
    contentType: req.headers.get("content-type") ?? undefined,
    userAgent: req.headers.get("user-agent") ?? undefined,
    from: req.headers.get("x-forwarded-for") ?? undefined,
    raw,
  });

  const doctor = text(body.doctor, 120);
  if (!doctor) return NextResponse.json({ ok: true, logged: entry.id });

  const result = await checkIn(
    {
      patientName: text(body.patientName, 120) ?? text(body.name, 120) ?? "Patient at reception",
      doctor,
      appointmentTime: text(body.appointmentTime, 20) ?? text(body.time, 20),
      language: text(body.language, 20),
      robot: text(body.robot, 40) ?? "nova",
    },
    baseUrl(req),
  );
  return NextResponse.json({ ok: true, logged: entry.id, visit: result.visit.id, notified: result.notified });
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
