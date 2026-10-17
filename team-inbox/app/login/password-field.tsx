"use client";

import { Eye, EyeSlash } from "@phosphor-icons/react";
import { useState } from "react";

export function PasswordField({ invalid }: { invalid: boolean }) {
  const [show, setShow] = useState(false);
  return (
    <div>
      <label htmlFor="password" className="text-sm font-semibold">
        Password
      </label>
      <div className="relative mt-2">
        <input
          id="password"
          name="password"
          type={show ? "text" : "password"}
          required
          autoFocus
          autoComplete="current-password"
          aria-invalid={invalid || undefined}
          className={`h-14 w-full rounded-xl border bg-surface pr-14 pl-4 text-lg focus:border-primary focus:outline-none ${
            invalid ? "border-danger" : "border-line-strong"
          }`}
        />
        <button
          type="button"
          onClick={() => setShow(!show)}
          aria-label={show ? "Hide password" : "Show password"}
          aria-pressed={show}
          className="absolute top-1/2 right-2 flex size-11 -translate-y-1/2 items-center justify-center rounded-lg text-muted hover:bg-subtle hover:text-ink"
        >
          {show ? <EyeSlash size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
        </button>
      </div>
    </div>
  );
}
