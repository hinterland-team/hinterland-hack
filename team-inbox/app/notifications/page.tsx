import type { Metadata } from "next";
import { NotificationsClient } from "./notifications-client";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Notifications - Nova Clinic" };

export default function NotificationsPage() {
  return <NotificationsClient />;
}
