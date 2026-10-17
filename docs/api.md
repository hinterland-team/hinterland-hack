# Nova Clinic - API between robot app and backend

Backend = `team-inbox/` (Next.js). Robot app = `robot-app/` (Android, AgentOS SDK 0.4.5).
All robot calls send `Authorization: Bearer <ROBOT_TOKEN>` (env `TEAM_INBOX_TOKEN` on the backend, `teamInbox.token` in robot-app/local.properties).
Base URL on the robot = `teamInbox.url` without the path, e.g. `http://192.168.2.214:3000`.

## Data

Doctor
```
{ id, name: "Dr. Blue", spot: "Dr. Blue", avgConsultMinutes: 10, teamsWebhookUrl?: string, active: boolean, createdAt }
```
`spot` = the exact map spot name the robot drives to. Doctors live in the db and are edited in the dashboard, not in code.

Visit
```
{
  id, patientName, doctorId: string | null, doctorName, appointmentTime?: "10:00", language?: "de",
  robot: "nova",
  status: "checked_in" | "notified" | "called" | "escorting" | "with_doctor" | "done" | "cancelled",
  createdAt, updatedAt,
  events: [{ at, type, detail? }]   // timeline shown on robot screen and dashboard, oldest first
}
```
No symptoms, no reason for the visit, no birthday or insurance data. Ever.
`doctorId` is `null` when no doctor matched. Then `doctorName` is what the robot sent.
Robot names are stored lower case (`"Nova"` -> `"nova"`).

Robot command (doctor -> robot)
```
{ id, robot, type: "call_patient", visitId, patientName, doctorName, spot, createdAt, deliveredAt?, doneAt? }
```
v2 adds `say` and `go_to` commands, see the end of this file.

Event types in `visit.events`. `detail` is a short English sentence, so the robot can show it as is or map `type` to its own text.

| type | when |
|---|---|
| `checked_in` | visit created ("Checked in by Nova") |
| `notified` | Teams card sent to the doctor |
| `teams_skipped` | no Teams webhook set up |
| `teams_failed` | Teams did not accept the card |
| `doctor_unknown` | no doctor matched the name |
| `reception_notified` | "doctor not found" card sent to the practice channel |
| `doctor_assigned` | staff picked the doctor on the board |
| `called` | doctor is ready (Teams button or board) |
| `called_again` | staff sent the robot again after it gave up |
| `robot_got_call` | robot fetched the command from /api/robot/next |
| `escorting`, `with_doctor`, `done`, `cancelled`, `called`, ... | status set with PATCH. `detail` = what the robot sent, or a default |
| `robot_back` | robot posted command done |

## Endpoints

| Method | Path | Who | What |
|---|---|---|---|
| POST | /api/visits | robot | body `{ patientName, doctor, appointmentTime?, language?, robot }`. Matches `doctor` to a doctor by name (case/space-insensitive, "Dr Blue" = "Dr. Blue"). Creates visit, sends Teams card, returns `{ visit, notified: boolean, doctorKnown: boolean, spot? }` |
| GET | /api/visits?active=1 | dashboard | visits not done/cancelled, newest first |
| GET | /api/visits?since=<iso> | dashboard | visits created since then plus all still open, newest first |
| GET | /api/visits/:id | robot, dashboard | one visit |
| PATCH | /api/visits/:id | robot, dashboard | body `{ status?, detail?, doctorId? }` -> updates status and appends an event. Same status with a `detail` only adds the event. `doctorId` re-assigns the doctor (and sends the Teams card if the visit is still waiting) |
| POST | /api/visits/:id/call | dashboard / Teams link | doctor is ready -> status `called`, creates robot command `call_patient`. Body `{ again: true }` (board) sends the robot again when it put the visit back to `called` |
| GET | /api/visits/:id/call?k=<CALL_KEY> | Teams card button | same as POST but a link (Teams cards can only open URLs), shows a small "Nova is bringing the patient" page |
| GET | /api/robot/next?robot=nova | robot (poll every 3 s) | oldest undelivered command for this robot or `204`; marks it delivered |
| POST | /api/robot/commands/:id/done | robot | command finished |
| POST | /api/help | robot | body `{ robot, summary, urgency: "normal" \| "urgent" }` -> urgent Teams message to the practice channel |
| GET | /api/help?open=1 | dashboard, robot (bearer) | help requests, newest first (`open=1`: only the ones nobody took). The robot polls it during an urgent help request and stops waiting once its request is closed on the board |
| PATCH | /api/help/:id | dashboard | body `{ status: "open" \| "done" }` |
| GET | /api/stats?since=<iso> | dashboard | check-ins today, avg wait (checked_in -> called), per doctor counts. `since` = local midnight, default server midnight |
| GET/POST/PATCH | /api/doctors | dashboard (robot: GET only, with bearer) | manage doctors. Robot reads the list to find a doctor's `spot` when it has no visit/command with one. GET returns an array. POST `{ name, spot?, teamsWebhookUrl?, active? }` (spot defaults to name), PATCH `{ id, ...fields }` (`teamsWebhookUrl: ""` removes it) |

