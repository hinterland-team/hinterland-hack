"use client";

import { ArrowSquareOut, CheckCircle, Copy, Info, LinkSimple, Plugs, WarningCircle } from "@phosphor-icons/react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { ChannelView } from "@/lib/channel-types";
import { encodeQr, qrPath } from "@/lib/qr";
import { LoginNeeded, api, post } from "@/lib/ui";
import { Button } from "../../components/button";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";

export type AppInfo = { clientId: string; secretHint: string; savedAt: string } | null;
export type AppsState = {
  slack: AppInfo;
  discord: AppInfo;
  origin: string;
  https: boolean;
  redirect: { slack: string; discord: string };
};

function errorText(e: unknown, fallback: string) {
  return e instanceof Error ? e.message : fallback;
}

function CopyField({ id, label, value }: { id: string; label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div>
      <label htmlFor={id} className="text-sm font-semibold">
        {label}
      </label>
      <div className="mt-1 flex gap-2">
        <input id={id} readOnly value={value} className={`${INPUT} h-11 font-mono text-sm`} onFocus={(e) => e.currentTarget.select()} />
        <Button
          className="h-11"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            } catch {
              setCopied(false);
            }
          }}
        >
          <Copy size={18} aria-hidden /> {copied ? "Copied" : "Copy"}
        </Button>
      </div>
    </div>
  );
}

const PROVIDER = {
  slack: {
    name: "Slack",
    steps: (redirect: string) => [
      <>
        Open{" "}
        <a href="https://api.slack.com/apps" target="_blank" rel="noreferrer" className="font-semibold text-primary hover:underline">
          api.slack.com/apps
        </a>
        , choose Create New App, then From scratch. Name it Otto Clinic and pick your workspace.
      </>,
      <>Under OAuth &amp; Permissions add the redirect link below, and the bot scope incoming-webhook.</>,
      <>Under Basic Information copy the Client ID and the Client Secret into the fields here.</>,
      redirect,
    ],
  },
  discord: {
    name: "Discord",
    steps: (redirect: string) => [
      <>
        Open the{" "}
        <a href="https://discord.com/developers/applications" target="_blank" rel="noreferrer" className="font-semibold text-primary hover:underline">
          Discord Developer Portal
        </a>
        , choose New Application and name it Otto Clinic.
      </>,
      <>Under OAuth2 add the redirect link below and save.</>,
      <>Copy the Client ID and the Client Secret (Reset Secret shows it once) into the fields here.</>,
      redirect,
    ],
  },
} as const;

