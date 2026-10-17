// notification channels as small plugins: each one turns the same message into its own format and posts it.
// send() never throws and never puts a token into its answer: { ok, text } with a short reason.
// no store, no next imports here, so tests can load this file directly
import { CHANNEL_INFO, type ChannelSource, type ChannelType, ENV_TEAMS_ID, type NotifyChannel } from "./channel-types";
import { cardToEmail, emailList } from "./email";
import type { NotifyMessage } from "./messages";
import { teamsCard } from "./teams";
import type { Place } from "./types";

export const DEFAULT_TIMEOUT_MS = 8000;
const RESEND_URL = "https://api.resend.com/emails";
const TELEGRAM_URL = "https://api.telegram.org";
// graph api version of meta's whatsapp cloud api docs (oct 2026)
const WHATSAPP_URL = "https://graph.facebook.com/v25.0";
const USER_AGENT = "NovaClinic/3.3";

export type SendResult = { ok: boolean; text: string; note?: string };

// one place a message goes to, ready to send
export type Target = {
  id: string;
  type: ChannelType;
  label: string;
  source: ChannelSource;
  config: Record<string, string>;
  placeId?: string;
};

export type NotifyEnv = {
  // TEAMS_WEBHOOK_URL, the practice channel
  teamsWebhook?: string;
  // the practice channel is a default channel (switch on /notifications, on unless turned off)
  envTeamsDefault: boolean;
  resendKey?: string;
  emailFrom?: string;
  // only for tests: local mocks instead of the real services
  resendUrl?: string;
  telegramUrl?: string;
  whatsappUrl?: string;
  // per channel, NOTIFY_TIMEOUT_MS
  timeoutMs: number;
};

export function readNotifyEnv(env: Record<string, string | undefined> = process.env, envTeamsDefault = true): NotifyEnv {
  const v = (key: string) => env[key]?.trim() || undefined;
  const ms = Number(v("NOTIFY_TIMEOUT_MS"));
  return {
    teamsWebhook: v("TEAMS_WEBHOOK_URL"),
    envTeamsDefault,
    resendKey: v("RESEND_API_KEY"),
    emailFrom: v("EMAIL_FROM"),
    resendUrl: v("RESEND_API_URL"),
    telegramUrl: v("TELEGRAM_API_URL"),
    whatsappUrl: v("WHATSAPP_API_URL"),
    timeoutMs: Number.isFinite(ms) && ms >= 200 ? Math.min(30000, Math.round(ms)) : DEFAULT_TIMEOUT_MS,
  };
}

export type SendContext = { env: NotifyEnv; deadline: number; fetch: typeof fetch };

// the plugin interface: posts one message with the saved fields of a channel. never throws
export type ChannelPlugin = {
  type: ChannelType;
  send(config: Record<string, string>, message: NotifyMessage, ctx: SendContext): Promise<SendResult>;
};

// ---------- small helpers

const ok = (text: string, note?: string): SendResult => (note ? { ok: true, text, note } : { ok: true, text });
const fail = (text: string): SendResult => ({ ok: false, text });

function cut(s: string, max: number) {
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

function oneLine(s: string, max = 140) {
  return cut(s.replace(/\s+/g, " ").trim(), max);
}

function seconds(ms: number) {
  const s = ms / 1000;
  return `${Number.isInteger(s) ? s : s.toFixed(1)} s`;
}

function htmlEscape(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

const is2xx = (status: number) => status >= 200 && status < 300;

type Answer = { status: number; text: string; json: unknown };

// a json POST with what is left of the channel's time. errors name the host only, never the path (tokens live there)
async function postJson(
  ctx: SendContext,
  url: string,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<Answer | { error: string }> {
  let host: string;
  try {
    host = new URL(url).host;
  } catch {
    return { error: "the link is not valid" };
  }
  const left = ctx.deadline - Date.now();
  if (left <= 0) return { error: `no answer within ${seconds(ctx.env.timeoutMs)}` };
  try {
    const res = await ctx.fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": USER_AGENT, ...headers },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(left),
    });
    const text = (await res.text()).slice(0, 4000);
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      // not json
    }
    return { status: res.status, text, json };
  } catch (err) {
    return { error: networkText(err, host, ctx.env.timeoutMs) };
  }
}

