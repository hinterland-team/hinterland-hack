// email as a second channel next to teams: a plain rest call to resend, no sdk.
// the email is made from the same adaptive card as the teams message (ticket, places, pace, links),
// so it can never carry more than the card. on when RESEND_API_KEY and EMAIL_FROM are set

export type Email = { subject: string; text: string; html: string };

type CardBlock = { type?: string; text?: string; facts?: { title?: string; value?: string }[] };
type CardAction = { type?: string; title?: string; url?: string };

const RESEND_URL = "https://api.resend.com/emails";
const MAX_RECIPIENTS = 5;
const ADDRESS = /^[^\s@,;<>"]+@[^\s@,;<>"]+\.[^\s@,;<>"]+$/;

export function emailConfigured() {
  return Boolean(process.env.RESEND_API_KEY?.trim() && process.env.EMAIL_FROM?.trim());
}

// "a@x.de, b@y.de" -> list. null when one of them is not an address
export function emailList(value: string) {
  const list = value
    .split(/[,;\s]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (!list.length || list.length > MAX_RECIPIENTS || list.some((a) => a.length > 254 || !ADDRESS.test(a))) return null;
  return list;
}

function esc(value: string) {
  return value.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

// title, lines, facts and link buttons of a teams card -> subject, plain text and simple html
export function cardToEmail(card: unknown): Email {
  const content = (card as { attachments?: { content?: { body?: CardBlock[]; actions?: CardAction[] } }[] })?.attachments?.[0]
    ?.content;
  const body = content?.body ?? [];
  const blocks = body.filter((b) => b.type === "TextBlock" && b.text).map((b) => b.text as string);
  const facts = body
    .filter((b) => b.type === "FactSet")
    .flatMap((b) => b.facts ?? [])
    .filter((f): f is { title: string; value: string } => Boolean(f.title && f.value));
  const links = (content?.actions ?? []).filter(
    (a): a is { title: string; url: string } => a.type === "Action.OpenUrl" && Boolean(a.title && a.url),
  );
  const [subject = "Nova Clinic", ...lines] = blocks;

  const text = [
    subject,
    "",
    ...lines,
    ...(facts.length ? ["", ...facts.map((f) => `${f.title}: ${f.value}`)] : []),
    ...(links.length ? ["", ...links.map((l) => `${l.title}: ${l.url}`)] : []),
    "",
    "Sent by Nova Clinic.",
  ].join("\n");

  const html = `<div style="font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;font-size:15px;line-height:22px;color:#0f1b2d;max-width:520px">
<h1 style="font-size:20px;line-height:28px;margin:0 0 8px">${esc(subject)}</h1>
${lines.map((l) => `<p style="margin:0 0 8px;color:#56667c">${esc(l)}</p>`).join("\n")}
${
  facts.length
    ? `<table role="presentation" style="border-collapse:collapse;margin:12px 0">${facts
        .map(
          (f) =>
            `<tr><td style="padding:4px 16px 4px 0;color:#56667c">${esc(f.title)}</td><td style="padding:4px 0;font-weight:600">${esc(f.value)}</td></tr>`,
        )
        .join("")}</table>`
    : ""
}
${links
  .map(
    (l) =>
      `<p style="margin:8px 0"><a href="${esc(l.url)}" style="display:inline-block;background:#2361d4;color:#ffffff;text-decoration:none;font-weight:600;padding:10px 16px;border-radius:10px">${esc(l.title)}</a></p>`,
  )
  .join("\n")}
<p style="margin:16px 0 0;font-size:13px;color:#56667c">Sent by Nova Clinic.</p>
</div>`;
  return { subject, text, html };
}

// true = resend took it. false = not set up, a bad address or resend said no
export async function sendEmail(to: string | undefined, email: Email): Promise<boolean> {
  if (!to || !emailConfigured()) return false;
  const list = emailList(to);
  if (!list) return false;
  try {
    const res = await fetch(process.env.RESEND_API_URL?.trim() || RESEND_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${process.env.RESEND_API_KEY?.trim()}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ from: process.env.EMAIL_FROM?.trim(), to: list, subject: email.subject, text: email.text, html: email.html }),
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) console.error(`resend answered ${res.status}`);
    return res.ok;
  } catch (err) {
    console.error("email failed", err instanceof Error ? err.message : err);
    return false;
  }
}
