"use client";

import { Door, MapPin, Robot, SignOut, UsersThree } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { PAGE } from "@/lib/ui";
import { Mascot } from "./mascot";

const LINKS = [
  { href: "/", label: "Reception", icon: UsersThree },
  { href: "/room", label: "Rooms", icon: Door },
  { href: "/places", label: "Places", icon: MapPin },
  { href: "/simulate", label: "Simulate", icon: Robot },
];

export function SiteHeader({ signOut }: { signOut: boolean }) {
  const path = usePathname();
  const login = path === "/login";
  return (
    <header className="border-b border-line bg-surface">
      <div className={`${PAGE} flex flex-wrap items-center gap-x-8 gap-y-2 py-3`}>
        <Link href="/" className="flex min-h-11 items-center gap-3 rounded-xl" aria-label="Nova Clinic, reception">
          <Mascot size={36} />
          <span className="leading-tight">
            <span className="block text-[17px] font-semibold tracking-tight">Nova Clinic</span>
            <span className="block text-xs text-muted">Robot guide for patients</span>
          </span>
        </Link>
        {!login && (
          <nav className="order-last -mx-1 flex w-full gap-1 sm:order-none sm:mx-0 sm:w-auto" aria-label="Main">
            {LINKS.map((l) => {
              const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
              const Icon = l.icon;
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  aria-current={active ? "page" : undefined}
                  className={`flex h-10 flex-1 items-center justify-center gap-2 rounded-xl px-3 text-sm font-semibold sm:flex-none ${
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
