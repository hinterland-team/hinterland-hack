# Nova Clinic - backend and reception dashboard

Staff decide where a patient goes, Otto walks with them. A patient is only a ticket like `A17`: no names, no reasons, nothing recorded. The contract is `../docs/journeys.md`, the extra API details are in `../docs/api.md`.

## Pages

- `/` - reception. "Guide a patient": type a name or room (or tap a card), pick the pace, press Enter or "Send Otto". The ticket then shows large ("Tell the patient: A17") with what happens next. Next to it every open journey, grouped by room: on the way, waiting with place in line and minutes, with staff, and the buttons "With me", "Done → exit", "Next room…", "Call in" and "Cancel". "Otto live" shows the robot's screen, the conversation, the current journey's timeline, and has "Tell Otto anything", "Say it out loud", "Send Otto to" and the demo switch "Show what patients say". Help alerts sit on top while open. Today's numbers are anonymous counts. Updates every 2 s.
  - Keyboard in the quick bar: Enter sends, 1 / 2 / 3 = pace, W = wheelchair, Esc = clear, arrow keys move between matches.
  - The robot card also shows the server Otto uses, errors of the last hour (links to the logs), a red "Out of service" box when the heartbeat says `state: "error"`, and Wait / Go on / Slower / Faster for the walk Otto is on. Below it the live map (it follows Otto during a walk).
