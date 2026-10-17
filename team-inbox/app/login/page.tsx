import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { safeNext } from "@/lib/login";
import { Mascot } from "../components/mascot";
import { PasswordField } from "./password-field";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Staff sign-in - Otto Clinic" };

type Search = Promise<Record<string, string | string[] | undefined>>;

export default async function LoginPage({ searchParams }: { searchParams: Search }) {
  const params = await searchParams;
  const one = (k: string) => (typeof params[k] === "string" ? (params[k] as string) : undefined);
  const next = safeNext(one("next"));
  // nothing to sign in to without a password
  if (!process.env.DASHBOARD_PASSWORD) redirect(next);

  const error = one("error");
  const message =
    error === "wrong"
      ? "That password did not work. Please try again."
      : error === "wait"
        ? `Too many wrong tries. Please wait ${one("m") ?? "10"} minutes, or ask a colleague.`
        : one("out")
          ? "You are signed out."
          : null;

  return (
    <main className="mx-auto flex w-full max-w-md flex-col px-4 pt-10 pb-16 sm:pt-16">
      <div className="rounded-2xl border border-line bg-surface p-6 shadow-panel sm:p-8">
        <Mascot size={64} />
        <h1 className="mt-5 text-2xl font-semibold tracking-tight">Staff sign-in</h1>
        <p className="mt-1 text-[15px] text-muted">
          One shared password for the reception and the rooms. Ask the practice team if you don&apos;t have it.
        </p>

        {message && (
          <p
            role={error ? "alert" : "status"}
            className={`mt-5 rounded-xl px-4 py-3 text-[15px] font-medium ${error ? "bg-danger-soft text-danger" : "bg-ok-soft text-ok"}`}
          >
            {message}
          </p>
        )}

        <form action="/api/login" method="post" className="mt-6">
          <input type="hidden" name="next" value={next} />
          <PasswordField invalid={error === "wrong"} />
          <button
            type="submit"
            className="press mt-5 flex h-14 w-full items-center justify-center rounded-xl bg-primary text-base font-semibold text-on-primary hover:bg-primary-hover"
          >
            Sign in
          </button>
        </form>
        <p className="mt-5 text-sm text-muted">You stay signed in on this device for 7 days. Use Sign out on shared computers.</p>
      </div>
    </main>
  );
}
