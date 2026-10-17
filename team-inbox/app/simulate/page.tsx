import type { Metadata } from "next";
import { SimulateClient } from "./simulate-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Simulate - Otto Clinic" };

export default function SimulatePage() {
  return <SimulateClient />;
}
