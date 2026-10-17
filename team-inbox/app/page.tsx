import { homeSpot } from "@/lib/clinic";
import { Board } from "./board";

export const dynamic = "force-dynamic";

export default function Home() {
  return <Board practiceChannel={Boolean(process.env.TEAMS_WEBHOOK_URL)} homeSpot={homeSpot()} />;
}
