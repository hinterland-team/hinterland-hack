# Nova Clinic v3 - patient-aware guide (contract)

Replaces the visit/check-in flow of `api.md` (v1/v2). Where they differ, this file wins.

**The idea:** the nurse or receptionist decides where a patient goes. Otto only gets an anonymous destination and a ticket number, walks *with* the patient (adapts the speed, checks they still follow, asks how they are), and staff call Otto back for the next room or the exit. No names, no diagnoses, no IDs, no free text about the patient, nothing recorded.

## 1. Privacy rules (hard)

- No patient name, birthday, insurance number, reason for the visit, symptoms or free-text notes anywhere: not in the API, the db, Teams cards, logs or the robot screen.
- A patient is only a **ticket**: one letter + two digits (`A17`), random among the free ones of today, shown on the robot screen and the staff screen. Not linked to anything else.
- Needs are fixed choices only: `pace` = `normal` | `slow` | `very_slow`, `wheelchair` = true/false. The language is not stored: Otto answers in the language the patient speaks.
- The camera is only used live on the robot (person detection: is someone close behind). No images, no faces, no recordings leave the robot.
- Finished journeys (`done` / `cancelled`) are deleted 12 h after they end. Anonymous counters (guides per day, average walk time, lost-patient events per day) are all that stays.
- The robot screen shows a short privacy line during a guide: "Otto only knows your ticket and the room. Nothing is recorded."
- Otto never decides where a patient should go medically. Doctor rooms are only set by staff. Patients can only ask Otto for public places (exit, reception, places marked `public`).

## 2. Data

Place (replaces Doctor)
```
{ id, name: "Dr. Blue", room?: "B204", spot: "Dr. Blue", kind: "room" | "exit" | "reception" | "other",
  public: boolean, avgMinutes: 10, teamsWebhookUrl?: string, active: boolean, createdAt }
```
- `spot` = exact map spot name on the robot. `public` places can be asked for by patients (exit and reception are always public). `avgMinutes` = time with staff, for wait estimates (1-120).
- Places are edited on the dashboard (no lists in code). One place of kind `reception` = where Otto picks patients up and waits; one of kind `exit`.

Journey (replaces Visit)
```
{ id, ticket: "A17", robot: "nova",
  pace: "normal" | "slow" | "very_slow", wheelchair: boolean,
  status: "waiting_pickup" | "walking" | "arrived" | "with_staff" | "done" | "cancelled",
  legs: [ { id, fromPlaceId, toPlaceId, toName, toSpot, status: "queued" | "pickup" | "walking" | "arrived" | "failed", startedAt?, arrivedAt? } ],
  createdBy: "staff" | "robot", createdAt, updatedAt,
  events: [ { at, type, detail? } ],
  queuePosition?, estimatedWaitMinutes? }
```
- The current leg is the last one. `status` follows it: leg `pickup` (Otto on its way to or waiting at the pickup point) -> journey `waiting_pickup`; `walking` -> `walking`; `arrived` -> `arrived`; staff tap "with me" -> `with_staff`.
- Event types (detail = short English sentence, never patient info): `created`, `teams_notified`, `teams_skipped`, `robot_assigned`, `pickup_started`, `patient_confirmed`, `walking`, `check_back` ("Patient close, all fine"), `slowed_down`, `waiting_for_patient`, `lost_patient`, `arrived`, `with_staff`, `next_leg`, `done`, `cancelled`, `failed`.
- `queuePosition` / `estimatedWaitMinutes` for journeys at a room: place in line among `arrived` + `walking` journeys to the same place, `(position - 1) * avgMinutes`, plus `ceil(avgMinutes / 2)` when someone is `with_staff` there.

Robot command (server -> robot, `/api/robot/next`)
```
{ id, robot, type: "guide", journeyId, legId, ticket, pace, wheelchair,
  from: { name, spot }, to: { name, spot, room? }, createdAt }
{ id, robot, type: "say", text, createdAt }
{ id, robot, type: "go_to", spot, createdAt }
```
`call_patient` is gone.

## 3. API (team-inbox)

Auth as before: robot routes need `Authorization: Bearer <TEAM_INBOX_TOKEN>`. Dashboard pages and routes need the staff login when `DASHBOARD_PASSWORD` is set (new: a friendly login page that sets an httpOnly session cookie, plus basic auth still accepted for scripts). Teams links check `CALL_KEY`.

