import { ApiError, baseUrl, jsonEtag, json, readJson, requireStaff, route } from "@/lib/http";
import { patientAction, simOff, simOn, simView, startDemo, stopDemo } from "@/lib/sim";

export const dynamic = "force-dynamic";

// staff, /simulate: virtual otto (robot id otto-sim). it only takes otto-sim's jobs, never the real robot's
export const GET = route(async (req) => {
  await requireStaff(req);
  return jsonEtag(req, await simView());
});

// { action: "on" | "off" | "demo" | "stop" | "patient", what?: fine | behind | blocked | slower }
export const POST = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  const base = baseUrl(req);
  switch (body.action) {
    case "on":
      await simOn(base);
      break;
    case "off":
      await simOff();
      break;
    case "demo":
      await startDemo(base);
      break;
    case "stop":
      await stopDemo();
      break;
    case "patient":
      patientAction(body.what);
      break;
    default:
      throw new ApiError(400, "action must be on, off, demo, stop or patient");
  }
  return json(await simView());
});
