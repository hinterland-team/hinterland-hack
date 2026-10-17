// notification channels on the server: the list for /notifications (masked), add / change / remove,
// "Send test", and sending a place's messages to all its channels at once with one journey event per channel
import {
  CHANNEL_INFO,
  CHANNEL_TYPES,
  type ChannelResult,
  type ChannelView,
  ENV_TEAMS_ID,
  MAX_CHANNELS,
  MAX_LABEL,
  type NotificationsView,
  type NotifyChannel,
  channelName,
} from "./channel-types";
import { event, now } from "./common";
import { ApiError, text } from "./http";
import { type NotifyMessage, testMessage } from "./messages";
import {
  type NotifyEnv,
  type SendResult,
  type Target,
  channelSummary,
  cleanConfig,
  dashboardTarget,
  defaultTargets,
  envTarget,
  maskedSecrets,
  placeEmailTarget,
  placeTargets,
  placeTeamsTarget,
  publicValues,
  readNotifyEnv,
  sendAll,
  sendTo,
} from "./notify";
import { getStore } from "./store";
import type { ChannelPatch } from "./store-types";
import { type JourneyEvent, type Place, isOneOf } from "./types";

// the practice channel (TEAMS_WEBHOOK_URL) can be taken out of the defaults on /notifications
const ENV_DEFAULT_KEY = "notify:envTeamsDefault";
// the last message or test per channel, for the status line on /notifications
const lastKey = (id: string) => `notify:last:${id}`;

export async function notifyEnv(): Promise<NotifyEnv> {
  return readNotifyEnv(process.env, (await getStore().getSetting(ENV_DEFAULT_KEY)) !== "false");
}

function emailFlags(env: NotifyEnv) {
  return { apiKey: Boolean(env.resendKey), from: Boolean(env.emailFrom) };
}

async function readLast(id: string): Promise<ChannelResult | undefined> {
  const raw = await getStore().getSetting(lastKey(id));
  if (!raw) return undefined;
  try {
    const v = JSON.parse(raw) as Partial<ChannelResult>;
    if (typeof v.at !== "string") return undefined;
    return { at: v.at, ok: v.ok === true, text: typeof v.text === "string" ? v.text : "", test: v.test === true };
  } catch {
    return undefined;
  }
}

async function saveLast(id: string, r: SendResult, test: boolean) {
  try {
    await getStore().setSetting(lastKey(id), JSON.stringify({ at: now(), ok: r.ok, text: r.text, test }));
  } catch {
    // only the status line, the message itself went out (or not) already
  }
}

// a new link or token: the old result says nothing about it any more
export async function forgetLast(id: string) {
  try {
    await getStore().setSetting(lastKey(id), "");
  } catch {
    // only the status line
  }
}

function viewOf(t: Target, extra: Partial<ChannelView> = {}): ChannelView {
  return {
    id: t.id,
    type: t.type,
    label: t.label,
    source: t.source,
    ...(t.placeId ? { placeId: t.placeId } : {}),
    active: true,
    isDefault: false,
    summary: channelSummary(t.type, t.config),
    values: publicValues(t.type, t.config),
    secrets: maskedSecrets(t.type, t.config),
    ...extra,
  };
}

function dashboardView(c: NotifyChannel) {
  return viewOf(dashboardTarget(c), { active: c.active, isDefault: c.isDefault });
}

const EMAIL_OFF = "Email is off on this server: RESEND_API_KEY and EMAIL_FROM are not set";

// everything /notifications shows. no secret leaves the server: links and tokens only as masked hints
export async function notificationsView(): Promise<NotificationsView> {
  const store = getStore();
  const [channels, places, env] = await Promise.all([store.listChannels(), store.listPlaces(), notifyEnv()]);
  const views: ChannelView[] = [];
  const practice = envTarget(env);
  if (practice) views.push(viewOf(practice, { isDefault: env.envTeamsDefault }));
  for (const c of channels) views.push(dashboardView(c));
  for (const p of places) {
    const own = placeTeamsTarget(p);
    if (own) views.push(viewOf(own, { active: p.active }));
    const mail = placeEmailTarget(p);
    if (mail) views.push(viewOf(mail, { active: p.active, ...(env.resendKey && env.emailFrom ? {} : { off: EMAIL_OFF }) }));
  }
  await Promise.all(
    views.map(async (v) => {
      const last = await readLast(v.id);
      if (last) v.last = last;
    }),
  );
  const routing: Record<string, string[]> = {};
  for (const p of places) if (p.kind !== "exit") routing[p.id] = placeTargets(p, channels, env).map((t) => t.id);
  return {
    channels: views,
    routing,
    help: defaultTargets(channels, env).map((t) => t.id),
    emailEnv: emailFlags(env),
    memoryStore: store.kind === "memory",
  };
}