| Method | Path | Who | What |
|---|---|---|---|
| GET/POST/PATCH/DELETE | /api/places | dashboard (robot: GET) | manage places. POST `{ name, room?, spot?, kind, public?, avgMinutes?, teamsWebhookUrl? }` (spot defaults to name) |
| POST | /api/journeys | dashboard; robot only for public places | `{ to: placeId, pace?, wheelchair?, robot? }` -> `201 { journey, command }`. Creates the ticket, the first leg (from reception to `to`), queues a `guide` command, sends the Teams card to the destination place (or the default channel). The robot may only create journeys to public places (`403` otherwise) |
| GET | /api/journeys?active=1 | dashboard, robot | open journeys, newest first |
| GET | /api/journeys/:id | dashboard, robot | one journey |
| GET | /api/journeys/by-ticket/:ticket | robot | today's open journey with that ticket, `404` |
| PATCH | /api/journeys/:id | robot, dashboard | `{ legId?, status?, event?: { type, detail? } }` -> leg/journey status and an event. Robot: `pickup` -> `walking` -> `arrived` or `failed`. Staff: `with_staff` |
| POST | /api/journeys/:id/next | dashboard, Teams link | `{ to: placeId }` (a room or the exit) -> new leg from the current place, `guide` command for it, Teams card to the new place. `409` when not `arrived` / `with_staff` |
| GET | /api/journeys/:id/next?to=<placeId>&k=<CALL_KEY> | Teams button | same as POST, answers with a small friendly page "Otto is on the way" |
| POST | /api/journeys/:id/done | dashboard, Teams link (GET with k) | journey `done`, Otto is not needed any more |
| POST | /api/journeys/:id/cancel | dashboard | `cancelled`, open command for it removed |
| GET | /api/robot/next?robot=nova | robot (poll every 3 s) | oldest undelivered command or `204` |
| POST | /api/robot/commands/:id/done | robot | command finished |
| POST | /api/robot/heartbeat | robot | as in v2, `goal` like "Guiding A17 to Room B204" (ticket, never a name) |
| GET | /api/robot/status | dashboard | as in v2 |
| POST | /api/robot/commands | dashboard | `say` / `go_to` as in v2 |
| POST | /api/help | robot | `{ robot, summary, urgency }` as before, plus optional `journeyId` / `ticket`. Summary never contains health details |
| GET/PATCH | /api/help | dashboard | as before |
| GET | /api/stats?since= | dashboard | today: guides, done, average walk minutes, lost-patient events, per place counts. Only anonymous numbers |

Teams card (Adaptive Card 1.4, per place webhook else `TEAMS_WEBHOOK_URL`): title "A17 is on the way", facts: Ticket, To (place + room), Pace (+ wheelchair), Expected in (minutes from the robot's walk estimate or 2-5 min), buttons `Action.OpenUrl` "Done - guide to exit" (`/next?to=<exit>`), "Done - finished here" (`/done`). No name.

Retention: on every write, delete journeys whose end is older than 12 h (and their commands/events). Counters are kept per day in a small `daily_stats` table.

## 4. The robot (robot-app)

Flow for a `guide` command:
1. **Pickup:** drive to `from.spot` if not there. Screen: ticket very large, "Please come to me", destination name, a "That's me" button. Speak once in German and English: "Ticket A17, please come to Otto." The patient confirms by tapping, or by saying the ticket (action `CONFIRM_TICKET`). Wait up to 3 min, remind every 45 s, then mark the leg `failed` ("Nobody came") and go home.
2. **Start:** ask about the pace only if staff didn't set it to slow/very slow: "Shall I walk normally or a bit slower?" (action `SET_PACE`). Speed per pace from a config table in code (normal ≈ 0.6 m/s, slow ≈ 0.4, very_slow ≈ 0.25; wheelchair = slow; values checked against what RobotAPI navigation accepts).
3. **Walking with check-backs:** navigate to `to.spot` at that speed. About every 25 s of driving, and right after the robot had to wait for an obstacle: pause, turn around towards the patient, look for a person within 3 m (PersonApi, live only), then:
   - person close -> short friendly line in the patient's language ("All good? We're almost there."), event `check_back`, turn back, go on;
   - person 3-6 m away -> "I'll wait for you", wait up to 20 s, slow down one step, event `waiting_for_patient` / `slowed_down`;
   - nobody -> call out twice, wait 30 s, event `lost_patient`, `POST /api/help` ("Lost the patient with ticket A17 near <last spot>"), wait 60 s more, then leg `failed`, back to reception.
   The patient can say "slower"/"faster"/"wait" anytime (`SET_PACE`, `PAUSE_WALK`).
4. **Arrival:** leg `arrived`, "This is Room B204. Please take a seat, the team knows you're here." Event `arrived`. Then drive back to reception (or take the next command).
5. **Next leg / exit:** when staff send a new leg, Otto drives to that room (pickup there), same flow, destination can be the exit ("Goodbye and get well soon").
- While walking/fetching/returning: busy hold to Nova Deploy (as now).
- Walk-ins without a ticket: Otto greets, explains it can bring them to public places (`GUIDE_TO_PLACE`, only `public` places from `/api/places`) or that the reception desk assigns rooms. Questions about the wait: `WAITING_STATUS { ticket }`. Emergencies: `CALL_STAFF` as before (112 hint for urgent).
- Robot screen: friendly, calm, large: idle (mascot + "Hello, I'm Otto" + what it can do), pickup (ticket huge), walking (destination, a simple progress line, the current step, privacy line), check-back, arrived, help. Big touch targets, high contrast, readable from 2 m.
- Every speech line is generated by AgentOS in the patient's language from a short English instruction (as now), so no language lists in code.

## 5. v3.1 additions (the original Terra brief + more interaction)

The Terra brief asks that the **visitor** gives their concern and contact person by voice or on the robot's touch screen, the contact gets a Teams message ("Ihr Besuch ist eingetroffen"), and Otto walks the visitor to the **waiting room**. So both ways start a journey: staff at the desk, or the patient at the robot.

### Self check-in on the robot (the brief's "intuitive input mask")
- New place fields: `kind` can also be `waiting` (a waiting room); a room can have `waitingPlaceId` and `selfCheckIn: true` (shown to patients on the robot).
- Robot screen, 3 big steps, no typing: 1) "Who are you here to see?" = the `selfCheckIn` rooms as large tiles (name + room) + "I'm not sure" (-> reception), 2) "How shall I walk?" = normal / slower / wheelchair, 3) "Confirm" -> `POST /api/journeys { to: <that room's waiting place or the room itself>, forPlaceId: <room>, pace, wheelchair, robot }` (robot may create these: `selfCheckIn` rooms and public places). Same by voice: action `CHECK_IN_FOR { place }`. Still no name: the visitor gets a ticket like everyone.
- Teams card for a self check-in: "Your visitor has arrived - ticket A17, waiting in <waiting room>", buttons "Call in" (`/next?to=<room>`), "Done - guide to exit".
- When the doctor taps "Call in" (dashboard, room view or Teams), Otto picks the ticket up in the waiting room and walks to the room.

