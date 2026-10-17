import type { Metadata } from "next";
import { DebugClient } from "./debug-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Debug - Settings - Otto Clinic" };

export default function DebugPage() {
  return <DebugClient />;
}
