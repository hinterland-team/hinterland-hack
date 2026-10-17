import type { Metadata } from "next";
import { WalkClient } from "./walk-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Your walk with Otto",
  description: "Wait, slower, faster or talk to Otto while it walks you to your room.",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

// public, no login: the patient opens it from the qr code on otto's pickup screen
export default async function WalkPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <WalkClient code={code.slice(0, 16)} />;
}
