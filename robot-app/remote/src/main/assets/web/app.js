"use strict";
// Nova Remote control page. talks to the robot's own http server (pairing, apps, apk upload) and
// a websocket (live screen out, taps / keys / driving in). token comes from pairing and is sent
// on every call; without it the robot refuses everything.

let token = null;
let ws = null;
let lastUrl = null;

const $ = (id) => document.getElementById(id);

// ---- pairing ----
$("pairBtn").onclick = pair;
$("code").addEventListener("keydown", (e) => { if (e.key === "Enter") pair(); });

async function pair() {
  const code = $("code").value.replace(/\D/g, "");
  const msg = $("pairMsg");
  if (code.length !== 9) { msg.textContent = "The code is 9 digits."; msg.className = "msg bad"; return; }
  msg.textContent = "Connecting…"; msg.className = "msg";
  try {
    const r = await fetch("/pair", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: "code=" + code });
    const d = await r.json();
    if (!d.ok) { msg.textContent = reason(d); msg.className = "msg bad"; return; }
    token = d.token;
    openControl(d.wsPort);
  } catch (e) {
    msg.textContent = "Could not reach the robot."; msg.className = "msg bad";
  }
}

function reason(d) {
  switch (d.error) {
    case "code": return "Wrong code.";
    case "mac": return "This device is not on the allow list.";
    case "locked": return "Too many tries, wait " + (d.wait || 60) + "s.";
    case "wrong network": return "You are not on the robot's Wi-Fi.";
    default: return "Not allowed.";
  }
}

// ---- control ----
function openControl(wsPort) {
  $("pair").hidden = true;
  $("control").hidden = false;
  connectWs(wsPort);
  refreshState();
  loadApps();
  setInterval(refreshState, 4000);
  wireScreen();
  wireNav();
  wireDrive();
  wireType();
  wireInstall();
  $("refreshApps").onclick = loadApps;
}

function connectWs(wsPort) {
  const url = "ws://" + location.hostname + ":" + wsPort + "/?t=" + encodeURIComponent(token);
  ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  ws.onmessage = (ev) => {
    if (typeof ev.data === "string") return;
    const blob = new Blob([ev.data], { type: "image/jpeg" });
    const u = URL.createObjectURL(blob);
    const img = $("screen");
    img.onload = () => { if (lastUrl) URL.revokeObjectURL(lastUrl); lastUrl = u; };
    img.src = u;
    $("screenHint").style.display = "none";
  };
  ws.onclose = () => setTimeout(() => connectWs(wsPort), 1500);
}

