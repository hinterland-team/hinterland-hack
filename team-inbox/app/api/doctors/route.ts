import { NextResponse } from "next/server";
import { createDoctor, updateDoctor } from "@/lib/clinic";
import { readJson, route } from "@/lib/http";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

export const GET = route(async () => NextResponse.json(await getStore().listDoctors()));

// { name, spot?, teamsWebhookUrl?, active? } - spot defaults to the name
export const POST = route(async (req) => {
  const doctor = await createDoctor(await readJson(req));
  return NextResponse.json(doctor, { status: 201 });
});

// { id, name?, spot?, teamsWebhookUrl? ("" removes it), active? }
export const PATCH = route(async (req) => NextResponse.json(await updateDoctor(await readJson(req))));