// "Connect Slack" / "Connect Discord": the app's client id and secret once, then one click per channel
export function OAuthConnect({
  provider,
  apps,
  isDefault,
  reloadApps,
}: {
  provider: "slack" | "discord";
  apps: AppsState | null;
  isDefault: boolean;
  reloadApps: () => Promise<void>;
}) {
  const info = PROVIDER[provider];
  const app = apps?.[provider] ?? null;
  const [editing, setEditing] = useState(false);
  const [clientId, setClientId] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const redirect = apps?.redirect[provider] ?? "";
  const localhost = /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(apps?.origin ?? "");
  // slack only sends people back to https. discord also takes http://localhost
  const blockedBy =
    apps && !apps.https && (provider === "slack" || !localhost)
      ? provider === "slack"
        ? "Slack only sends people back to an https address, and this page runs on http. Connect works once the dashboard is opened through an https address. Until then, paste a webhook link below."
        : "Discord takes http only for localhost. Open the dashboard on this laptop at http://localhost:3000 to connect, or paste a webhook link below."
      : null;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await post("/api/integrations/apps", { provider, clientId: clientId.trim(), clientSecret: secret.trim() }, "PUT");
      setSecret("");
      setEditing(false);
      await reloadApps();
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not save"));
    }
    setBusy(false);
  };

  const remove = async () => {
    try {
      await api(`/api/integrations/apps?provider=${provider}`, { method: "DELETE" });
      await reloadApps();
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not remove"));
    }
  };

  const showForm = !app || editing;
  return (
    <div className="mt-4 rounded-xl border border-line p-4" aria-label={`Connect ${info.name}`} role="group">
      <p className="flex items-center gap-2 text-[15px] font-semibold">
        <Plugs size={20} aria-hidden className="text-primary" /> Connect with one click
      </p>
      {app && !editing ? (
        <>
          <p className="mt-1 text-sm text-muted">
            {info.name} opens, you pick the channel there and come back here. Nothing to copy.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {blockedBy ? (
              <Button kind="primary" size="lg" disabled>
                Connect {info.name}
              </Button>
            ) : (
              <a
                href={`/api/oauth/${provider}/start?default=${isDefault ? 1 : 0}`}
                className="press inline-flex h-14 items-center gap-2 rounded-xl bg-primary px-5 font-semibold text-on-primary hover:bg-primary-hover"
              >
                Connect {info.name} <ArrowSquareOut size={18} aria-hidden />
              </a>
            )}
            <span className="text-sm text-muted">
              Your {info.name} app: client ID <span className="font-mono">{app.clientId.length > 14 ? `${app.clientId.slice(0, 6)}…${app.clientId.slice(-4)}` : app.clientId}</span>, secret {app.secretHint}
            </span>
            <Button kind="ghost" size="sm" onClick={() => (setEditing(true), setClientId(app.clientId))}>
              Change
            </Button>
            <Button kind="ghost" size="sm" onClick={remove}>
              Remove
            </Button>
          </div>
        </>
      ) : (
        <p className="mt-1 text-sm text-muted">Set up once, about 5 minutes. After that every new channel is one click.</p>
      )}
      {showForm && (
        <>
          <ol className="mt-3 grid list-decimal gap-1 pl-5 text-[15px] text-muted">
            {info.steps(redirect).slice(0, 3).map((s, i) => (
              <li key={i} className="break-words">
                {s}
              </li>
            ))}
          </ol>
          <div className="mt-3">
            <CopyField id={`redirect-${provider}`} label="Redirect link for your app" value={redirect} />
          </div>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <div>
              <label htmlFor={`cid-${provider}`} className="text-sm font-semibold">
                Client ID
              </label>
              <input id={`cid-${provider}`} className={`${INPUT} mt-1 font-mono text-[15px]`} value={clientId} onChange={(e) => setClientId(e.target.value)} autoComplete="off" spellCheck={false} />
            </div>
            <div>
              <label htmlFor={`csecret-${provider}`} className="text-sm font-semibold">
                Client Secret
              </label>
              <input
                id={`csecret-${provider}`}
                className={`${INPUT} mt-1 font-mono text-[15px]`}
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                autoComplete="off"
                spellCheck={false}
                placeholder={app ? "Saved. Type a new one to replace it" : ""}
              />
            </div>
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button kind="primary" onClick={save} disabled={busy || !clientId.trim() || (!app && !secret.trim())}>
              {busy ? "Saving" : "Save app"}
            </Button>
            {editing && <Button onClick={() => setEditing(false)}>Cancel</Button>}
          </div>
          <p className="mt-2 text-sm text-muted">The secret stays on this server. The page only shows its last 4 characters.</p>
        </>
      )}
      {blockedBy && (
        <p className="mt-3 flex items-start gap-2 rounded-xl bg-warn-soft px-3 py-2.5 text-sm text-warn">
          <Info size={18} aria-hidden className="mt-px shrink-0" />
          {blockedBy}
        </p>
      )}
      {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}
    </div>
  );
}

export function QrCode({ text, label, size = 180 }: { text: string; label: string; size?: number }) {
  const qr = useMemo(() => {
    const code = encodeQr(text);
    return code ? qrPath(code) : null;
  }, [text]);
  if (!qr) return null;
  return (
    <svg viewBox={qr.viewBox} width={size} height={size} role="img" aria-label={label} className="shrink-0 rounded-lg" shapeRendering="crispEdges">
      <rect width="100%" height="100%" fill="#ffffff" />
      <path d={qr.d} fill="#0f1b2d" />
    </svg>
  );
}

type TgLink = {
  code: string;
  status: "waiting" | "connected" | "expired" | "error";
  bot: string;
  url: string;
  groupUrl: string;
  expiresAt: string;
  error?: string;
  channel?: ChannelView;
};

