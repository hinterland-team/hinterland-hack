import type { Metadata } from "next";
import { RobotSettingsClient } from "./robot-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Robot - Settings - Otto Clinic" };

export default function RobotSettingsPage() {
  return <RobotSettingsClient />;
}
