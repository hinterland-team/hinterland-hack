import type { Metadata } from "next";
import { DemoScriptClient } from "./script-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Demo script - Settings - Otto Clinic" };

export default function DemoScriptPage() {
  return <DemoScriptClient />;
}
