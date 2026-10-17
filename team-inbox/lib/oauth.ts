// "Connect Slack" / "Connect Discord": the team saves its own app's client id and secret once (Settings ->
// Notifications), then one click sends the browser to Slack or Discord, the team picks the channel there, and
// the server swaps the code for an incoming webhook and adds it as a channel. secrets stay on the server.
// slack: scope incoming-webhook (oauth.v2.access answers incoming_webhook.url). discord: scope webhook.incoming
// (the token answer has webhook.url / id + token)
import { createChannel } from "./channels";
import type { ChannelView } from "./channel-types";
import { ApiError, text } from "./http";
import { getStore } from "./store";

export const OAUTH_PROVIDERS = ["slack", "discord"] as const;
export type OAuthProvider = (typeof OAUTH_PROVIDERS)[number];
export const OAUTH_COOKIE = "otto_oauth";
const STATE_MINUTES = 10;

type App = { clientId: string; clientSecret: string; savedAt: string };
export type AppView = { clientId: string; secretHint: string; savedAt: string } | null;

const appKey = (p: OAuthProvider) => `oauth:app:${p}`;

export function isProvider(value: unknown): value is OAuthProvider {
  return typeof value === "string" && (OAUTH_PROVIDERS as readonly string[]).includes(value);
}

const NAME: Record<OAuthProvider, string> = { slack: "Slack", discord: "Discord" };

function urls(p: OAuthProvider, env: Record<string, string | undefined> = process.env) {
  const v = (k: string) => env[k]?.trim().replace(/\/+$/, "") || undefined;
  return p === "slack"
    ? { authorize: v("SLACK_OAUTH_URL") ?? "https://slack.com/oauth/v2/authorize", api: v("SLACK_API_URL") ?? "https://slack.com/api" }
    : { authorize: v("DISCORD_OAUTH_URL") ?? "https://discord.com/oauth2/authorize", api: v("DISCORD_API_URL") ?? "https://discord.com/api" };
}

async function readApp(p: OAuthProvider): Promise<App | null> {
  const raw = await getStore().getSetting(appKey(p));
  if (!raw) return null;
  try {
    const a = JSON.parse(raw) as Partial<App>;
    return a.clientId && a.clientSecret ? { clientId: a.clientId, clientSecret: a.clientSecret, savedAt: a.savedAt ?? "" } : null;
  } catch {
    return null;
  }
}

function view(a: App | null): AppView {
  if (!a) return null;
  return { clientId: a.clientId, secretHint: a.clientSecret.length >= 12 ? `…${a.clientSecret.slice(-4)}` : "••••", savedAt: a.savedAt };
}

export async function appsView() {
  const [slack, discord] = await Promise.all([readApp("slack"), readApp("discord")]);
  return { slack: view(slack), discord: view(discord) };
}

// { clientId, clientSecret } (an empty secret keeps the saved one)
export async function saveApp(p: OAuthProvider, body: Record<string, unknown>) {
  const before = await readApp(p);
  const clientId = text(body.clientId, 120);
  const secretRaw = typeof body.clientSecret === "string" ? body.clientSecret.trim() : "";
  if (!clientId || !/^[A-Za-z0-9._-]{3,120}$/.test(clientId)) throw new ApiError(400, `The client ID is the number from your ${NAME[p]} app's settings`);
  if (secretRaw && !/^\S{8,200}$/.test(secretRaw)) throw new ApiError(400, "The client secret has no spaces");
  const clientSecret = secretRaw || before?.clientSecret;
  if (!clientSecret) throw new ApiError(400, "The client secret is needed");
  const app: App = { clientId, clientSecret, savedAt: new Date().toISOString() };
  await getStore().setSetting(appKey(p), JSON.stringify(app));
  return view(app);
}

export async function deleteApp(p: OAuthProvider) {
  await getStore().setSetting(appKey(p), "");
  return { ok: true };
}

// ---------- the round trip

type Pending = { provider: OAuthProvider; redirectUri: string; isDefault: boolean; at: number };
const g = globalThis as unknown as { __ottoOAuth?: Map<string, Pending> };
const pending = () => (g.__ottoOAuth ??= new Map<string, Pending>());

function sweep() {
  const cutoff = Date.now() - STATE_MINUTES * 60_000;
  for (const [k, v] of pending()) if (v.at < cutoff) pending().delete(k);
}

function randomState() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function callbackUrl(origin: string, p: OAuthProvider) {
  return `${origin}/api/oauth/${p}/callback`;
}

