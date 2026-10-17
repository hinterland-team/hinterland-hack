// notification channels: the kinds, the fields staff fill in and how the board names them.
// shared by the server and the browser, no imports (tests load this file directly)

export const CHANNEL_TYPES = ["teams", "slack", "telegram", "whatsapp", "discord", "email", "webhook"] as const;
export type ChannelType = (typeof CHANNEL_TYPES)[number];

// the practice channel from TEAMS_WEBHOOK_URL in .env.local, shown as a channel
export const ENV_TEAMS_ID = "env:teams";
export const MAX_CHANNELS = 50;
export const MAX_PLACE_CHANNELS = 20;
export const MAX_LABEL = 60;

export type ChannelField = {
  key: string;
  label: string;
  // links with a token in them, bot tokens, api keys: kept on the server, shown masked after saving
  secret?: boolean;
  optional?: boolean;
  kind?: "url" | "text" | "tel" | "email";
  placeholder?: string;
  help?: string;
};

export type ChannelInfo = {
  title: string;
  // the word on the board and in the journey events: "Slack"
  short: string;
  what: string;
  how: string[];
  note?: string;
  fields: ChannelField[];
};

export const CHANNEL_INFO: Record<ChannelType, ChannelInfo> = {
  teams: {
    title: "Microsoft Teams",
    short: "Teams",
    what: "A card with buttons in a Teams channel.",
    how: [
      "In Teams open the channel, click the three dots and then Workflows.",
      'Pick "Post to a channel when a webhook request is received" and finish the steps.',
      "Copy the link the workflow shows at the end.",
    ],
    fields: [{ key: "url", label: "Workflow link", secret: true, kind: "url", placeholder: "https://" }],
  },
  slack: {
    title: "Slack",
    short: "Slack",
    what: "A message with link buttons in a Slack channel.",
    how: [
      "Open api.slack.com/apps and create an app for your workspace (from scratch).",
      "Turn on Incoming Webhooks, click Add New Webhook and pick the channel.",
      "Copy the webhook link.",
    ],
    fields: [{ key: "url", label: "Webhook link", secret: true, kind: "url", placeholder: "https://hooks.slack.com/services/" }],
  },
  telegram: {
    title: "Telegram",
    short: "Telegram",
    what: "A message with buttons from your Telegram bot, to one person or a group.",
    how: [
      "In Telegram send /newbot to @BotFather and copy the token it answers with.",
      "Add the bot to the group, or send the bot any message from your own account.",
      "Open api.telegram.org/bot<token>/getUpdates in a browser and copy the chat id. Group ids start with a minus.",
    ],
    fields: [
      { key: "botToken", label: "Bot token", secret: true, placeholder: "123456789:AA" },
      { key: "chatId", label: "Chat id", placeholder: "-1001234567890 or @channelname" },
    ],
  },
  whatsapp: {
    title: "WhatsApp",
    short: "WhatsApp",
    what: "A text message with the links, sent with Meta's WhatsApp Cloud API.",
    how: [
      "In your Meta app on developers.facebook.com open WhatsApp, then API Setup.",
      "Copy the access token and the phone number id (the id, not the number itself).",
      "Enter the phone number that gets the messages, with the country code.",
    ],
    note: "Meta only delivers free text to a number that wrote to your WhatsApp business number in the last 24 hours. Ask the person to send it any message first, for example every morning.",
    fields: [
      { key: "accessToken", label: "Access token", secret: true, placeholder: "EAAG" },
      { key: "phoneNumberId", label: "Phone number id", placeholder: "106540352242922" },
      { key: "to", label: "Send to", kind: "tel", placeholder: "+49 176 1234567" },
    ],
  },
  discord: {
    title: "Discord",
    short: "Discord",
    what: "A card with link buttons in a Discord channel.",
    how: ["In Discord open Server Settings, Integrations, Webhooks.", "Create a webhook, pick the channel and copy the webhook URL."],
    fields: [{ key: "url", label: "Webhook link", secret: true, kind: "url", placeholder: "https://discord.com/api/webhooks/" }],
  },
  email: {
    title: "Email",
    short: "Email",
    what: "An email with the same content and link buttons, sent with Resend.",
    how: [
      "Create an API key at resend.com (sending access is enough).",
      "The sender's domain must be verified at Resend. For a first test use onboarding@resend.dev: Resend then only delivers to the address of your Resend account.",
    ],
    fields: [
      {
        key: "to",
        label: "Send to",
        kind: "email",
        placeholder: "desk@clinic.example",
        help: "Up to 5 addresses, separated by commas.",
      },
      { key: "apiKey", label: "Resend API key", secret: true, placeholder: "re_" },
      { key: "from", label: "Sender", placeholder: "Otto Clinic <otto@your-domain.example>" },
    ],
  },
  webhook: {
    title: "Own webhook",
    short: "Webhook",
    what: "The message as JSON, posted to your own system.",
    how: [
      "Otto Clinic sends a POST with a JSON body: kind, title, text, facts, links and data (ticket, place, room, pace, wheelchair, expected).",
      "With a secret it adds the header Authorization: Bearer <secret>.",
    ],
    fields: [
      { key: "url", label: "Link", secret: true, kind: "url", placeholder: "https://" },
      { key: "secret", label: "Secret", secret: true, optional: true },
    ],
  },
};

