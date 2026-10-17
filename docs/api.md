# Nova Clinic - API between robot app and backend

The contract is now `journeys.md` (v3 and v3.1: places, journeys, tickets, the `guide` command, Teams cards, retention). Read that first. The v1/v2 check-in flow (visits, doctors, patient names, `call_patient`) is gone.

This file keeps what still holds from v2 and writes down the details `journeys.md` leaves open.

Backend = `team-inbox/` (Next.js). Robot app = `robot-app/` (Android, AgentOS SDK 0.4.5).
Base URL on the robot = `teamInbox.url` without the path, e.g. `http://192.168.2.214:3000`.

## Auth

- Robot calls send `Authorization: Bearer <TEAM_INBOX_TOKEN>` (`teamInbox.token` in robot-app/local.properties). Without `TEAM_INBOX_TOKEN` on the server the robot routes are open.
- Robot-only routes check the token themselves and never ask for a staff login: `GET /api/robot/next`, `POST /api/robot/heartbeat`, `POST /api/robot/commands/:id/done`, `POST /api/robot/feed`, `POST /api/help`.
- Staff login when `DASHBOARD_PASSWORD` is set: `/login` (one shared password) sets an httpOnly session cookie for 7 days. It is signed with a key made from `DASHBOARD_PASSWORD` and `SESSION_SECRET`, so changing either signs everybody out. Basic auth (any user name, that password) still works for scripts. `POST /api/logout` signs out.
- Wrong passwords: 5 per address in 10 minutes, then `429` for 10 minutes. The address is only kept as a hash, for one hour.
- The robot token also opens the dashboard routes, but only to read. Writes that the contract gives to staff answer `403` for the robot: places, `next` / `done` / `cancel` / `retry`, `with_staff`, robot commands, closing help, the demo switch.
- Teams links (`GET /api/journeys/:id/next?to=...&k=...` and `GET /api/journeys/:id/done?k=...`) skip the login and check `k` against `CALL_KEY`.
- Errors are `{ "error": "..." }` with 400 (bad body), 401 (token or login), 403 (not allowed for the robot), 404 (unknown id), 409 (state does not allow it).

## Robot heartbeat and status (as in v2, plus the screen)

- `POST /api/robot/heartbeat` (robot) every 15 s, body `{ robot, battery: 0-100 | null, charging, state, position, goal, detail?, screen? }` -> `204`.
  - `state`: `"idle" | "greeting" | "talking" | "fetching" | "walking" | "returning" | "charging" | "error"`.
  - `goal`: what Otto does right now, with the ticket, never a name ("Guiding A17 to Room B204").
  - `screen` (v3.1): `{ view, ticket?, title, step?, progress? }`, `view` one of `idle, checkin, pickup, walking, check_back, waiting, arrived, help`. Unknown views become `idle`, `progress` is kept in 0..1, a bad ticket is dropped.
  - Never refused for odd values: battery is rounded and kept in 0-100, a non-number becomes `null`, `charging` is only true for `true`, empty strings become `null`.
- `GET /api/robot/status?robot=nova` (dashboard) -> `{ robot, battery, charging, state, position, goal, detail, screen, lastSeenAt, online }`. `online` = heartbeat or poll in the last 45 s. A robot never seen -> `{ robot, online: false }`.

## Commands from staff (as in v2, plus ask)

`GET /api/robot/next` returns `guide` (see journeys.md), `say`, `ask` or `go_to`, oldest first, or `204`. A poll also counts as "seen".
- `say`: `{ id, robot, type: "say", text, createdAt }` - Otto says `text` word for word.
- `ask` (v3.1): `{ id, robot, type: "ask", text, createdAt }` - Otto treats it like something a person said (AgentOS query).
- `go_to`: `{ id, robot, type: "go_to", spot, createdAt }` - drive there and stay.
- `POST /api/robot/commands` (staff) `{ robot, type: "ask" | "say" | "go_to", text?, spot? }` -> `201 <command>`. `ask` text max 500 chars, `say` max 300.
- `GET /api/robot/commands?robot=nova&limit=10` (dashboard) -> last commands, newest first. Queued = no `deliveredAt`, delivered = `deliveredAt` without `doneAt`, done = `doneAt`.
- The robot posts `POST /api/robot/commands/:id/done` for every type when it is finished (for a guide: back at reception or onto the next job).

## Details the contract leaves open

- `POST /api/journeys` takes only `to`, `forPlaceId`, `pace`, `wheelchair`, `robot`. Any other field (a name, a note, a language) is refused with `400`, not ignored.
- `forPlaceId` must be a room, and `to` must be that room or its waiting room. The robot may create a journey when `to` is public, or when `forPlaceId` is a room with `selfCheckIn`. Everything else from the robot is `403`. Without an active reception place, `409`.
- A journey to a waiting room for a room gets its Teams card ("Your visitor has arrived ...") when the leg is `arrived` there, not when it is created. "Call in" = `next` to the room. Guides to the exit send no card.
- A leg the robot marks `failed` puts the journey back to `waiting_pickup` with that leg still `failed`. Staff then press "Send Otto again" = `POST /api/journeys/:id/retry` (same walk as a new leg and a new `guide`) or cancel.
- Leg updates (`pickup`, `walking`, `arrived`, `failed`) only work on the current leg. A leg that is `arrived` or `failed` can't change any more (`409`), and neither can anything while the journey is `with_staff` (events still can).
- Staff `with_staff` works from any open state. If Otto had not reported the arrival yet, the leg is set to `arrived` and its undelivered guide is dropped.
- `POST /api/journeys/:id/done` twice is fine (`already: true`), the Teams link shows "already done".
- Event types: the list in journeys.md, plus `teams_failed` (Teams did not take the card) from the server. The robot may send other lower-case types too; only the listed ones are counted. `feedback` needs `detail` = `up` or `down`.
- Queue: the line of a journey is its `forPlaceId`, else where it goes. Order in the line: called into the room itself first, then the ones seated or at the door (by arrival), then the ones still walking.
- `GET /api/stats?day=2026-10-17` (or `?since=<iso>`, or nothing = today in `CLINIC_TIMEZONE`) -> `{ day, journeys, guides, done, cancelled, avgWalkMinutes, lostPatients, feedbackUp, feedbackDown, activeNow, perPlace: [{ placeId, name, guides, done, avgWalkMinutes, lostPatients }] }`. Only numbers, kept per day and place in `daily_stats`.
- Live feed (v3.1): `POST /api/robot/feed` answers `{ ok, stored, dropped, showPatientText }`. Items `who: "patient", kind: "said"` are dropped while the demo switch is off. `GET /api/robot/feed?robot=nova` (dashboard) -> `{ items (newest first), showPatientText }`. `PATCH /api/robot/feed { showPatientText }` (staff) flips the demo switch. The feed lives in the server's memory only; on Vercel each running instance has its own.
- `GET /api/board?robot=nova` (dashboard) = everything the reception and room pages show in one request (places, open journeys with their place in line, help, robot status, last commands, feed, today's numbers).
- Help: `POST /api/help { robot, summary, urgency, journeyId?, ticket? }` -> `201 { ok, id, notified, help }`. `GET /api/help?open=1` (dashboard and robot), `PATCH /api/help/:id { status: "open" | "done" }` (staff).
- Retention runs on every write to journeys, places, help and commands: journeys that ended 12 h ago go with their legs, events, commands and feed. Open journeys nobody touched for 24 h go too. Old `say` / `ask` / `go_to` commands and handled help requests go after 12 h.
