"use client";

import { Bell, Bug, MapPin, Scroll, SlidersHorizontal } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { href: "/settings/places", label: "Places", icon: MapPin },
  { href: "/settings/notifications", label: "Notifications", icon: Bell },
  { href: "/settings/robot", label: "Robot", icon: SlidersHorizontal },
  { href: "/settings/demo", label: "Demo script", icon: Scroll },
  { href: "/settings/debug", label: "Debug", icon: Bug },
];

export function SettingsTabs() {
  const path = usePathname();
  return (
    <nav aria-label="Settings" className="grid grid-cols-2 gap-1 border-b border-line pb-2 sm:flex sm:gap-2 sm:pb-0">
      {TABS.map((t, i) => {
        const active = path.startsWith(t.href);
        const Icon = t.icon;
        // phones: two tabs per row, the last one gets the whole row
        const wide = i === TABS.length - 1 && TABS.length % 2 === 1 ? "col-span-2 sm:col-span-1" : "";
        return (
          <Link
            key={t.href}
            href={t.href}
            aria-current={active ? "page" : undefined}
            className={`${wide} flex h-11 items-center justify-center gap-2 rounded-xl px-4 text-[15px] font-semibold sm:-mb-px sm:rounded-none sm:rounded-t-xl sm:border-b-2 ${
              active
                ? "bg-primary-soft text-primary-ink sm:border-primary sm:bg-transparent"
                : "text-muted hover:bg-subtle hover:text-ink sm:border-transparent"
            }`}
          >
            <Icon size={18} weight={active ? "fill" : "regular"} aria-hidden />
            {t.label}
          </Link>
        );
      })}
    </nav>
  );
}
