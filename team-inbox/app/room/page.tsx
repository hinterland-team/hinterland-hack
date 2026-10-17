import type { Metadata } from "next";
import { RoomPicker } from "./room-picker";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Rooms - Nova Clinic" };

export default function RoomsPage() {
  return <RoomPicker />;
}
