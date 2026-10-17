import type { Metadata } from "next";
import { DoctorsClient } from "./doctors-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Doctors - Nova Clinic" };

export default function DoctorsPage() {
  return <DoctorsClient practiceChannel={Boolean(process.env.TEAMS_WEBHOOK_URL)} />;
}