### Open commands
- New command `{ type: "ask", text }`: Otto treats the text like something a person said to it (AgentOS `query`), so its AI decides what to do with all its actions (talk, guide, wait, call staff). Dashboard: "Tell Otto anything" box. No fixed command list.
- Patients can say anything at any time; Otto's AI answers, and the actions cover the physical part.

### Quick entry for staff (data collection in seconds)
- On the reception page a quick bar: start typing a place name or room, the matching places filter live (from the db, fuzzy), Enter sends Otto with the selected pace; keyboard: 1 / 2 / 3 = pace, W = wheelchair, Esc = clear. Mouse/touch users use the cards. No word lists: only place names from the db.

### More interaction while walking
- Otto talks at milestones (start, half way, almost there, arrival) with short friendly lines, asks at each check-back ("All good? Shall I slow down?") and reacts to the answer; the patient can interrupt any time ("wait", "slower", "how far is it?", "where are we?"): `PAUSE_WALK`, `SET_PACE`, `WALK_STATUS` (answers remaining distance/time from navigation) and free talk.
- At arrival a one-tap feedback on the robot screen: "How was the walk?" thumbs up / down (optional). Only the anonymous count goes to the server: `PATCH /api/journeys/:id { event: { type: "feedback", detail: "up" | "down" } }`, counted in daily_stats.

### Live tracking on the dashboard (privacy kept)
- Heartbeat gets `screen`: `{ view: "idle" | "checkin" | "pickup" | "walking" | "check_back" | "waiting" | "arrived" | "help", ticket?, title, step?, progress?: 0..1 }` -> the dashboard shows a live mirror card of the robot screen.
- `POST /api/robot/feed` (robot bearer) `{ robot, journeyId?, items: [{ at, who: "nova" | "patient", kind: "said" | "intent" | "action", text }] }` -> live conversation feed on the dashboard. Otto's own lines are shown as said. For the patient only the understood intent ("asked to slow down", "confirmed ticket A17"), unless the dashboard switch "Show what patients say (demo)" is on; the response `{ showPatientText: boolean }` tells the robot. The feed lives only in memory (last 200 items, cleared with the journey and after 12 h), never in the db.
- Dashboard "Live" panel: robot screen mirror + conversation feed + the current journey's timeline.

### App icon
- Nova Clinic gets a proper adaptive launcher icon (foreground + background vectors + monochrome): friendly, recognisable on the robot's home screen at small size. Otto's face theme, calm medical blue/teal, no text.

## 6. v3.2 - smarter walk, logs, live map, patient phone companion

