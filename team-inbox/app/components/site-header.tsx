"use client";

import { Bell, Door, MapPin, MapTrifold, Robot, SignOut, TerminalWindow, UsersThree } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { PAGE } from "@/lib/ui";
import { Mascot } from "./mascot";

const LINKS = [
  { href: "/", label: "Reception", icon: UsersThree },
  { href: "/room", label: "Rooms", icon: Door },
  { href: "/map", label: "Map", icon: MapTrifold },
  { href: "/logs", label: "Logs", icon: TerminalWindow },
  { href: "/places", label: "Places", icon: MapPin },
  { href: "/notifications", label: "Notifications", icon: Bell },
  { href: "/simulate", label: "Simulate", icon: Robot },
];

export function SiteHeader({ signOut }: { signOut: boolean }) {
  const path = usePathname();
  // the patient's phone page has no staff header at all
  if (path.startsWith("/walk/")) return null;
  const login = path === "/login";
  return (
    <header className="border-b border-line bg-surface">
      <div className={`${PAGE} flex flex-wrap items-center gap-x-6 gap-y-2 py-3`}>
        <Link href="/" className="flex min-h-11 items-center gap-3 rounded-xl" aria-label="Nova Clinic, reception">
          <Mascot size={36} />
          <span className="leading-tight">
            <span className="block text-[17px] font-semibold tracking-tight">Nova Clinic</span>
            <span className="block text-xs text-muted">Robot guide for patients</span>
          </span>
        </Link>
        {!login && (
          <nav
            className="order-last -mx-1 grid w-full grid-cols-3 gap-1 lg:order-none lg:mx-0 lg:flex lg:w-auto"
            aria-label="Main"
          >
            {LINKS.map((l) => {
              const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
              const Icon = l.icon;
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  aria-current={active ? "page" : undefined}
                  className={`flex h-11 items-center justify-center gap-2 rounded-xl px-3 text-sm font-semibold lg:h-10 [&:last-child:nth-child(3n+1)]:col-span-3 ${
                    active ? "bg-primary-soft text-primary-ink" : "text-muted hover:bg-subtle hover:text-ink"
                  }`}
                >
                  <Icon size={18} weight={active ? "fill" : "regular"} aria-hidden className="hidden min-[400px]:block" />
                  {l.label}
                </Link>
              );
            })}
          </nav>
        )}
        {signOut && !login && (
          <form action="/api/logout" method="post" className="ml-auto">
            <button
              type="submit"
              className="flex h-10 items-center gap-2 rounded-xl px-3 text-sm font-semibold text-muted hover:bg-subtle hover:text-ink"
            >
              <SignOut size={18} aria-hidden />
              Sign out
            </button>
          </form>
        )}
      </div>
    </header>
  );
}
