# Nova Clinic - backend and reception dashboard

Staff decide where a patient goes, Nova walks with them. A patient is only a ticket like `A17`: no names, no reasons, nothing recorded. The contract is `../docs/journeys.md`, the extra API details are in `../docs/api.md`.

## Pages

- `/` - reception. "Guide a patient": type a name or room (or tap a card), pick the pace, press Enter or "Send Nova". The ticket then shows large ("Tell the patient: A17") with what happens next. Next to it every open journey, grouped by room: on the way, waiting with place in line and minutes, with staff, and the buttons "With me", "Done → exit", "Next room…", "Call in" and "Cancel". "Nova live" shows the robot's screen, the conversation, the current journey's timeline, and has "Tell Nova anything", "Say it out loud", "Send Nova to" and the demo switch "Show what patients say". Help alerts sit on top while open. Today's numbers are anonymous counts. Updates every 2 s.
  - Keyboard in the quick bar: Enter sends, 1 / 2 / 3 = pace, W = wheelchair, Esc = clear, arrow keys move between matches.
- `/room` and `/room/<placeId>` - the room view for doctors' phones: who is in the waiting room ("Call in"), who is coming, who is here, with big buttons.
- `/places` - places Nova can walk to: name, room number, map spot, kind (room, waiting room, reception, exit, other), minutes per patient, waiting room, self check-in on the robot, public, own Teams webhook. Nothing is built in: start with the reception, the exit and your rooms.
- `/simulate` - plays the robot (poll, pickup, walking with check-backs, lost patient, arrival, feedback, heartbeat with screen, conversation, self check-in, waiting status) so the loop can be shown without hardware.
- `/login` - staff sign-in when `DASHBOARD_PASSWORD` is set.

## Run

```
npm install
cp .env.example .env.local   # then fill in the values
npm run dev:lan              # 0.0.0.0:3000, so the robot can reach the laptop over Wi-Fi
```

Without `DATABASE_URL` everything lives in memory and is gone after a restart. With a Neon database, set `DATABASE_URL` and run `npm run db:setup`. Run it again after an update: it only adds what is missing. On an old v1/v2 database it moves the doctors to places (kind room) once and drops the old visits tables, because they hold patient names.

## Deploy (Vercel)

1. Create a Neon database (Vercel Marketplace or neon.tech) and run `npm run db:setup` once with its `DATABASE_URL` in `.env.local`.
2. In the Vercel project set `DATABASE_URL`, `TEAM_INBOX_TOKEN`, `DASHBOARD_PASSWORD`, `SESSION_SECRET`, `CALL_KEY`, `CLINIC_TIMEZONE` (e.g. `Europe/Berlin`), and if used `TEAMS_WEBHOOK_URL` and `PUBLIC_BASE_URL`.
3. Deploy. Everything runs on the Node runtime and nothing is written to disk.

The conversation feed never goes to the database (by design), it lives in the memory of the running server. On Vercel each instance has its own, so the feed can look empty there. The login rate limit is kept in the database.

## Env

| Name | What |
|---|---|
| `DATABASE_URL` | Neon Postgres. Empty = in memory |
| `TEAM_INBOX_TOKEN` | robot token, same as `teamInbox.token` in `robot-app/local.properties` |
| `DASHBOARD_PASSWORD` | shared staff password for `/login` (and basic auth for scripts). Empty = no login |
| `SESSION_SECRET` | optional, mixed into the session cookie signature |
| `CALL_KEY` | secret in the Teams card links |
| `TEAMS_WEBHOOK_URL` | Teams Workflows webhook of the practice channel. Used when a place has no own webhook, and for help requests |
| `PUBLIC_BASE_URL` | address phones can open, for links in Teams cards. Empty = the address the request came in on |
| `CLINIC_TIMEZONE` | e.g. `Europe/Berlin`: tickets per day, today's numbers, clock times in cards |

## Demo without the robot

1. Open `/places` and add the reception, the exit, a waiting room and two rooms (one with the waiting room and self check-in on).
2. Open the reception in one window and `/simulate` in another.
3. On the reception type the room, press 2 for slow, press Enter. Tell the patient the ticket.
4. On `/simulate` press "Check for jobs" and step through pickup, walking, check-backs and arrival. The reception shows the robot's screen and the conversation live.
5. On the reception (or the room view) press "With me", then "Done → exit" or "Next room…".
6. Check in on `/simulate` as a patient for the room with the waiting room: the room gets a Teams card when the patient sits there, and "Call in" brings them over.

Keep automatic checking off on `/simulate` while the real robot runs, otherwise the simulator takes its jobs.