// a channel made on the dashboard, as stored. config holds the links and tokens: it never leaves the server
export type NotifyChannel = {
  id: string;
  type: ChannelType;
  label: string;
  config: Record<string, string>;
  isDefault: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

// dashboard = made on /notifications, env = TEAMS_WEBHOOK_URL, place = a place's own Teams webhook or email (set on /places)
export type ChannelSource = "dashboard" | "env" | "place";

export type ChannelResult = { at: string; ok: boolean; text: string; test: boolean };

// what the browser gets: no secrets, only masked hints
export type ChannelView = {
  id: string;
  type: ChannelType;
  label: string;
  source: ChannelSource;
  placeId?: string;
  active: boolean;
  isDefault: boolean;
  // where it goes, without the secret part: "hooks.slack.com/…k3Fq", "Chat -1001234", "To +49176…"
  summary: string;
  // the fields that are not secret
  values: Record<string, string>;
  // saved secret fields, masked: { url: "hooks.slack.com/…k3Fq" }
  secrets: Record<string, string>;
  // why it can't send right now
  off?: string;
  last?: ChannelResult;
};

export type NotificationsView = {
  channels: ChannelView[];
  // place id -> the channels that get its messages, in sending order
  routing: Record<string, string[]>;
  // help requests from otto go to the default channels
  help: string[];
  // RESEND_API_KEY / EMAIL_FROM set on the server: an email channel may leave them empty
  emailEnv: { apiKey: boolean; from: boolean };
  // no DATABASE_URL and STORE_FILE=off: the channels are gone after a restart of the server
  memoryStore: boolean;
  // where everything is kept: the database, a file on this computer (.data/store.json) or only in memory
  storage: "postgres" | "file" | "memory";
  // POST /api/integrations/journeys is on (INTEGRATION_KEY is set)
  integration: boolean;
};

// journey events about messages: <channel>_notified / _sent / _failed / _skipped.
// the walk timeline hides them, the board's message line shows them
const NOTIFY_EVENT = /^(teams|slack|telegram|whatsapp|discord|email|webhook)_(notified|sent|failed|skipped)$/;

export function notifyEvent(type: string): { channel: ChannelType; outcome: "sent" | "failed" | "skipped" } | null {
  const m = NOTIFY_EVENT.exec(type);
  if (!m) return null;
  const outcome = m[2] === "failed" ? "failed" : m[2] === "skipped" ? "skipped" : "sent";
  return { channel: m[1] as ChannelType, outcome };
}

export function isNotifyEvent(type: string) {
  return NOTIFY_EVENT.test(type);
}

// "Slack" or "Slack (Front desk)"
export function channelName(c: { type: ChannelType; label: string }) {
  const info = CHANNEL_INFO[c.type];
  const label = c.label.trim();
  return !label || label === info.title || label === info.short ? info.short : `${info.short} (${label})`;
}

// the short name in "who gets what" lists: "Practice Teams", "Own Teams", "Email", or the channel's name
export function channelChipName(c: Pick<ChannelView, "source" | "type" | "label">) {
  if (c.source === "env") return "Practice Teams";
  if (c.source === "place") return c.type === "teams" ? "Own Teams" : "Email";
  return c.label || CHANNEL_INFO[c.type].short;
}
