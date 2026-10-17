import type { Metadata } from "next";
import { LOG_LEVELS, LOG_SOURCES, type LogLevel, isOneOf } from "@/lib/types";
import { LogsClient } from "./logs-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Logs - Otto Clinic" };

// /logs?level=error&source=nav,voice&q=blocked opens with these filters
export default async function LogsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const p = await searchParams;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";
  const level = one(p.level);
  const sources = one(p.source)
    .split(",")
    .filter((s) => isOneOf(LOG_SOURCES, s));
  return (
    <LogsClient
      initialLevel={isOneOf(LOG_LEVELS, level) ? (level as LogLevel) : "debug"}
      initialSources={sources}
      initialQuery={one(p.q).slice(0, 100)}
    />
  );
}