- `/map` - the live map: the spots of Otto's map as labelled dots, Otto as an arrow with its trail, LiDAR points, the patient's estimated position, the target and the metres left. Zoom, fit, own light / dark switch. Select a place and "Send Otto here" (`go_to`).
- `/logs` - what the robot app reports: live every 3 s, filter by level (and above) and source, search, pause (counts the new lines), copy, load older lines. Kept 24 h, at most 5000 lines per robot.
- `/walk/<code>` - the patient's phone page (no login, opened from the QR code on Otto's pickup screen): ticket, destination, metres left, big buttons Wait, Go on, Slower, Faster and Talk (the phone's speech-to-text, sent to Otto as `ask`). Works only while the journey is open and only while Otto is with that ticket. Rate limited.
- `/room` and `/room/<placeId>` - the room view for doctors' phones: who is in the waiting room ("Call in"), who is coming, who is here, with big buttons.
- `/places` - places Otto can walk to: name, room number, map spot, detour spot (`viaSpot`, tried once when the way stays blocked), kind (room, waiting room, reception, exit, other), minutes per patient, waiting room, self check-in on the robot, public, own Teams webhook, email (`notifyEmail`). Nothing is built in: start with the reception, the exit and your rooms. Each place shows where its messages go, rooms without a waiting room get a hint, and spots missing on Otto's map are marked.
- `/notifications` - the channels for messages to staff (Teams, Slack, Telegram, WhatsApp, Discord, email, own webhook), "Send test", and which place uses which channels. See below.
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

The conversation feed keeps Otto's lines and what it understood in the database for at most 12 h (cleared when the journey ends), so every Vercel instance shows it. What a patient said word for word (only with the demo switch on) stays in the memory of one server instance. The login and phone page rate limits are kept in the database.

## Added in v3.2 (contract: `../docs/journeys.md` section 6)

| Method | Path | Who | What |
|---|---|---|---|
| GET | /api/health | anyone | `{ ok, version, time }`, the robot picks the first server that answers |
| POST | /api/robot/logs | robot | `{ robot, items: [{ at, level, source, text }] }`. Odd levels/sources are mapped, empty lines dropped, at most 200 per call |
| GET | /api/robot/logs | dashboard | `?robot=&level=` (that level and above) `&source=a,b&q=&after=<id>` (tail) or `&before=<id>` (older) `&limit=` -> `{ lines (oldest first), more }` |
| POST | /api/robot/map | robot | `{ robot, mapName?, pose, places?, scan?, follower?, target?, remainingMeters? }`. Latest frame per robot + the last 120 poses of Otto (never the patient's) |
| GET | /api/robot/map | dashboard | `{ map }` |
| POST | /api/robot/commands | dashboard | also `{ type: "walk_control", journeyId, action: wait / resume / slower / faster }` |
| GET / POST | /api/walk/:code | anyone with the code | the phone page data / `{ action }` -> `walk_control` command. 409 while Otto is not with the ticket, 410 after the end |
| POST | /api/walk/:code/ask | anyone with the code | `{ text }` -> `ask` command with `origin: "phone"`. The text is removed from the database once Otto has fetched it |
| POST | /api/integrations/journeys | header `X-Api-Key` | `{ to: placeId or place name, pace?, wheelchair? }` -> `{ ticket, journeyId, to, via?, status, walkUrl }` |

Every `guide` command the server sends out (create answer, next leg, retry, `/api/robot/next`) has `walkCode`, `to.viaSpot` and `walkUrl` (the absolute phone page link for the QR code, `PUBLIC_BASE_URL` or the request address). Heartbeats may send `server`, `version` and `pose { x, y, theta }`; the pose keeps the map alive when no map frame came for 20 s. Rate limits: phone page reads 90 per minute per address, 20 taps and 6 messages per minute per phone and journey, 20 unknown codes in 10 minutes per address; 10 wrong integration keys in 10 minutes per address.

## Notifications (v3.3)

`/notifications` decides who hears about a patient. A message goes out when Otto walks a patient to a place ("A17 is on the way"), when a visitor sits in a room's waiting room ("Your visitor has arrived"), and when Otto asks for help. Every message has the ticket, the place, the pace (and wheelchair), the expected minutes and two links: "Call in" or "Done - guide to exit", and "Done - finished here". The links carry `CALL_KEY` as before. Never a name, never anything about health.

Each channel is a small plugin in `lib/notify.ts`: it gets the message, posts it in its own format and answers ok or a short reason.

| Channel | What it needs | What arrives |
|---|---|---|
| Microsoft Teams | the Workflows webhook link of the channel | the Adaptive Card, same as before |
| Slack | an incoming webhook link | a message with link buttons |
| Telegram | the bot token from @BotFather and the chat id | a message with URL buttons |
| WhatsApp | Meta Cloud API access token, phone number id, the number to send to (with country code) | a text message with the links |
| Discord | a webhook link | a card with link buttons |
| Email | the addresses, a Resend API key and a sender (or `RESEND_API_KEY` / `EMAIL_FROM` from `.env.local`) | the same email as before |
| Own webhook | a link, optional secret (sent as `Authorization: Bearer <secret>`) | JSON: `kind`, `title`, `text`, `facts`, `links`, `data`, `sentAt` |

- "Add a channel", fill in the fields, then "Send test". The card shows the result, later also the result of the last real message.
- "Default for all places": every place without its own choice gets it, and so do help requests from Otto. Under "Who gets which messages" a place can pick its own channels instead.
- `TEAMS_WEBHOOK_URL` shows up as the practice channel. It works as before, and can be taken out of the defaults there. A place's own Teams webhook and its email from `/places` also work as before: the webhook replaces the practice channel for that place, the email comes on top.
- All channels of a place get the message at the same time, each waits at most `NOTIFY_TIMEOUT_MS` (8 s). Every channel adds one event to the journey (`slack_notified`, `telegram_failed` with the reason, ...). The reception shows "Sent via Teams, Slack" and every failure on the patient's row.
- Links, tokens and keys stay on the server. The page only shows the end of them (`hooks.slack.com/…k3Fq`), the same for a place's own Teams webhook in `/api/places` and `/api/board`. Leave a saved secret empty to keep it. They never go into the logs.
- Without `DATABASE_URL` the channels live in memory like everything else: after a restart add them again, or set `DATABASE_URL` and run `npm run db:setup` (it adds the `notify_channels` table and `places.channel_ids`).
- WhatsApp: Meta only delivers free text to a number that wrote to your business number in the last 24 hours. A test only shows that Meta took the message.
- Telegram and Discord can refuse buttons with a local link (like `http://192.168.x.x:3000`). Then the message goes out again with the links as text.

| Method | Path | Who | What |
|---|---|---|---|
| GET | /api/channels | staff | channels (masked), the channels of each place, the help channels |
| POST | /api/channels | staff | `{ type, label?, config: { ...fields }, isDefault? }` |
| PATCH | /api/channels | staff | `{ id, label?, config?, isDefault?, active? }`. An empty secret keeps the saved one, `null` removes an optional one |
| DELETE | /api/channels?id= | staff | removes it. A place that picked only this one goes back to the defaults |
| POST | /api/channels/test | staff | `{ id }` -> `{ ok, text, at }`, any channel: made here, the practice channel, a place's own webhook or email |
| PATCH | /api/places | staff | `{ id, channelIds: [ids] }`, `null` = the default channels |

### Waiting rooms

A room with a waiting room (pick it under Edit on `/places`) works like this: reception sends the patient "via Waiting B", Otto walks them there, and when they sit down the room gets "Your visitor has arrived" with "Call in". "Call in" (in the message, on the reception or in the room view) sends Otto back to the waiting room to fetch the ticket and walk it to the room. The waiting room's map spot has to exist on Otto's map: the team adds it on the robot. `/places` shows a hint on rooms without a waiting room, and on waiting rooms whether their spot is on the map Otto sent last.

## Env

| Name | What |
|---|---|
| `DATABASE_URL` | Neon Postgres. Empty = in memory |
| `TEAM_INBOX_TOKEN` | robot token, same as `teamInbox.token` in `robot-app/local.properties` |
| `DASHBOARD_PASSWORD` | shared staff password for `/login` (and basic auth for scripts). Empty = no login |
| `SESSION_SECRET` | optional, mixed into the session cookie signature |
| `CALL_KEY` | secret in the Teams card links |
| `TEAMS_WEBHOOK_URL` | Teams Workflows webhook of the practice channel. Used when a place has no own webhook, and for help requests. Shows up on `/notifications` as a channel. The other channels are set up there, not here |
| `PUBLIC_BASE_URL` | address phones can open, for links in Teams cards, emails and the phone page QR code. Empty = the address the request came in on |
| `CLINIC_TIMEZONE` | e.g. `Europe/Berlin`: tickets per day, today's numbers, clock times in cards |
| `RESEND_API_KEY` + `EMAIL_FROM` | email as a second channel (Resend REST API): places with an email get the same message as the Teams card, ticket and room only. Both empty = no email |
| `RESEND_API_URL` | optional, only for tests (a mock). Default `https://api.resend.com/emails` |
| `NOTIFY_TIMEOUT_MS` | optional, how long one channel may take, default 8000 |
| `TELEGRAM_API_URL` / `WHATSAPP_API_URL` | optional, only for tests (a mock). Default `https://api.telegram.org` / `https://graph.facebook.com/v25.0` |
| `INTEGRATION_KEY` | key for `POST /api/integrations/journeys` (header `X-Api-Key`). Empty = the route is off |

## Demo without the robot

1. Open `/places` and add the reception, the exit, a waiting room and two rooms (one with the waiting room and self check-in on).
2. Open the reception in one window and `/simulate` in another.
3. On the reception type the room, press 2 for slow, press Enter. Tell the patient the ticket.
4. On `/simulate` press "Check for jobs" and step through pickup, walking, check-backs and arrival. The reception shows the robot's screen and the conversation live.
5. On the reception (or the room view) press "With me", then "Done → exit" or "Next room…".
6. Check in on `/simulate` as a patient for the room with the waiting room: the room gets "Your visitor has arrived" on its channels when the patient sits there, and "Call in" brings them over.
7. On `/notifications` add a channel and press "Send test". The next walk shows "Sent via ..." on its row.

Keep automatic checking off on `/simulate` while the real robot runs, otherwise the simulator takes its jobs.
