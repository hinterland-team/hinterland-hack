// staff session cookie. web crypto only, so it works in middleware and in routes.
// the signing key comes from DASHBOARD_PASSWORD (+ SESSION_SECRET when set):
// a new password signs everybody out

export const SESSION_COOKIE = "nova_staff";
export const SESSION_DAYS = 7;

const enc = new TextEncoder();

function b64url(bytes: ArrayBuffer) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function sameText(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function sha256(text: string) {
  return b64url(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

async function key(password: string) {
  const raw = await crypto.subtle.digest("SHA-256", enc.encode(`nova-session|${password}|${process.env.SESSION_SECRET ?? ""}`));
  return crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
}

async function sign(password: string, payload: string) {
  return b64url(await crypto.subtle.sign("HMAC", await key(password), enc.encode(payload)));
}

// "v1.<expires ms>.<signature>"
export async function makeSession(password: string, now = Date.now()) {
  const payload = `v1.${now + SESSION_DAYS * 86400_000}`;
  return `${payload}.${await sign(password, payload)}`;
}

export async function validSession(password: string, value: string | undefined, now = Date.now()) {
  if (!value) return false;
  const parts = value.split(".");
  if (parts.length !== 3 || parts[0] !== "v1") return false;
  const expires = Number(parts[1]);
  if (!Number.isFinite(expires) || expires < now) return false;
  return sameText(parts[2], await sign(password, `${parts[0]}.${parts[1]}`));
}

// compares hashes, so the time does not depend on how much of the password was right
export async function passwordMatches(given: string, password: string) {
  return sameText(await sha256(`pw|${given}`), await sha256(`pw|${password}`));
}

export function basicPassword(header: string | null) {
  if (!header?.startsWith("Basic ")) return null;
  try {
    const decoded = atob(header.slice(6));
    return decoded.slice(decoded.indexOf(":") + 1);
  } catch {
    return null;
  }
}

export function cookieValue(header: string | null, name: string) {
  if (!header) return undefined;
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return undefined;
}

// cookie or basic auth. true when no password is set (open dashboard)
export async function staffAuthorized(req: Request) {
  const password = process.env.DASHBOARD_PASSWORD;
  if (!password) return true;
  if (await validSession(password, cookieValue(req.headers.get("cookie"), SESSION_COOKIE))) return true;
  const given = basicPassword(req.headers.get("authorization"));
  return given !== null && (await passwordMatches(given, password));
}

export function bearerValid(req: Request) {
  const token = process.env.TEAM_INBOX_TOKEN;
  const header = req.headers.get("authorization");
  if (!token) return true;
  return header !== null && sameText(header, `Bearer ${token}`);
}

export function hasBearer(req: Request) {
  return req.headers.get("authorization")?.startsWith("Bearer ") ?? false;
}

export function isHttps(req: Request) {
  const proto = req.headers.get("x-forwarded-proto")?.split(",")[0]?.trim();
  return (proto ?? new URL(req.url).protocol.replace(":", "")) === "https";
}

export function sessionCookie(value: string, secure: boolean, maxAge = SESSION_DAYS * 86400) {
  return [
    `${SESSION_COOKIE}=${encodeURIComponent(value)}`,
    "Path=/",
    `Max-Age=${maxAge}`,
    "HttpOnly",
    "SameSite=Lax",
    ...(secure ? ["Secure"] : []),
  ].join("; ");
}