function cleanLabel(value: unknown, fallback: string) {
  if (value !== undefined && value !== null && typeof value !== "string") throw new ApiError(400, "label must be text");
  return text(value, MAX_LABEL) ?? fallback;
}

function bool(value: unknown, name: string) {
  if (value === undefined) return undefined;
  if (typeof value !== "boolean") throw new ApiError(400, `${name} must be true or false`);
  return value;
}

// { type, label?, config: { ...the fields of the type }, isDefault?, active? }
export async function createChannel(body: Record<string, unknown>) {
  const type = body.type;
  if (!isOneOf(CHANNEL_TYPES, type)) throw new ApiError(400, `type must be one of ${CHANNEL_TYPES.join(", ")}`);
  const store = getStore();
  if ((await store.listChannels()).length >= MAX_CHANNELS) throw new ApiError(409, `there are already ${MAX_CHANNELS} channels`);
  const checked = cleanConfig(type, body.config, undefined, emailFlags(await notifyEnv()));
  if ("error" in checked) throw new ApiError(400, checked.error);
  const at = now();
  const channel: NotifyChannel = {
    id: crypto.randomUUID(),
    type,
    label: cleanLabel(body.label, CHANNEL_INFO[type].title),
    config: checked.config,
    isDefault: bool(body.isDefault, "isDefault") ?? true,
    active: bool(body.active, "active") ?? true,
    createdAt: at,
    updatedAt: at,
  };
  return dashboardView(await store.createChannel(channel));
}

// { id, label?, config?, isDefault?, active? }. a secret field left empty keeps the saved one.
// the practice channel from .env.local only takes isDefault
export async function updateChannel(body: Record<string, unknown>) {
  const id = text(body.id, 200);
  if (!id) throw new ApiError(400, "id is required");
  const store = getStore();
  if (id === ENV_TEAMS_ID) {
    const practice = envTarget(await notifyEnv());
    if (!practice) throw new ApiError(404, "channel not found");
    if (Object.keys(body).some((k) => k !== "id" && k !== "isDefault")) {
      throw new ApiError(400, "the practice channel comes from TEAMS_WEBHOOK_URL in .env.local, here only isDefault can change");
    }
    const isDefault = bool(body.isDefault, "isDefault");
    if (isDefault === undefined) throw new ApiError(400, "send isDefault");
    await store.setSetting(ENV_DEFAULT_KEY, String(isDefault));
    const last = await readLast(id);
    return viewOf(practice, { isDefault, ...(last ? { last } : {}) });
  }
  if (id.startsWith("place:")) throw new ApiError(400, "this channel is set on the place, change it on the Places page");
  const before = (await store.listChannels()).find((c) => c.id === id);
  if (!before) throw new ApiError(404, "channel not found");
  if (body.type !== undefined && body.type !== before.type) throw new ApiError(400, "the type can't change, add a new channel instead");
  const patch: ChannelPatch = { updatedAt: now() };
  if (body.label !== undefined) patch.label = cleanLabel(body.label, CHANNEL_INFO[before.type].title);
  if (body.config !== undefined) {
    const checked = cleanConfig(before.type, body.config, before.config, emailFlags(await notifyEnv()));
    if ("error" in checked) throw new ApiError(400, checked.error);
    patch.config = checked.config;
  }
  const isDefault = bool(body.isDefault, "isDefault");
  if (isDefault !== undefined) patch.isDefault = isDefault;
  const active = bool(body.active, "active");
  if (active !== undefined) patch.active = active;
  const saved = await store.updateChannel(id, patch);
  if (!saved) throw new ApiError(404, "channel not found");
  if (patch.config && JSON.stringify(patch.config) !== JSON.stringify(before.config)) await forgetLast(id);
  const view = dashboardView(saved);
  const last = await readLast(id);
  return last ? { ...view, last } : view;
}

