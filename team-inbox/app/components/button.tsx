"use client";

import type { ComponentProps, ReactNode } from "react";

const KINDS = {
  primary: "bg-primary text-on-primary hover:bg-primary-hover",
  secondary: "border border-line-strong bg-surface text-ink hover:bg-subtle",
  soft: "bg-primary-soft text-primary-ink hover:bg-primary-soft/70",
  ghost: "text-muted hover:bg-subtle hover:text-ink",
  danger: "bg-danger text-on-primary hover:opacity-90",
  quiet: "text-muted hover:bg-danger-soft hover:text-danger",
};

const SIZES = {
  sm: "h-9 rounded-xl px-3 text-sm",
  md: "h-11 rounded-xl px-4 text-[15px]",
  lg: "h-14 rounded-xl px-5 text-base",
  xl: "h-16 rounded-2xl px-6 text-lg",
};

export function Button({
  kind = "secondary",
  size = "md",
  className = "",
  type = "button",
  ...props
}: ComponentProps<"button"> & { kind?: keyof typeof KINDS; size?: keyof typeof SIZES }) {
  return (
    <button
      type={type}
      className={`press inline-flex shrink-0 items-center justify-center gap-2 font-semibold whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-45 ${SIZES[size]} ${KINDS[kind]} ${className}`}
      {...props}
    />
  );
}

// a row of choices where exactly one is on. arrow keys move like a radio group
export function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
  size = "md",
  hint,
}: {
  label: string;
  value: T;
  options: { value: T; label: string; key?: string }[];
  onChange: (value: T) => void;
  size?: "md" | "lg";
  hint?: boolean;
}) {
  const move = (dir: number) => {
    const i = options.findIndex((o) => o.value === value);
    const next = options[(i + dir + options.length) % options.length];
    onChange(next.value);
  };
  return (
    <div
      role="radiogroup"
      aria-label={label}
      className="grid gap-1 rounded-xl border border-line bg-subtle p-1"
      style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}
      onKeyDown={(e) => {
        if (e.key === "ArrowRight" || e.key === "ArrowDown") {
          e.preventDefault();
          move(1);
        } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
          e.preventDefault();
          move(-1);
        }
      }}
    >
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(o.value)}
            className={`press flex min-w-0 items-center justify-center gap-2 rounded-lg px-2 font-semibold ${
              size === "lg" ? "h-12 text-base" : "h-10 text-sm"
            } ${on ? "bg-surface text-ink shadow-panel" : "text-muted hover:text-ink"}`}
          >
            <span className="truncate">{o.label}</span>
            {hint && o.key && (
              <kbd
                className={`hidden rounded-md border px-1.5 font-mono text-xs leading-5 font-medium sm:inline ${
                  on ? "border-line-strong text-muted" : "border-line text-muted"
                }`}
              >
                {o.key}
              </kbd>
            )}
          </button>
        );
      })}
    </div>
  );
}

export function Switch({
  checked,
  onChange,
  label,
  description,
  icon,
  disabled,
  id,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
  id: string;
}) {
  return (
    <label
      htmlFor={id}
      className={`flex min-h-12 cursor-pointer items-center gap-3 py-2 ${disabled ? "cursor-not-allowed opacity-50" : ""}`}
    >
      {icon && <span className="text-muted">{icon}</span>}
      <span className="min-w-0 flex-1">
        <span className="block text-[15px] font-semibold">{label}</span>
        {description && <span className="block text-sm text-muted">{description}</span>}
      </span>
      <span className="relative inline-flex shrink-0">
        <input
          id={id}
          type="checkbox"
          role="switch"
          className="peer sr-only"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span className="h-8 w-14 rounded-full border border-line-strong bg-subtle transition-colors peer-checked:border-primary peer-checked:bg-primary peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-primary" />
        <span className="pointer-events-none absolute top-1 left-1 size-6 rounded-full bg-surface shadow-panel transition-transform peer-checked:translate-x-6" />
      </span>
    </label>
  );
}

export function Dot({ className = "", live = false }: { className?: string; live?: boolean }) {
  return <span aria-hidden className={`inline-block size-2.5 shrink-0 rounded-full ${live ? "live-dot" : ""} ${className}`} />;
}

export function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="rounded-md border border-line-strong bg-surface px-1.5 font-mono text-xs leading-5 font-medium text-muted">
      {children}
    </kbd>
  );
}
