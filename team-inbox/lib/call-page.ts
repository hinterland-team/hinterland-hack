import { MASCOT_SVG } from "./mascot";

export type CallPage = {
  status: number;
  pill?: string;
  heading: string;
  lines: string[];
  note?: string;
  moving?: boolean;
};

function esc(value: string) {
  return value.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

// the small page a doctor sees after tapping "Call patient in" in teams
export function callPageHtml(page: CallPage) {
  const body = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(page.heading)}</title>
<style>
:root { color-scheme: light dark; --bg: #f4f7fb; --surface: #ffffff; --line: #dde5f0; --text: #0e1a2b; --muted: #5a6a80; --primary: #2a63d4; --soft: #e3edff; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #0b1220; --surface: #121b2d; --line: #22304a; --text: #e7edf6; --muted: #94a3bb; --primary: #7aaeff; --soft: #1a2c4d; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font: 16px/24px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; -webkit-font-smoothing: antialiased; }
main { max-width: 448px; margin: 0 auto; padding: 48px 16px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 32px 24px; text-align: center; }
.face { width: 96px; height: 96px; margin: 0 auto 24px; }
.face svg { width: 96px; height: 96px; display: block; }
.moving .face { animation: bob 1.6s ease-in-out infinite; }
.nova-eyes { transform-box: fill-box; transform-origin: center; animation: blink 5s infinite; }
@keyframes bob { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-4px); } }
@keyframes blink { 0%, 94%, 100% { transform: scaleY(1); } 96% { transform: scaleY(0.1); } }
@media (prefers-reduced-motion: reduce) { .moving .face, .nova-eyes { animation: none; } }
.pill { display: inline-block; background: var(--soft); color: var(--primary); border-radius: 999px; padding: 4px 12px; font-size: 14px; line-height: 20px; font-weight: 600; margin: 0 0 16px; }
h1 { font-size: 24px; line-height: 32px; font-weight: 650; margin: 0 0 12px; overflow-wrap: anywhere; }
p { margin: 0 0 4px; color: var(--muted); overflow-wrap: anywhere; }
.note { font-size: 14px; line-height: 20px; margin-top: 24px; }
</style>
</head>
<body>
<main class="${page.moving ? "moving" : ""}">
  <div class="panel">
    <div class="face">${MASCOT_SVG}</div>
    ${page.pill ? `<div class="pill">${esc(page.pill)}</div>` : ""}
    <h1>${esc(page.heading)}</h1>
    ${page.lines.map((l) => `<p>${esc(l)}</p>`).join("\n    ")}
    ${page.note ? `<p class="note">${esc(page.note)}</p>` : ""}
  </div>
</main>
</body>
</html>`;
  return new Response(body, {
    status: page.status,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
  });
}