export async function deleteChannel(id: string | undefined) {
  if (!id) throw new ApiError(400, "id is required");
  if (id === ENV_TEAMS_ID) throw new ApiError(400, "the practice channel comes from TEAMS_WEBHOOK_URL in .env.local, remove it there");
  if (id.startsWith("place:")) throw new ApiError(400, "this channel is set on the place, change it on the Places page");
  if (!(await getStore().deleteChannel(id))) throw new ApiError(404, "channel not found");
  await forgetLast(id);
  return { ok: true };
}

// any channel by id: made here, the practice channel, or a place's own teams webhook / email
async function findTarget(id: string): Promise<Target | null> {
  const store = getStore();
  if (id === ENV_TEAMS_ID) return envTarget(await notifyEnv());
  const own = /^place:(.+):(teams|email)$/.exec(id);
  if (own) {
    const place = await store.getPlace(own[1]);
    if (!place) return null;
    return own[2] === "teams" ? placeTeamsTarget(place) : placeEmailTarget(place);
  }
  const c = (await store.listChannels()).find((x) => x.id === id);
  return c ? dashboardTarget(c) : null;
}

// "Send test": a message that says it is a test, with links to the reception and this page
export async function testChannel(id: unknown, base: string) {
  const key = text(id, 200);
  if (!key) throw new ApiError(400, "id is required");
  const target = await findTarget(key);
  if (!target) throw new ApiError(404, "channel not found");
  const at = now();
  const r = await sendTo(target, testMessage(channelName(target), base, at), await notifyEnv());
  if (!r.ok) console.error(`notify test: ${channelName(target)} failed: ${r.text}`);
  await saveLast(target.id, r, true);
  return { ok: r.ok, text: r.text, at };
}

// the events keep the v3 texts for the practice channel, a place's own teams webhook and its email
function eventFor(t: Target, r: SendResult, who: string): JourneyEvent {
  if (t.type === "teams" && t.source !== "dashboard") {
    return r.ok ? event("teams_notified", `Teams card sent to ${who}`) : event("teams_failed", `Teams did not take the card: ${r.text}`);
  }
  if (t.type === "email" && t.source === "place") {
    return r.ok ? event("email_sent", `Email sent to ${who}`) : event("email_failed", `The email to ${who} did not go out: ${r.text}`);
  }
  const name = channelName(t);
  if (r.ok) return event(t.type === "email" ? "email_sent" : `${t.type}_notified`, `Sent via ${name}${r.note ? `, ${r.note}` : ""}`);
  return event(`${t.type}_failed`, `${name} failed: ${r.text}`);
}

// a place's message to all its channels at the same time. one event per channel, in a fixed order
export async function notifyPlace(place: Place, message: NotifyMessage): Promise<JourneyEvent[]> {
  const store = getStore();
  const [channels, env] = await Promise.all([store.listChannels(), notifyEnv()]);
  const targets = placeTargets(place, channels, env);
  if (!targets.length) return [event("teams_skipped", `No channel for ${place.name}, the board shows it`)];
  const results = await sendAll(targets, message, env);
  await Promise.all(results.map((r, i) => saveLast(targets[i].id, r, false)));
  return results.map((r, i) => {
    if (!r.ok) console.error(`notify: ${channelName(targets[i])} failed: ${r.text}`);
    return eventFor(targets[i], r, place.name);
  });
}

// help requests from otto go to the default channels. true when at least one took it
export async function notifyHelp(message: NotifyMessage) {
  const store = getStore();
  const [channels, env] = await Promise.all([store.listChannels(), notifyEnv()]);
  const targets = defaultTargets(channels, env);
  if (!targets.length) return false;
  const results = await sendAll(targets, message, env);
  await Promise.all(results.map((r, i) => saveLast(targets[i].id, r, false)));
  results.forEach((r, i) => {
    if (!r.ok) console.error(`notify help: ${channelName(targets[i])} failed: ${r.text}`);
  });
  return results.some((r) => r.ok);
}

// for /api/places: the ids a place may pick
export async function channelIdsKnown() {
  const ids = new Set((await getStore().listChannels()).map((c) => c.id));
  if (readNotifyEnv().teamsWebhook) ids.add(ENV_TEAMS_ID);
  return ids;
}