### Responses

- Errors are `{ "error": "..." }` with status 400 (bad body), 401 (token), 404 (unknown id) or 409 (state does not allow it).
- `POST /api/visits` -> `201 { visit, notified, doctorKnown, spot? }`. `spot` only when `doctorKnown`. An unknown doctor still creates the visit, and staff picks the doctor on the board.
- `GET /api/visits/:id`, `PATCH /api/visits/:id` -> the visit object.
- `POST /api/visits/:id/call` -> `200 { visit, command, already }`. Calling twice does not send the robot twice (`already: true`). `409` when the visit is closed or has no doctor yet.
- `GET /api/robot/next` -> `200 <command>` or `204` with no body. It also marks the robot as online on the dashboard.
- `POST /api/robot/commands/:id/done` -> `200 { ok: true, command }`.
- `POST /api/help` -> `201 { ok: true, id, notified, help }`.
- `GET /api/stats` -> `{ since, checkInsToday, avgWaitSeconds, avgWaitMinutes, waitingNow, withDoctorNow, doneToday, perDoctor: [{ doctorId, doctorName, checkIns, waiting, withDoctor, done, avgWaitSeconds }], robots: [{ name, lastSeenAt }] }`.

### Auth

- Robot routes (`POST /api/visits`, `GET /api/robot/next`, `POST /api/robot/heartbeat`, `POST /api/robot/commands/:id/done`, `POST /api/help`) need the bearer token when `TEAM_INBOX_TOKEN` is set. They never ask for the dashboard password.
- `GET /api/robot/status` and `GET/POST /api/robot/commands` are dashboard routes (password, or the bearer).
- Dashboard pages and dashboard routes use basic auth when `DASHBOARD_PASSWORD` is set (any user name). A valid bearer token also works there, so the robot can read visits and doctors and PATCH visits.
- The Teams link checks `k` against `CALL_KEY`.

## Teams

Incoming webhook from a Teams "Workflows" flow (Post to a channel when a webhook request is received). Payload = Adaptive Card:
```
{ "type": "message", "attachments": [{ "contentType": "application/vnd.microsoft.card.adaptive", "content": { AdaptiveCard 1.4 } }] }
```
Card: title "Patient arrived", facts (Patient, Doctor, Appointment, Checked in at), button `Action.OpenUrl` "Call patient in" -> `/api/visits/:id/call?k=...`.
Per-doctor webhook if set, else `TEAMS_WEBHOOK_URL`. If none is set, nothing is sent and `notified=false`.
Links in cards use `PUBLIC_BASE_URL`, or the address the robot used to reach the backend when it is empty.
Unknown doctor -> card "Patient arrived - doctor not found" to `TEAMS_WEBHOOK_URL`. Help -> card "Help needed at reception" (urgent: "Urgent: ...") to `TEAMS_WEBHOOK_URL`.

## Flow

