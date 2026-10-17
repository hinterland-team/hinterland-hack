import type { Metadata } from "next";
import { MapClient } from "./map-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Map - Otto Clinic" };

export default function MapPage() {
  return <MapClient />;
}
