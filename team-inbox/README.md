# Nova Clinic - backend and reception board

The robot checks patients in here, doctors get a Teams card, and the board shows every patient live. The API is described in `../docs/api.md`.

## Pages

- `/` - reception board. One lane per doctor, one column per step (checked in, doctor notified, called in, escorting, with doctor, done). Updates every 2 s.
  - Waiting patients show their place in line and a rough wait ("#2 in line, ~10 min").
  - The robot panel under the numbers shows online or offline, battery, what Nova is doing and where. Staff can make Nova say something or send it to a spot, and see the last commands (queued, delivered, done).
- `/doctors` - add and edit doctors: name, map spot, minutes per patient (for the wait estimate), optional Teams webhook, active.
- `/simulate` - acts as the robot for demos without hardware. It sends the same requests as the robot app, and the server adds the token. It can also send heartbeats and handle say and go_to commands.

## Run

```
npm install
cp .env.example .env.local   # then fill in the values
npm run dev:lan              # 0.0.0.0:3000, so the robot can reach the laptop over Wi-Fi
```

Without `DATABASE_URL` everything lives in memory and is gone after a restart. With a Neon database, set `DATABASE_URL` and run `npm run db:setup` once. Run it again after an update: it only adds what is missing.

## Env

| Name | What |
|---|---|
| `DATABASE_URL` | Neon Postgres. Empty = in memory |
| `TEAM_INBOX_TOKEN` | robot token, same as `teamInbox.token` in `robot-app/local.properties` |
| `TEAMS_WEBHOOK_URL` | Teams Workflows webhook of the practice channel. Used when a doctor has no own webhook, and for help requests |
| `CALL_KEY` | secret in the "Call patient in" link |
| `PUBLIC_BASE_URL` | address phones can open, for links in Teams cards. Empty = the address the robot used |
| `DASHBOARD_PASSWORD` | optional basic auth for the board and dashboard APIs, any user name |
| `CLINIC_TIMEZONE` | optional, e.g. `Europe/Berlin`, for clock times in cards when the server runs in UTC |
| `ROBOT_HOME_SPOT` | map spot the board offers first under "Send to". Default `Reception Point` |

## Demo without the robot

1. Open `/doctors` and add two doctors.
2. Open the board in one window and `/simulate` in another.
3. Check in a patient on `/simulate`. The card shows up on the board.
4. Press "Call in" on the board (or the Teams button). On `/simulate`, press "Check for commands" and step through walking, arrived and back.
5. Press "Done" on the board.
6. Send a heartbeat on `/simulate` and the robot panel goes online. Type something under "Say something" on the board, then pick it up on `/simulate` with "Check for commands".

Turn off auto checking on `/simulate` while the real robot is running, otherwise the simulator takes its commands.
