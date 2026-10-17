import { PAGE } from "@/lib/ui";
import { SettingsTabs } from "./settings-tabs";

// one place for setting things up: where otto walks, who hears about it, otto's own settings and what it reports
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className={`${PAGE} pb-16`}>
      <div className="pt-6 pb-4">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Settings</h1>
        <p className="mt-1 max-w-prose text-[15px] text-muted">Where Otto walks, who hears about patients, and Otto&apos;s own settings.</p>
      </div>
      <SettingsTabs />
      <div className="pt-6">{children}</div>
    </main>
  );
}