1. Patient talks to Nova. AgentOS LLM fills `CHECK_IN` -> POST /api/visits -> Teams card -> robot says the reply.
2. Robot screen shows the timeline from `visit.events`.
3. Doctor taps "Call patient in" -> command `call_patient`.
4. Robot polls /api/robot/next, says "Herr Weber, Dr. Blue ist jetzt bereit", PATCH `escorting`, navigates to `spot`, PATCH `with_doctor`, says goodbye, drives back to "Reception Point", POST command done.
5. Dashboard shows everything live; doctor sets `done`.

If the robot can't get there (blocked, lost position, spot not on the map), it PATCHes the visit back to the status it had before (`called` for a call) with `detail` = what went wrong, POSTs /api/help (normal) and drives back to reception.
The board then shows that detail on the card with a "Send Nova again" button (POST `/call` with `{ again: true }`), which closes the old command and queues a new one.

## v2 - Nova acts on its own (added 17.10.2026)

### Queue and waiting time
- Doctor gets `avgConsultMinutes` (number, default 10, editable on /doctors). `POST/PATCH /api/doctors` take it as a number from 1 to 120 (rounded to whole minutes), anything else -> `400`.
- Every visit object returned by the API (POST result `visit`, GET one, GET list, also PATCH and `/call`) gets two computed fields:
  - `queuePosition`: 1-based place among this doctor's waiting visits (status `checked_in` or `notified`, oldest `createdAt` first). `null` when the visit is not waiting or has no doctor.
  - `estimatedWaitMinutes`: `(queuePosition - 1) * avgConsultMinutes`, plus `ceil(avgConsultMinutes / 2)` when the doctor has someone `with_doctor` or `escorting` right now. `null` when `queuePosition` is null.
- The robot tells the patient after check-in ("2 Patienten vor Ihnen, etwa 10 Minuten") and answers `WAITING_STATUS` with it.

### Robot heartbeat and status
- `POST /api/robot/heartbeat` (robot, bearer) every 15 s, body `{ robot, battery: 0-100 | null, charging: boolean, state, position: string | null, goal: string | null, detail?: string }` -> `204`.
  - `state`: `"idle" | "greeting" | "talking" | "fetching" | "walking" | "returning" | "charging" | "error"`.
  - `goal`: what Nova is doing right now in plain English ("Walking Max Weber to Dr. Blue"), shown on the board.
  - A heartbeat is never refused for odd values: `battery` is rounded and kept in 0-100, a non-number becomes `null`, `charging` is only `true` for `true`, `state` is stored lower case, empty strings become `null`.
- `GET /api/robot/status?robot=nova` (dashboard) -> `{ robot, battery, charging, state, position, goal, detail, lastSeenAt, online }`. `online` = heartbeat or poll in the last 45 s. Unknown robot -> `{ robot, online: false }`. A robot that only polled so far (no heartbeat yet) -> `battery`, `state`, `position`, `goal`, `detail` are `null`, `charging` is `false`.
- The board shows a robot panel: online/offline, battery, state, position, goal.

### More robot commands (staff -> robot)
`/api/robot/next` can now return three command types (same shape, unused fields absent):
- `call_patient` (as before).
- `say`: `{ id, robot, type: "say", text, createdAt }` - Nova says `text` out loud (staff typed it on the board).
- `go_to`: `{ id, robot, type: "go_to", spot, createdAt }` - Nova drives to `spot` and stays there (e.g. back to reception, or to the charger).
- `POST /api/robot/commands` (dashboard) body `{ robot, type: "say" | "go_to", text?, spot? }` -> `201 <command>`. `400` when `type` is something else, `say` has no `text` (max 300 chars) or `go_to` has no `spot`.
- `GET /api/robot/commands?robot=nova&limit=10` (dashboard, added for the board) -> the last commands of every type, newest first (`limit` 1-50, default 10). Queued = no `deliveredAt`, delivered = `deliveredAt` without `doneAt`, done = `doneAt`.
- Robot posts `/api/robot/commands/:id/done` for every type when finished.
The board's robot panel has "Say something" (text field) and "Send to" (spot field with suggestions from the doctors' spots plus `ROBOT_HOME_SPOT`, env, default "Reception Point").
