// "Connect Telegram": the bot token once, then a link (and a qr code) to the bot with a one-time code.
// the person presses Start (or adds the bot to a group): telegram sends the bot "/start <code>", the server reads it
// by long-polling getUpdates, learns the chat and adds it as a channel. the token never leaves the server
import { createChannel } from "./channels";
import type { ChannelView } from "./channel-types";
import { ApiError } from "./http";
import { getStore } from "./store";

export const LINK_MINUTES = 10;
const TOKEN = /^\d{3,20}:[A-Za-z0-9_-]{20,100}$/;
const START = /^\/start(?:@([A-Za-z0-9_]{3,64}))?\s+([A-Za-z0-9_-]{8,64})\s*$/;

type Bot = { username: string; name: string };
type Link = {
  code: string;
  token: string;
  bot: Bot;
  isDefault: boolean;
  expiresAt: number;
  status: "waiting" | "connected" | "expired" | "error";
  error?: string;
  channel?: ChannelView;
};
type Poller = { running: boolean; offset: number };

const g = globalThis as unknown as { __ottoTgLinks?: Map<string, Link>; __ottoTgPollers?: Map<string, Poller> };
const links = () => (g.__ottoTgLinks ??= new Map<string, Link>());
const pollers = () => (g.__ottoTgPollers ??= new Map<string, Poller>());

function apiBase() {
  return (process.env.TELEGRAM_API_URL?.trim() || "https://api.telegram.org").replace(/\/+$/, "");
}

// long poll seconds (tests use 1)
function pollSeconds() {
  const n = Number(process.env.TELEGRAM_POLL_SECONDS);
  return Number.isFinite(n) && n >= 0 && n <= 50 ? Math.round(n) : 20;
}

type Tg = { status: number; ok: boolean; result: unknown; description?: string };