// "Connect Telegram": the bot token once, then a link and qr code with a one-time code. pressing Start in telegram
// connects that chat (or a group, with the second link)
export function TelegramConnect({ isDefault, onConnected }: { isDefault: boolean; onConnected: (c: ChannelView) => Promise<void> }) {
  const [token, setToken] = useState("");
  const [link, setLink] = useState<TgLink | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const done = useRef(false);

  useEffect(() => {
    if (!link || link.status !== "waiting") return;
    const t = setInterval(async () => {
      try {
        const next = await api<TgLink>(`/api/integrations/telegram?code=${encodeURIComponent(link.code)}`);
        setLink(next);
        if (next.status === "connected" && next.channel && !done.current) {
          done.current = true;
          await onConnected(next.channel);
        }
      } catch (e) {
        if (!(e instanceof LoginNeeded)) setError(errorText(e, "Lost the link"));
      }
    }, 2000);
    return () => clearInterval(t);
  }, [link, onConnected]);

  const start = async () => {
    setBusy(true);
    setError(null);
    done.current = false;
    try {
      setLink(await post<TgLink>("/api/integrations/telegram", { botToken: token.trim(), isDefault }));
      setToken("");
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not make the link"));
    }
    setBusy(false);
  };

  return (
    <div className="mt-4 rounded-xl border border-line p-4" role="group" aria-label="Connect Telegram">
      <p className="flex items-center gap-2 text-[15px] font-semibold">
        <Plugs size={20} aria-hidden className="text-primary" /> Connect with a link
      </p>
      {!link || link.status === "expired" || link.status === "error" ? (
        <>
          <ol className="mt-2 grid list-decimal gap-1 pl-5 text-[15px] text-muted">
            <li>In Telegram send /newbot to @BotFather and copy the token it answers with.</li>
            <li>Paste it here. You get a link and a QR code: open it on the phone that should get the messages and press Start.</li>
          </ol>
          <label htmlFor="tg-connect-token" className="mt-3 block text-sm font-semibold">
            Bot token
          </label>
          <div className="mt-1 flex flex-col gap-2 sm:flex-row">
            <input
              id="tg-connect-token"
              className={`${INPUT} font-mono text-[15px] placeholder:font-sans`}
              value={token}
              onChange={(e) => setToken(e.target.value)}
              autoComplete="off"
              spellCheck={false}
              placeholder="123456789:AA"
            />
            <Button kind="primary" className="h-12" onClick={start} disabled={busy || !token.trim()}>
              <LinkSimple size={18} aria-hidden /> {busy ? "Checking the bot" : "Get the link"}
            </Button>
          </div>
          {link?.status === "expired" && <p className="mt-2 text-sm text-muted">The last link ran out. Make a new one.</p>}
          {link?.status === "error" && (
            <p className="mt-2 flex items-start gap-2 text-[15px] font-medium text-danger">
              <WarningCircle size={18} weight="fill" aria-hidden className="mt-0.5 shrink-0" /> {link.error}
            </p>
          )}
        </>
      ) : link.status === "connected" ? (
        <p className="mt-2 flex items-start gap-2 rounded-xl bg-ok-soft px-3 py-2.5 text-[15px]" role="status">
          <CheckCircle size={20} weight="fill" aria-hidden className="mt-px shrink-0 text-ok" />
          Connected: {link.channel?.label ?? "the chat"}. Otto Clinic sent a hello there.
        </p>
      ) : (
        <div className="mt-3 flex flex-wrap items-start gap-4">
          <QrCode text={link.url} label={`QR code: open @${link.bot} in Telegram`} />
          <div className="min-w-0 flex-1 basis-56">
            <p className="text-[15px]">
              Scan it with the phone that should get the messages, or open the link there, then press <strong>Start</strong>.
            </p>
            <a href={link.url} target="_blank" rel="noreferrer" className="mt-2 inline-flex h-11 items-center gap-2 rounded-xl bg-primary px-4 font-semibold text-on-primary hover:bg-primary-hover">
              Open @{link.bot} <ArrowSquareOut size={18} aria-hidden />
            </a>
            <p className="mt-3 text-sm text-muted">
              For a team group:{" "}
              <a href={link.groupUrl} target="_blank" rel="noreferrer" className="font-semibold text-primary hover:underline">
                add the bot to a group
              </a>{" "}
              with this link instead.
            </p>
            <p className="mt-3 flex items-center gap-2 text-sm text-muted" role="status">
              <span className="live-dot inline-block size-2.5 rounded-full bg-primary" aria-hidden /> Waiting for Start. The link works for 10 minutes, once.
            </p>
          </div>
        </div>
      )}
      {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}
    </div>
  );
}
