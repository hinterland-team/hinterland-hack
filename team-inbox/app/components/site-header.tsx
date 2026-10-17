"use client";

import { Door, GearSix, MapTrifold, Presentation, Robot, SignOut, TerminalWindow, UsersThree } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { PAGE } from "@/lib/ui";
import { Mascot } from "./mascot";
import { ModeSwitch } from "./robot-mode";

const LINKS = [
  { href: "/", label: "Reception", icon: UsersThree },
  { href: "/room", label: "Rooms", icon: Door },
  { href: "/map", label: "Map", icon: MapTrifold },
  { href: "/logs", label: "Logs", icon: TerminalWindow },
  { href: "/settings", label: "Settings", icon: GearSix },
  { href: "/simulate", label: "Simulate", icon: Robot },
  { href: "/demo", label: "Demo", icon: Presentation },
];

export function SiteHeader({ signOut }: { signOut: boolean }) {
  const path = usePathname();
  // the patient's phone page has no staff header at all
  if (path.startsWith("/walk/")) return null;
  const login = path === "/login";
  return (
    <header className="border-b border-line bg-surface">
      <div className={`${PAGE} flex flex-wrap items-center gap-x-4 gap-y-2 py-3 sm:gap-x-6`}>
        <Link href="/" className="flex min-h-11 items-center gap-3 rounded-xl" aria-label="Otto Clinic, reception">
          <Mascot size={36} />
          {/* narrow phones: the logo alone, so Otto's mode switch fits next to it */}
          <span className="leading-tight max-[439px]:sr-only">
            <span className="block text-[17px] font-semibold tracking-tight">Otto Clinic</span>
            <span className="hidden text-xs text-muted min-[420px]:block">Robot guide for patients</span>
          </span>
        </Link>
        {!login && (
          <nav className="order-last -mx-1 grid w-full grid-cols-4 gap-1 xl:order-none xl:mx-0 xl:flex xl:w-auto" aria-label="Main">
            {LINKS.map((l) => {
              const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
              const Icon = l.icon;
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  aria-current={active ? "page" : undefined}
                  className={`flex h-11 min-w-0 items-center justify-center gap-2 rounded-xl px-1 text-sm font-semibold sm:px-3 xl:h-10 ${
                    active ? "bg-primary-soft text-primary-ink" : "text-muted hover:bg-subtle hover:text-ink"
                  }`}
                >
                  <Icon size={18} weight={active ? "fill" : "regular"} aria-hidden className="hidden shrink-0 sm:block" />
                  {l.label}
                </Link>
              );
            })}
          </nav>
        )}
        {!login && (
          <div className="ml-auto flex items-center gap-1">
            <ModeSwitch />
            {signOut && (
              <form action="/api/logout" method="post">
                <button
                  type="submit"
                  aria-label="Sign out"
                  className="flex h-10 items-center gap-2 rounded-xl px-2.5 text-sm font-semibold text-muted hover:bg-subtle hover:text-ink"
                >
                  <SignOut size={18} aria-hidden />
                  <span className="hidden sm:inline">Sign out</span>
                </button>
              </form>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