function networkText(err: unknown, host: string, timeoutMs: number) {
  const e = err as { name?: string; cause?: { code?: string; name?: string } } | null;
  if (e?.name === "TimeoutError" || e?.name === "AbortError" || e?.cause?.name === "TimeoutError") {
    return `no answer within ${seconds(timeoutMs)}`;
  }
  const code = e?.cause?.code ?? "";
  if (code === "ENOTFOUND" || code === "EAI_AGAIN") return `${host} is not known (no internet, or a typo in the link)`;
  if (code === "ECONNREFUSED") return `${host} refused the connection`;
  if (code === "ECONNRESET" || code === "UND_ERR_SOCKET") return `${host} closed the connection`;
  if (/CERT|SSL|TLS/i.test(code)) return `${host} has a certificate problem`;
  return `could not reach ${host}`;
}

// a readable reason from an error answer: json message fields, else short plain text, else the status
function reason(a: Answer, fallback?: string) {
  const j = a.json as { error?: unknown; message?: unknown; description?: unknown } | null;
  const nested = j && typeof j.error === "object" && j.error ? (j.error as { message?: unknown }).message : undefined;
  const found = [nested, j?.message, j?.description, typeof j?.error === "string" ? j.error : undefined].find(
    (x): x is string => typeof x === "string" && x.trim().length > 0,
  );
  const plain = !found && a.text && !/^\s*</.test(a.text) && a.text.length <= 200 ? a.text : undefined;
  const text = found ?? plain ?? fallback;
  return text ? `${oneLine(text, 120)} (${a.status})` : `answered ${a.status}`;
}

// ---------- the formats

export function slackPayload(m: NotifyMessage) {
  const e = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const blocks: Record<string, unknown>[] = [
    { type: "header", text: { type: "plain_text", text: cut(m.title, 150) } },
    { type: "section", text: { type: "mrkdwn", text: cut(e(m.text), 3000) } },
    {
      type: "section",
      fields: m.facts.slice(0, 10).map(([k, v]) => ({ type: "mrkdwn", text: cut(`*${e(k)}*\n${e(v)}`, 2000) })),
    },
  ];
  if (m.links.length) {
    blocks.push({
      type: "actions",
      elements: m.links.slice(0, 5).map((l, i) => ({
        type: "button",
        text: { type: "plain_text", text: cut(l.label, 75) },
        url: l.url,
        action_id: `link_${i + 1}`,
        ...(l.primary ? { style: "primary" } : {}),
      })),
    });
  }
  return { text: m.title, blocks };
}

// buttons = inline url buttons under the message; without them the links are part of the text
export function telegramPayload(m: NotifyMessage, chatId: string, buttons = true) {
  const lines = [`<b>${htmlEscape(m.title)}</b>`, htmlEscape(m.text), "", ...m.facts.map(([k, v]) => `${htmlEscape(k)}: <b>${htmlEscape(v)}</b>`)];
  if (!buttons && m.links.length) lines.push("", ...m.links.map((l) => `${htmlEscape(l.label)}: ${htmlEscape(l.url)}`));
  return {
    chat_id: chatId,
    text: cut(lines.join("\n"), 4096),
    parse_mode: "HTML",
    link_preview_options: { is_disabled: true },
    ...(buttons && m.links.length
      ? { reply_markup: { inline_keyboard: m.links.map((l) => [{ text: cut(l.label, 64), url: l.url }]) } }
      : {}),
  };
}

export function whatsappPayload(m: NotifyMessage, to: string) {
  const lines = [`*${m.title}*`, m.text, "", ...m.facts.map(([k, v]) => `${k}: ${v}`)];
  if (m.links.length) lines.push("", ...m.links.map((l) => `${l.label}: ${l.url}`));
  return {
    messaging_product: "whatsapp",
    recipient_type: "individual",
    to,
    type: "text",
    text: { preview_url: false, body: cut(lines.join("\n"), 4096) },
  };
}

