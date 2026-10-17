import type { Metadata } from "next";
import { RoomPicker } from "./room-picker";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Rooms - Otto Clinic" };

export default function RoomsPage() {
  return <RoomPicker />;
}
