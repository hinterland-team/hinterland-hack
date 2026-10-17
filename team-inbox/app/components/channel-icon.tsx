"use client";

import { DiscordLogo, EnvelopeSimple, MicrosoftTeamsLogo, SlackLogo, TelegramLogo, WebhooksLogo, WhatsappLogo } from "@phosphor-icons/react";
import type { ChannelType } from "@/lib/channel-types";

const ICONS: Record<ChannelType, typeof SlackLogo> = {
  teams: MicrosoftTeamsLogo,
  slack: SlackLogo,
  telegram: TelegramLogo,
  whatsapp: WhatsappLogo,
  discord: DiscordLogo,
  email: EnvelopeSimple,
  webhook: WebhooksLogo,
};

export function ChannelIcon({ type, size = 20, className = "" }: { type: ChannelType; size?: number; className?: string }) {
  const Icon = ICONS[type];
  return <Icon size={size} aria-hidden className={className} />;
}