// buttons = link buttons (non-interactive components, allowed for plain webhooks with with_components=true)
export function discordPayload(m: NotifyMessage, buttons = true) {
  const md = (s: string) => s.replace(/([\\*_~`|])/g, "\\$1");
  const links = m.links.map((l) => `[${md(l.label)}](${l.url})`).join("\n");
  return {
    username: "Nova Clinic",
    allowed_mentions: { parse: [] },
    embeds: [
      {
        title: cut(m.title, 256),
        description: cut(`${md(m.text)}${!buttons && m.links.length ? `\n\n${links}` : ""}`, 4096),
        color: m.urgent ? 0xc0303a : 0x2361d4,
        fields: m.facts.slice(0, 25).map(([k, v]) => ({ name: cut(k, 256), value: cut(md(v), 1024), inline: true })),
      },
    ],
    ...(buttons && m.links.length
      ? {
          components: [
            { type: 1, components: m.links.slice(0, 5).map((l) => ({ type: 2, style: 5, label: cut(l.label, 80), url: l.url })) },
          ],
        }
      : {}),
  };
}

export function webhookPayload(m: NotifyMessage, sentAt: string) {
  return {
    source: "nova-clinic",
    kind: m.kind,
    title: m.title,
    text: m.text,
    urgent: Boolean(m.urgent),
    facts: m.facts.map(([label, value]) => ({ label, value })),
    links: m.links.map((l) => ({ label: l.label, url: l.url })),
    data: m.data,
    sentAt,
  };
}

// ---------- the plugins

const teams: ChannelPlugin = {
  type: "teams",
  async send(cfg, m, ctx) {
    const a = await postJson(ctx, cfg.url, teamsCard(m));
    if ("error" in a) return fail(a.error);
    return is2xx(a.status) ? ok("Teams took the card") : fail(reason(a));
  },
};

const slack: ChannelPlugin = {
  type: "slack",
  async send(cfg, m, ctx) {
    const a = await postJson(ctx, cfg.url, slackPayload(m));
    if ("error" in a) return fail(a.error);
    return is2xx(a.status) ? ok("Slack took it") : fail(reason(a));
  },
};

function telegramReason(a: Answer) {
  if (a.status === 401) return "the bot token is wrong (401)";
  const d = (a.json as { description?: unknown } | null)?.description;
  if (typeof d === "string" && d.trim()) return `${oneLine(d.replace(/^(Bad Request|Forbidden|Unauthorized):\s*/i, ""), 120)} (${a.status})`;
  return `answered ${a.status}`;
}

const telegram: ChannelPlugin = {
  type: "telegram",
  async send(cfg, m, ctx) {
    const url = `${(ctx.env.telegramUrl ?? TELEGRAM_URL).replace(/\/+$/, "")}/bot${cfg.botToken}/sendMessage`;
    const took = (a: Answer) => a.status === 200 && (a.json as { ok?: unknown } | null)?.ok === true;
    let a = await postJson(ctx, url, telegramPayload(m, cfg.chatId, true));
    if ("error" in a) return fail(a.error);
    if (took(a)) return ok("Telegram took it");
    const why = telegramReason(a);
    // telegram refuses buttons with links it does not accept (localhost, some local addresses): links as text then
    if (a.status === 400 && m.links.length && /button|url/i.test(why)) {
      a = await postJson(ctx, url, telegramPayload(m, cfg.chatId, false));
      if ("error" in a) return fail(a.error);
      if (took(a)) return ok("Telegram took it, with the links as text: it did not accept them as buttons", "links as text");
      return fail(telegramReason(a));
    }
    return fail(why);
  },
};

// the cloud api's error codes that need a plain explanation (meta's error code list)
const WHATSAPP_CODES: Record<number, string> = {
  190: "the access token is wrong or expired",
  131030: "this number is not on the test number's list of allowed numbers",
  131047: "the person has not written to your business number in the last 24 hours",
  131026: "WhatsApp could not deliver to this number",
  133010: "the business number is not registered",
  130429: "too many messages, try again later",
  131056: "too many messages to this number, try again later",
};

const whatsapp: ChannelPlugin = {
  type: "whatsapp",
  async send(cfg, m, ctx) {
    const url = `${(ctx.env.whatsappUrl ?? WHATSAPP_URL).replace(/\/+$/, "")}/${encodeURIComponent(cfg.phoneNumberId)}/messages`;
    const a = await postJson(ctx, url, whatsappPayload(m, cfg.to), { Authorization: `Bearer ${cfg.accessToken}` });
    if ("error" in a) return fail(a.error);
    const id = (a.json as { messages?: { id?: unknown }[] } | null)?.messages?.[0]?.id;
    if (is2xx(a.status) && typeof id === "string" && id) {
      return ok("WhatsApp accepted it. It arrives if this number wrote to your business number in the last 24 hours");
    }
    const code = (a.json as { error?: { code?: unknown } } | null)?.error?.code;
    if (typeof code === "number" && WHATSAPP_CODES[code]) return fail(`${WHATSAPP_CODES[code]} (#${code})`);
    return fail(is2xx(a.status) ? "WhatsApp did not confirm the message" : reason(a));
  },
};

function discordReason(a: Answer) {
  if (a.status === 404) return "the webhook link is wrong or the webhook was deleted (404)";
  if (a.status === 401) return "the webhook token is wrong (401)";
  if (a.status === 429) return "too many messages, try again later (429)";
  return reason(a);
}

const discord: ChannelPlugin = {
  type: "discord",
  async send(cfg, m, ctx) {
    let url: URL;
    try {
      url = new URL(cfg.url);
    } catch {
      return fail("the link is not valid");
    }
    url.searchParams.set("wait", "true");
    const withButtons = new URL(url.href);
    withButtons.searchParams.set("with_components", "true");
    let a = await postJson(ctx, withButtons.href, discordPayload(m, true));
    if ("error" in a) return fail(a.error);
    if (is2xx(a.status)) return ok("Discord took it");
    // a webhook that does not take the link buttons: the links go into the card text
    if (a.status === 400 && m.links.length) {
      a = await postJson(ctx, url.href, discordPayload(m, false));
      if ("error" in a) return fail(a.error);
      if (is2xx(a.status)) return ok("Discord took it, with the links in the text: it did not accept the buttons", "links as text");
    }
    return fail(discordReason(a));
  },
};

const email: ChannelPlugin = {
  type: "email",
  async send(cfg, m, ctx) {
    const apiKey = cfg.apiKey || ctx.env.resendKey;
    const from = cfg.from || ctx.env.emailFrom;
    if (!apiKey || !from) return fail("no Resend API key or sender is set");
    const to = emailList(cfg.to ?? "");
    if (!to) return fail("the email address is not valid");
    // the same text and buttons as the teams card
    const mail = cardToEmail(teamsCard(m));
    const a = await postJson(
      ctx,
      ctx.env.resendUrl ?? RESEND_URL,
      { from, to, subject: mail.subject, text: mail.text, html: mail.html },
      { Authorization: `Bearer ${apiKey}` },
    );
    if ("error" in a) return fail(a.error);
    return is2xx(a.status) ? ok("Resend took the email") : fail(reason(a));
  },
};

const webhook: ChannelPlugin = {
  type: "webhook",
  async send(cfg, m, ctx) {
    const a = await postJson(ctx, cfg.url, webhookPayload(m, new Date().toISOString()), cfg.secret ? { Authorization: `Bearer ${cfg.secret}` } : {});
    if ("error" in a) return fail(a.error);
    return is2xx(a.status) ? ok(`your system took it (${a.status})`) : fail(reason(a));
  },
};

export const PLUGINS: Record<ChannelType, ChannelPlugin> = { teams, slack, telegram, whatsapp, discord, email, webhook };

// ---------- sending

function secretsOf(t: Target, env: NotifyEnv) {
  const list: (string | undefined)[] = CHANNEL_INFO[t.type].fields.filter((f) => f.secret).map((f) => t.config[f.key]);
  if (t.type === "email") list.push(env.resendKey);
  return list.filter((s): s is string => typeof s === "string" && s.length >= 6);
}

// a reason must never carry a token or a webhook link
function scrub(text: string, secrets: string[]) {
  let out = text;
  for (const s of secrets) {
    for (const form of new Set([s, encodeURIComponent(s)])) out = out.split(form).join("•••");
  }
  return oneLine(out, 200);
}

// one channel, at most timeoutMs. never throws
export async function sendTo(t: Target, m: NotifyMessage, env: NotifyEnv, fetchImpl: typeof fetch = fetch): Promise<SendResult> {
  const deadline = Date.now() + env.timeoutMs;
  let timer: ReturnType<typeof setTimeout> | undefined;
  // the fetch has the same deadline; this only guards against a plugin that hangs somewhere else
  const late = new Promise<SendResult>((resolve) => {
    timer = setTimeout(() => resolve(fail(`no answer within ${seconds(env.timeoutMs)}`)), env.timeoutMs + 500);
  });
  let r: SendResult;
  try {
    r = await Promise.race([PLUGINS[t.type].send(t.config, m, { env, deadline, fetch: fetchImpl }), late]);
  } catch {
    r = fail("could not send");
  } finally {
    clearTimeout(timer);
  }
  const secrets = secretsOf(t, env);
  const out: SendResult = { ok: r.ok, text: scrub(r.text, secrets) };
  if (r.note) out.note = scrub(r.note, secrets);
  return out;
}

// all channels at the same time; the answers come back in the order of the targets
export function sendAll(targets: Target[], m: NotifyMessage, env: NotifyEnv, fetchImpl?: typeof fetch) {
  return Promise.all(targets.map((t) => sendTo(t, m, env, fetchImpl)));
}

// ---------- the fields staff enter

export type EmailEnvFlags = { apiKey: boolean; from: boolean };
export type ConfigCheck = { config: Record<string, string> } | { error: string };

const SENDER = /^(?:[^<>\r\n"]{1,100}\s<[^\s@<>,;"]+@[^\s@<>,;"]+\.[^\s@<>,;"]+>|[^\s@<>,;"]+@[^\s@<>,;"]+\.[^\s@<>,;"]+)$/;

function badUrl(s: string) {
  if (s.length > 2000 || /\s/.test(s)) return "The link can't contain spaces";
  try {
    const u = new URL(s);
    if (u.protocol !== "https:" && u.protocol !== "http:") return "The link must start with https://";
  } catch {
    return "The link is not valid, it starts with https://";
  }
  return null;
}

// "+49 176 123-4567", "0049176..." -> "+491761234567". null without a country code
export function normalizePhone(s: string) {
  let d = s.replace(/[\s().\-/]/g, "");
  if (d.startsWith("00")) d = `+${d.slice(2)}`;
  if (!d.startsWith("+")) d = `+${d}`;
  return /^\+[1-9]\d{6,14}$/.test(d) ? d : null;
}

// the fields of a type, trimmed and checked. before = the saved config: an empty secret keeps the saved one,
// null removes an optional field
export function cleanConfig(
  type: ChannelType,
  input: unknown,
  before: Record<string, string> | undefined,
  emailEnv: EmailEnvFlags,
): ConfigCheck {
  const raw = input && typeof input === "object" && !Array.isArray(input) ? (input as Record<string, unknown>) : {};
  const fields = CHANNEL_INFO[type].fields;
  const out: Record<string, string> = {};
  for (const f of fields) {
    const v = raw[f.key];
    if (v === null) continue;
    if (v !== undefined && typeof v !== "string") return { error: `${f.label} must be text` };
    const s = v?.trim();
    if (s) out[f.key] = s;
    else if (before?.[f.key] && (f.secret || v === undefined)) out[f.key] = before[f.key];
  }
  const label = (key: string) => fields.find((f) => f.key === key)?.label ?? key;
  const need = (key: string) => (out[key] ? null : `${label(key)} is needed`);

  switch (type) {
    case "teams":
    case "slack":
    case "discord":
    case "webhook": {
      const missing = need("url");
      if (missing) return { error: missing };
      const bad = badUrl(out.url);
      if (bad) return { error: bad };
      if (type === "webhook" && out.secret && !/^[\x21-\x7e]{1,500}$/.test(out.secret)) {
        return { error: "The secret can only have letters, digits and signs, no spaces" };
      }
      break;
    }
    case "telegram": {
      const missing = need("botToken") ?? need("chatId");
      if (missing) return { error: missing };
      if (!/^\d{3,20}:[A-Za-z0-9_-]{20,100}$/.test(out.botToken)) return { error: "The bot token looks like 123456789:AAE… (from @BotFather)" };
      if (!/^(-?\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,63})$/.test(out.chatId)) {
        return { error: "The chat id is a number like -1001234567890, or @channelname" };
      }
      break;
    }
    case "whatsapp": {
      const missing = need("accessToken") ?? need("phoneNumberId") ?? need("to");
      if (missing) return { error: missing };
      if (!/^\S{20,2000}$/.test(out.accessToken)) return { error: "The access token is the long code from API Setup" };
      if (!/^\d{5,25}$/.test(out.phoneNumberId)) {
        return { error: "The phone number id is the long number from API Setup, not the phone number" };
      }
      const phone = normalizePhone(out.to);
      if (!phone) return { error: "Send to needs the number with the country code, like +49 176 1234567" };
      out.to = phone;
      break;
    }
    case "email": {
      const list = emailList(out.to ?? "");
      if (!list) return { error: "Send to must be one email address, or up to 5 separated by commas" };
      out.to = list.join(", ");
      if (!out.apiKey && !emailEnv.apiKey) return { error: "The Resend API key is needed (this server has no RESEND_API_KEY)" };
      if (out.apiKey && !/^\S{8,300}$/.test(out.apiKey)) return { error: "The Resend API key has no spaces and starts with re_" };
      if (!out.from && !emailEnv.from) return { error: "The sender is needed (this server has no EMAIL_FROM)" };
      if (out.from && !SENDER.test(out.from)) return { error: "The sender looks like Nova Clinic <nova@your-domain.example>" };
      break;
    }
  }
  return { config: out };
}

// ---------- what the browser may see

export function maskTail(value: string) {
  return value.length >= 12 ? `…${value.slice(-4)}` : "••••";
}

// "https://hooks.slack.com/services/T0/B0/abcd1234" -> "hooks.slack.com/…1234"
export function maskUrl(value: string) {
  try {
    const u = new URL(value);
    return `${u.host}/…${value.length >= 24 ? value.slice(-4) : ""}`;
  } catch {
    return maskTail(value);
  }
}

export function channelSummary(type: ChannelType, config: Record<string, string>) {
  if (type === "telegram") return config.chatId ? `Chat ${config.chatId}` : "";
  if (type === "whatsapp" || type === "email") return config.to ? `To ${config.to}` : "";
  return config.url ? maskUrl(config.url) : "";
}

export function publicValues(type: ChannelType, config: Record<string, string>) {
  const out: Record<string, string> = {};
  for (const f of CHANNEL_INFO[type].fields) if (!f.secret && config[f.key]) out[f.key] = config[f.key];
  return out;
}

export function maskedSecrets(type: ChannelType, config: Record<string, string>) {
  const out: Record<string, string> = {};
  for (const f of CHANNEL_INFO[type].fields) {
    const v = config[f.key];
    if (f.secret && v) out[f.key] = f.kind === "url" ? maskUrl(v) : maskTail(v);
  }
  return out;
}

// ---------- who gets what

export function envTarget(env: NotifyEnv): Target | null {
  if (!env.teamsWebhook) return null;
  return { id: ENV_TEAMS_ID, type: "teams", label: "Practice channel", source: "env", config: { url: env.teamsWebhook } };
}

// a place's own teams webhook, set on /places
export function placeTeamsTarget(p: Pick<Place, "id" | "name" | "teamsWebhookUrl">): Target | null {
  if (!p.teamsWebhookUrl) return null;
  return {
    id: `place:${p.id}:teams`,
    type: "teams",
    label: `${p.name}, own channel`,
    source: "place",
    placeId: p.id,
    config: { url: p.teamsWebhookUrl },
  };
}

// a place's email address, set on /places. it uses RESEND_API_KEY + EMAIL_FROM of the server
export function placeEmailTarget(p: Pick<Place, "id" | "name" | "notifyEmail">): Target | null {
  if (!p.notifyEmail) return null;
  return { id: `place:${p.id}:email`, type: "email", label: p.name, source: "place", placeId: p.id, config: { to: p.notifyEmail } };
}

export function dashboardTarget(c: NotifyChannel): Target {
  return { id: c.id, type: c.type, label: c.label, source: "dashboard", config: c.config };
}

// help requests from otto, and places without their own choice
export function defaultTargets(channels: NotifyChannel[], env: NotifyEnv): Target[] {
  const out: Target[] = [];
  const practice = envTarget(env);
  if (practice && env.envTeamsDefault) out.push(practice);
  for (const c of channels) if (c.active && c.isDefault) out.push(dashboardTarget(c));
  return out;
}

// the place's own choice (channelIds) or the default channels. its own teams webhook replaces the practice
// channel as before, its email comes on top when the server can send email. order: teams, email, the others
export function placeTargets(
  place: Pick<Place, "id" | "name" | "teamsWebhookUrl" | "notifyEmail" | "channelIds">,
  channels: NotifyChannel[],
  env: NotifyEnv,
): Target[] {
  let picked: Target[];
  if (place.channelIds) {
    const want = new Set(place.channelIds);
    const pool = [envTarget(env), ...channels.filter((c) => c.active).map(dashboardTarget)];
    picked = pool.filter((t): t is Target => Boolean(t && want.has(t.id)));
  } else {
    picked = defaultTargets(channels, env);
  }
  const own = placeTeamsTarget(place);
  const mail = env.resendKey && env.emailFrom ? placeEmailTarget(place) : null;
  const first = own ?? picked.find((t) => t.id === ENV_TEAMS_ID);
  return [...(first ? [first] : []), ...(mail ? [mail] : []), ...picked.filter((t) => t.source === "dashboard")];
}
