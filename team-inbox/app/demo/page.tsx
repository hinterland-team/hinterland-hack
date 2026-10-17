import type { Metadata } from "next";
import { DemoClient } from "./demo-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Demo - Otto Clinic" };

export default function DemoPage() {
  return <DemoClient />;
}
