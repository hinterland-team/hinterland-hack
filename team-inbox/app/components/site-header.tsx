"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { PAGE } from "@/lib/ui";
import { Mascot } from "./mascot";

const LINKS = [
  { href: "/", label: "Board" },
  { href: "/doctors", label: "Doctors" },
  { href: "/simulate", label: "Simulate" },
];

export function SiteHeader() {
  const path = usePathname();
  return (
    <header className="border-b border-line bg-surface">
      <div className={`${PAGE} flex flex-wrap items-center gap-x-12 gap-y-3 py-4`}>
        <Link href="/" className="flex items-center gap-3">
          <Mascot size={40} />
          <span>
            <span className="block text-lg font-semibold leading-6">Nova Clinic</span>
            <span className="block text-xs text-muted">Robot reception</span>
          </span>
        </Link>
        <nav className="flex gap-2" aria-label="Main">
          {LINKS.map((l) => {
            const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
            return (
              <Link
                key={l.href}
                href={l.href}
                aria-current={active ? "page" : undefined}
                className={`rounded-lg px-3 py-2 text-sm font-medium ${
                  active ? "bg-primary-soft text-primary-ink" : "text-muted hover:bg-subtle hover:text-ink"
                }`}
              >
                {l.label}
              </Link>
            );
          })}
        </nav>
      </div>
    </header>
  );
}