function sendWs(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

async function api(path, opts) {
  const sep = path.includes("?") ? "&" : "?";
  return fetch(path + sep + "t=" + encodeURIComponent(token), opts);
}

async function refreshState() {
  try {
    const d = await (await api("/api/state")).json();
    const bits = [];
    bits.push(d.capturing ? "screen ✓" : "screen off");
    bits.push(d.a11y ? "taps ✓" : "taps off");
    bits.push(d.drive ? "drive ✓" : "drive off");
    if (d.battery && d.battery.level >= 0) bits.push(d.battery.level + "%" + (d.battery.charging ? "⚡" : ""));
    $("stat").textContent = bits.join("  ·  ");
  } catch (e) { /* keep last */ }
}

// ---- live screen: click = tap, drag = swipe ----
function wireScreen() {
  const img = $("screen");
  let down = null;
  const norm = (e) => {
    const r = img.getBoundingClientRect();
    const p = (e.touches && e.touches[0]) || e;
    return { x: (p.clientX - r.left) / r.width, y: (p.clientY - r.top) / r.height };
  };
  const start = (e) => { down = { ...norm(e), t: Date.now() }; e.preventDefault(); };
  const end = (e) => {
    if (!down) return;
    const up = norm(e.changedTouches ? { clientX: e.changedTouches[0].clientX, clientY: e.changedTouches[0].clientY } : e);
    const far = Math.hypot(up.x - down.x, up.y - down.y) > 0.03;
    if (far) sendWs({ t: "swipe", x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms: Math.min(800, Date.now() - down.t) });
    else sendWs({ t: "tap", x: down.x, y: down.y });
    down = null;
  };
  img.addEventListener("mousedown", start);
  img.addEventListener("mouseup", end);
  img.addEventListener("touchstart", start, { passive: false });
  img.addEventListener("touchend", end);
}

// ---- back / home / recents ----
function wireNav() {
  document.querySelectorAll(".nav").forEach((b) =>
    b.addEventListener("click", () => sendWs({ t: "key", k: b.dataset.key }))
  );
}

// ---- driving: arrow keys + the on-screen d-pad ----
function wireDrive() {
  const keyToDir = { ArrowUp: "forward", ArrowDown: "back", ArrowLeft: "left", ArrowRight: "right" };
  const held = {};
  const tickers = {};

  const startDir = (dir) => {
    if (dir === "stop") { stopAll(); return; }
    if (tickers[dir]) return;
    sendWs({ t: "drive", dir });
    // the robot stops the wheels if no command arrives within ~0.5s, so refresh while held
    tickers[dir] = setInterval(() => sendWs({ t: "drive", dir }), 200);
  };
  const stopDir = (dir) => {
    clearInterval(tickers[dir]); delete tickers[dir];
    if (Object.keys(tickers).length === 0) sendWs({ t: "stop" });
  };
  const stopAll = () => { Object.keys(tickers).forEach((d) => { clearInterval(tickers[d]); delete tickers[d]; }); sendWs({ t: "stop" }); };

  document.addEventListener("keydown", (e) => {
    const dir = keyToDir[e.key];
    if (!dir || held[e.key]) return;
    held[e.key] = true; startDir(dir); e.preventDefault();
  });
  document.addEventListener("keyup", (e) => {
    const dir = keyToDir[e.key];
    if (!dir) return;
    held[e.key] = false; stopDir(dir); e.preventDefault();
  });

  document.querySelectorAll(".dpad button").forEach((b) => {
    const dir = b.dataset.dir;
    const go = (e) => { e.preventDefault(); startDir(dir); };
    const rel = (e) => { e.preventDefault(); if (dir !== "stop") stopDir(dir); };
    b.addEventListener("mousedown", go);
    b.addEventListener("mouseup", rel);
    b.addEventListener("mouseleave", rel);
    b.addEventListener("touchstart", go, { passive: false });
    b.addEventListener("touchend", rel);
  });
}

// ---- type into the focused field on the robot ----
function wireType() {
  const send = () => { const v = $("text").value; if (v) sendWs({ t: "type", text: v }); };
  $("typeBtn").onclick = send;
  $("text").addEventListener("keydown", (e) => { if (e.key === "Enter") send(); });
}

// ---- apk install (drag & drop or pick) ----
function wireInstall() {
  const drop = $("drop"), input = $("apk");
  drop.addEventListener("click", () => input.click());
  input.addEventListener("change", () => input.files[0] && upload(input.files[0]));
  ["dragover", "dragenter"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("hot"); }));
  ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("hot"); }));
  drop.addEventListener("drop", (e) => { const f = e.dataTransfer.files[0]; if (f) upload(f); });
}

async function upload(file) {
  const msg = $("installMsg");
  msg.textContent = "Uploading " + file.name + "…"; msg.className = "msg";
  const fd = new FormData();
  fd.append("apk", file, file.name);
  try {
    const d = await (await api("/api/install", { method: "POST", body: fd })).json();
    if (d.ok) { msg.textContent = "Sent. Tap Install on the robot screen."; msg.className = "msg ok"; }
    else { msg.textContent = "Upload failed."; msg.className = "msg bad"; }
  } catch (e) { msg.textContent = "Upload failed."; msg.className = "msg bad"; }
}

// ---- app list ----
async function loadApps() {
  const ul = $("apps");
  ul.innerHTML = "<li class='muted'>loading…</li>";
  try {
    const d = await (await api("/api/apps")).json();
    ul.innerHTML = "";
    d.apps.forEach((a) => {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.className = "name";
      name.textContent = (a.label || a.package) + (a.versionName ? "  " + a.versionName : "");
      name.title = a.package;
      const open = document.createElement("button");
      open.textContent = "Open";
      open.onclick = () => api("/api/launch", post("pkg=" + encodeURIComponent(a.package)));
      const del = document.createElement("button");
      del.textContent = "Uninstall"; del.className = "del";
      del.onclick = () => api("/api/uninstall", post("pkg=" + encodeURIComponent(a.package)));
      li.append(name, open);
      if (!a.system) li.append(del);
      ul.append(li);
    });
  } catch (e) { ul.innerHTML = "<li class='muted'>could not load</li>"; }
}

function post(body) {
  return { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body };
}
