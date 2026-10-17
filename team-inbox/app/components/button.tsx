import type { ButtonHTMLAttributes } from "react";

const KINDS = {
  primary: "bg-primary text-on-primary hover:bg-primary-hover",
  secondary: "border border-line-strong bg-surface text-ink hover:bg-subtle",
  ghost: "text-muted hover:bg-danger-soft hover:text-danger",
  danger: "bg-danger text-on-primary hover:opacity-90",
};

export function Button({
  kind = "secondary",
  className = "",
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { kind?: keyof typeof KINDS }) {
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${KINDS[kind]} ${className}`}
      {...props}
    />
  );
}

export function Dot({ className = "", pulse = false }: { className?: string; pulse?: boolean }) {
  return <span aria-hidden className={`inline-block size-2 shrink-0 rounded-full ${pulse ? "pulse" : ""} ${className}`} />;
}