// a telegram bot api call. errors never contain the url (the token is in it)
async function call(token: string, method: string, body: Record<string, unknown>, timeoutMs: number): Promise<Tg> {
  try {
    const res = await fetch(`${apiBase()}/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const json = (await res.json().catch(() => null)) as { ok?: unknown; result?: unknown; description?: unknown } | null;
    return {
      status: res.status,
      ok: json?.ok === true,
      result: json?.result,
      description: typeof json?.description === "string" ? json.description.slice(0, 160) : undefined,
    };
  } catch {
    return { status: 0, ok: false, result: null, description: "Telegram could not be reached. Is the internet on?" };
  }
}

function tokenError(r: Tg) {
  if (r.status === 401 || r.status === 404) return "The bot token is wrong. Copy it again from @BotFather";
  return r.description ?? `Telegram answered ${r.status}`;
}

// the bot's name for the link, and proof that the token works
export async function botInfo(token: string): Promise<Bot> {
  if (!TOKEN.test(token)) throw new ApiError(400, "The bot token looks like 123456789:AAE… (from @BotFather)");
  const r = await call(token, "getMe", {}, 10_000);
  const me = r.result as { username?: unknown; first_name?: unknown } | null;
  if (!r.ok || !me || typeof me.username !== "string") throw new ApiError(400, tokenError(r));
  return { username: me.username, name: typeof me.first_name === "string" ? me.first_name : me.username };
}

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function linkView(l: Link) {
  const expired = l.status === "waiting" && Date.now() > l.expiresAt;
  if (expired) l.status = "expired";
  return {
    code: l.code,
    status: l.status,
    bot: l.bot.username,
    url: `https://t.me/${l.bot.username}?start=${l.code}`,
    groupUrl: `https://t.me/${l.bot.username}?startgroup=${l.code}`,
    expiresAt: new Date(l.expiresAt).toISOString(),
    ...(l.error ? { error: l.error } : {}),
    ...(l.channel ? { channel: l.channel } : {}),
  };
}

// { botToken } or { channelId } (the token of a telegram channel made before), isDefault?
export async function startLink(body: Record<string, unknown>) {
  let token = typeof body.botToken === "string" ? body.botToken.trim() : "";
  if (!token && typeof body.channelId === "string") {
    const c = (await getStore().listChannels()).find((x) => x.id === body.channelId && x.type === "telegram");
    token = c?.config.botToken ?? "";
  }
  if (!token) throw new ApiError(400, "The bot token is needed");
  const bot = await botInfo(token);
  const code = newCode();
  const link: Link = {
    code,
    token,
    bot,
    isDefault: body.isDefault !== false,
    expiresAt: Date.now() + LINK_MINUTES * 60_000,
    status: "waiting",
  };
  links().set(code, link);
  for (const [k, l] of links()) if (Date.now() > l.expiresAt + 60 * 60_000) links().delete(k);
  poll(token).catch(() => undefined);
  return linkView(link);
}

export function linkStatus(code: string) {
  const l = links().get(code);
  if (!l) throw new ApiError(404, "This link is not active any more. Make a new one");
  return linkView(l);
}

export function cancelLink(code: string) {
  const l = links().get(code);
  if (l && l.status === "waiting") l.status = "expired";
  return { ok: true };
}

function waitingFor(token: string) {
  return [...links().values()].filter((l) => l.token === token && l.status === "waiting" && Date.now() <= l.expiresAt);
}

function chatName(chat: Record<string, unknown>) {
  if (typeof chat.title === "string" && chat.title.trim()) return chat.title.trim();
  const name = [chat.first_name, chat.last_name].filter((x): x is string => typeof x === "string" && x.trim().length > 0).join(" ");
  if (name) return name;
  return typeof chat.username === "string" ? `@${chat.username}` : "Telegram chat";
}

async function connect(l: Link, chat: Record<string, unknown>) {
  try {
    l.channel = await createChannel({
      type: "telegram",
      label: chatName(chat),
      config: { botToken: l.token, chatId: String(chat.id) },
      isDefault: l.isDefault,
    });
    l.status = "connected";
    await call(
      l.token,
      "sendMessage",
      { chat_id: chat.id, text: "Connected to Otto Clinic. The team gets messages about patients on the way here: tickets and rooms only, never a name." },
      8_000,
    );
  } catch (err) {
    l.status = "error";
    l.error = err instanceof ApiError ? err.message : "Could not add the chat";
  }
}

// one long-poll loop per bot while someone waits for its link. telegram keeps the updates until we confirm them
async function poll(token: string) {
  const p = pollers().get(token) ?? { running: false, offset: 0 };
  pollers().set(token, p);
  if (p.running) return;
  p.running = true;
  try {
    while (waitingFor(token).length) {
      const wait = pollSeconds();
      const asked = Date.now();
      const r = await call(token, "getUpdates", { offset: p.offset || undefined, timeout: wait, allowed_updates: ["message", "channel_post"] }, (wait + 10) * 1000);
      // telegram holds the request until something comes; an answer right away with nothing in it: a short pause
      if (r.ok && Array.isArray(r.result) && !r.result.length && Date.now() - asked < 300) await new Promise((ok) => setTimeout(ok, 300));
      if (!r.ok) {
        const msg =
          r.status === 409
            ? "Another program reads this bot's messages (a webhook or a second server). Remove the webhook or use a new bot"
            : tokenError(r);
        if (r.status === 409 || r.status === 401 || r.status === 404) {
          for (const l of waitingFor(token)) {
            l.status = "error";
            l.error = msg;
          }
          break;
        }
        await new Promise((ok) => setTimeout(ok, 2000));
        continue;
      }
      for (const u of Array.isArray(r.result) ? (r.result as Record<string, unknown>[]) : []) {
        if (typeof u.update_id === "number") p.offset = Math.max(p.offset, u.update_id + 1);
        const msg = (u.message ?? u.channel_post) as Record<string, unknown> | undefined;
        const m = typeof msg?.text === "string" ? START.exec(msg.text) : null;
        const chat = msg?.chat as Record<string, unknown> | undefined;
        if (!m || !chat || (typeof chat.id !== "number" && typeof chat.id !== "string")) continue;
        const l = links().get(m[2]);
        if (l && l.token === token && l.status === "waiting" && Date.now() <= l.expiresAt) await connect(l, chat);
      }
    }
    // confirm what was read, so telegram does not send it again
    if (p.offset) await call(token, "getUpdates", { offset: p.offset, timeout: 0 }, 8_000);
  } finally {
    p.running = false;
  }
}