// -> where to send the browser, and the state for this browser's cookie
export async function startOAuth(p: OAuthProvider, origin: string, isDefault: boolean) {
  const app = await readApp(p);
  if (!app) throw new ApiError(409, `Save your ${NAME[p]} app's client ID and secret first`);
  sweep();
  const state = randomState();
  const redirectUri = callbackUrl(origin, p);
  pending().set(state, { provider: p, redirectUri, isDefault, at: Date.now() });
  const u = new URL(urls(p).authorize);
  u.searchParams.set("client_id", app.clientId);
  u.searchParams.set("redirect_uri", redirectUri);
  u.searchParams.set("state", state);
  if (p === "slack") u.searchParams.set("scope", "incoming-webhook");
  else {
    u.searchParams.set("response_type", "code");
    u.searchParams.set("scope", "webhook.incoming");
  }
  return { url: u.href, state };
}

function cancelled(p: OAuthProvider, error: string) {
  return error === "access_denied" ? `${NAME[p]} was not connected: the permission was cancelled` : `${NAME[p]} said: ${error.slice(0, 80)}`;
}

async function postForm(url: string, form: Record<string, string>, headers: Record<string, string> = {}) {
  let host = "the service";
  try {
    host = new URL(url).host;
  } catch {
    // stays generic
  }
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json", ...headers },
      body: new URLSearchParams(form).toString(),
      signal: AbortSignal.timeout(10_000),
    });
    const body = (await res.json().catch(() => null)) as Record<string, unknown> | null;
    return { status: res.status, body };
  } catch {
    throw new ApiError(502, `Could not reach ${host}. Is the internet on?`);
  }
}

// the code from the callback -> the incoming webhook link + a name for the channel
async function exchange(p: OAuthProvider, app: App, code: string, redirectUri: string): Promise<{ url: string; label: string }> {
  const { api } = urls(p);
  if (p === "slack") {
    const { body } = await postForm(`${api}/oauth.v2.access`, {
      client_id: app.clientId,
      client_secret: app.clientSecret,
      code,
      redirect_uri: redirectUri,
    });
    if (!body || body.ok !== true) {
      const why = typeof body?.error === "string" ? body.error : "no answer";
      throw new ApiError(502, why === "bad_redirect_uri" ? "Slack refused the redirect link: add it to your Slack app (OAuth & Permissions)" : `Slack did not connect (${why.slice(0, 60)})`);
    }
    const hook = body.incoming_webhook as { url?: unknown; channel?: unknown } | undefined;
    if (!hook || typeof hook.url !== "string") throw new ApiError(502, "Slack gave no webhook: the app needs the incoming-webhook scope");
    const channel = typeof hook.channel === "string" ? hook.channel : "";
    return { url: hook.url, label: channel ? (channel.startsWith("#") ? channel : `#${channel}`) : "Slack" };
  }
  const { status, body } = await postForm(`${api}/oauth2/token`, {
    grant_type: "authorization_code",
    code,
    redirect_uri: redirectUri,
    client_id: app.clientId,
    client_secret: app.clientSecret,
  });
  const hook = body?.webhook as { url?: unknown; id?: unknown; token?: unknown; name?: unknown } | undefined;
  if (status >= 300 || !hook) {
    const why = typeof body?.error_description === "string" ? body.error_description : typeof body?.error === "string" ? body.error : `answered ${status}`;
    throw new ApiError(502, `Discord did not connect (${String(why).slice(0, 80)})`);
  }
  const url =
    typeof hook.url === "string"
      ? hook.url
      : typeof hook.id === "string" && typeof hook.token === "string"
        ? `https://discord.com/api/webhooks/${hook.id}/${hook.token}`
        : null;
  if (!url) throw new ApiError(502, "Discord gave no webhook");
  return { url, label: typeof hook.name === "string" && hook.name ? hook.name : "Discord" };
}

// the browser is back: check the state (one time, same browser), swap the code, add the channel
export async function finishOAuth(p: OAuthProvider, query: URLSearchParams, cookieState: string | undefined): Promise<ChannelView> {
  const state = query.get("state") ?? "";
  sweep();
  const found = state ? pending().get(state) : undefined;
  if (state) pending().delete(state);
  if (!found || found.provider !== p || !cookieState || cookieState !== state) {
    throw new ApiError(400, "This link is too old or was opened in another browser. Please press Connect again");
  }
  const error = query.get("error");
  if (error) throw new ApiError(400, cancelled(p, error));
  const code = query.get("code");
  if (!code) throw new ApiError(400, `${NAME[p]} sent no code. Please press Connect again`);
  const app = await readApp(p);
  if (!app) throw new ApiError(409, `The ${NAME[p]} app is not saved any more`);
  const hook = await exchange(p, app, code, found.redirectUri);
  return createChannel({ type: p, label: hook.label, config: { url: hook.url }, isDefault: found.isDefault });
}