Field test 17.10: the walk works, but the patient can't talk to Otto from behind (mics and screen face forward, wheels are loud), and Otto notices too late that nobody follows (it only looks every ~25 s). The online server was not reachable from the robot's Wi-Fi.

### 6.1 Server choice on the robot
- New `GET /api/health` (no auth) -> `{ ok: true, version, time }`.
- Robot: `teamInbox.urls` in local.properties (comma list, first = preferred, e.g. the online url then the laptop url); falls back to `teamInbox.url`. Otto checks `/api/health` on start and every 60 s, uses the first that answers, and shows which one on the staff menu and in the heartbeat (`server`). No other change: both servers have the same API.

### 6.2 Logs
- `POST /api/robot/logs` (robot bearer) `{ robot, items: [{ at, level: "debug"|"info"|"warn"|"error", source: "app"|"net"|"nav"|"voice"|"brain"|"vision"|"lidar", text }] }`. Stored in the db, last 24 h / 5000 rows per robot. Logs never contain patient words or names (same rules as the feed).
- Dashboard **Logs** page: live tail (3 s), filter by level and source, search, pause, copy. Errors also show as a small count on the robot card.
- On the robot: a staff menu (long-press the Nova Clinic logo 3 s) with the same log, the server status, version, chassis status and battery.

### 6.3 Faster "is the patient still behind me?"
- LiDAR: Otto enables RobotApi radar data (`setNavigationLineData` + `STATUS_LINE_DATA`, see RobotAPI_en.md) while guiding and looks for a leg-like cluster behind it (points 0.3-2.5 m behind, inside the rear sector). On the first data it logs which angles the scan covers, so we learn if the back is visible on this robot.
- If the scan covers the back: follower watch is continuous; no follower for 4 s -> immediate check-back (turn + camera). If it does not: adaptive schedule.
- Adaptive schedule either way: first check 10 s after start, then 10-30 s depending on how close the patient was last time, plus one check right after every bigger turn (heading change > 45 deg from `getPosition`) and after obstacle waits.
- Camera (PersonApi) confirms at each check-back, live only, nothing stored.

### 6.4 Talking at the stops and from behind
- Each check-back is a short face-to-face talk window: Otto turns, asks a yes/no question ("All good? Shall I slow down?"), listens ~6 s, answers, then goes on.
- **Patient phone companion:** the pickup screen shows a QR code `<PUBLIC_BASE_URL>/walk/<walkCode>` (walkCode = 8 random characters per journey, in the `guide` command, valid only while the journey is open). The phone page (no login, no personal data) shows ticket, destination, distance left, and big buttons **Wait**, **Slower**, **Faster**, **Go on**, **Talk** (browser speech-to-text, the text goes to Otto as `ask`). Rate limited.
- New command `{ type: "walk_control", journeyId, action: "wait"|"resume"|"slower"|"faster" }` from the phone page or the dashboard.

### 6.5 Live map on the dashboard
- `POST /api/robot/map` (robot bearer), ~1 per second while moving, every 15 s idle: `{ robot, mapName?, pose: { x, y, theta }, places: [{ name, x, y }] (on change / every 60 s), scan: [[x, y], ...] (map frame, at most 240 points), follower?: { x, y, source: "lidar"|"camera" }, target?: { name, x, y }, remainingMeters? }`. The latest one per robot is kept (db row), plus a short trail (last 120 poses).
- Dashboard **Map** panel/page: places as labelled dots, Otto as an arrow with its trail, the LiDAR points, the patient's estimated position, the target and the remaining distance. Fit-to-view, zoom, light/dark.
- Click a place on the map -> "Send Otto here" (`go_to`, same as "Send Otto to").

### 6.6 More of the list
- Waiting room stop: places of kind `waiting` (needs a map spot) — already in the data, now in the robot flow and on the map.
- Blocked path: after asking people to step aside twice, Otto tries the optional place field `viaSpot` (a waypoint) once, then calls staff.
- Out of service: when the chassis is not available (app not started from the home screen, emergency stop, suspended), the robot shows a clear screen ("I can't drive right now - please start me from the home screen") and the heartbeat says `state: "error"` with the reason; the dashboard shows a warning on the robot card.
- Email as second channel: place field `notifyEmail`; when `RESEND_API_KEY` + `EMAIL_FROM` are set the same message as the Teams card goes out by email (plain REST call, no name, ticket + room only).
- Integration for the nurse's system: `POST /api/integrations/journeys` with header `X-Api-Key: <INTEGRATION_KEY>`, body `{ to: placeId | place name, pace?, wheelchair? }` -> same as a staff journey, returns the ticket.
- `docs/demo.md`: a 3-minute demo script (who stands where, what to say, what the judges see on the dashboard, plan B without Wi-Fi).
