import type { Metadata } from "next";
import { RoomView } from "./room-view";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Room view - Otto Clinic" };

export default async function RoomPage({ params }: { params: Promise<{ placeId: string }> }) {
  const { placeId } = await params;
  return <RoomView placeId={placeId} />;
}
