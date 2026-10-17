import type { Metadata } from "next";
import { homeSpot } from "@/lib/clinic";
import { SimulateClient } from "./simulate-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Simulate - Nova Clinic" };

export default function SimulatePage() {
  return <SimulateClient homeSpot={homeSpot()} />;
}
