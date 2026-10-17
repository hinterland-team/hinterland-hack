import type { Metadata } from "next";
import { PlacesClient } from "./places-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Places - Nova Clinic" };

export default function PlacesPage() {
  return <PlacesClient practiceChannel={Boolean(process.env.TEAMS_WEBHOOK_URL)} />;
}
