# Otto Clinic - backend and reception dashboard

Staff decide where a patient goes, Otto walks with them. A patient is only a ticket like `A17`: no names, no reasons, nothing recorded. The contract is `../docs/journeys.md` and `../docs/robot-setup.md`, the extra API details are in `../docs/api.md`. The robot is called Otto everywhere; its API id stays `nova` (its Android app is still called Nova Clinic on the robot's home screen).

## Run it fast on the laptop

```
npm install
cp .env.example .env.local   # then fill in the values
npm run serve:lan            # builds once, then serves on 0.0.0.0:3000 (the robot reaches the laptop over Wi-Fi)
```

`serve:lan` is the production server: every page and api answers in tens of milliseconds and nothing compiles while you click. `npm run dev:lan` is only for changing the code: it compiles each page on its first open (1-6 s) and again after every change. Stop the one before you start the other (same port, same `.next` folder). After a code change run `npm run serve:lan` again. No internet at the venue? `npm run start:lan` starts the last build without building (the build fetches the Google fonts once).

Measured on this laptop (17.10.2026, same data, ms):

| | `dev:lan` | `serve:lan` |
|---|---|---|
| a page, first open | 1300-6400 | 15-190 |
| a page, again | 150-210 | 13-28 |
| an api, first call | 200-760 | 6-25 |
| an api, again | 25-56 | 5-18 |
| reception in the browser until the data shows | 1230 | 220 |

The pages ask the server for news every 2 s (map 1-4 s) and get "304, nothing changed" without a body when nothing changed, so an open reception downloads nothing while it is quiet (before: about 150 KB per 30 s). A tab in the background stops asking, unless alerts are on for that device (then every 15 s). The clocks on the page tick only where they are shown, so the panels don't redraw every second.

Without `DATABASE_URL` the laptop keeps everything in memory and writes a copy to `.data/store.json` (`STORE_FILE`): places, channels, settings, Otto's settings and mode, open walks, the floor plans. A restart reads it back. Finished walks still go 12 h after they end, also when the server was off in between, and the demo switch for patients' words is off again after a restart. The file is git-ignored and holds the channel secrets, so it stays on this computer. `STORE_FILE=off` = memory only. With a Neon database, set `DATABASE_URL` and run `npm run db:setup` (again after an update: it only adds what is missing).

## Pages

- `/` - reception. "Guide a patient": type a name or room (or tap a card), pick the pace, press Enter or "Send Otto". The ticket then shows large ("Tell the patient: A17") with what happens next. Next to it every open journey, grouped by room: on the way, waiting with place in line and minutes, with staff, and the buttons "With me", "Done → exit", "Next room…", "Call in" and "Cancel". "Otto live" shows the robot's screen, the conversation, the current journey's timeline, and has "Tell Otto anything", "Say it out loud", "Send Otto to" and the demo switch "Show what patients say". Help alerts sit on top while open. Today's numbers are anonymous counts. Updates every 2 s.
  - Keyboard in the quick bar: Enter sends, 1 / 2 / 3 = pace, W = wheelchair, Esc = clear, arrow keys move between matches.
  - The robot card also shows Otto's mode ("Otto: Show mode, since 18:42"), the floor and whether Otto knows where it stands, the server Otto uses, errors of the last hour (links to the logs), a red "Out of service" box when the heartbeat says `state: "error"`, "Switch floor" when Otto has more than one map, and Wait / Go on / Slower / Faster for the walk Otto is on. Below it the live map (it follows Otto during a walk). While Virtual Otto runs, the card can switch between Otto and Virtual Otto; the simulation's walks are tagged SIMULATION.
  - "Alerts on this device" shows browser alerts like "A17 has arrived" on this computer while the page is open. Phones need the dashboard on an https address for that; on the laptop http://localhost:3000 works.
  - Destinations on another floor than Otto (or the reception) say so and can't be sent: Otto can't take the elevator. In Show and Calm mode "Send Otto" is off with a note and a "Switch to Normal" button.
- `/map` - the live map on Otto's real floor plan (the picture Otto uploads, map meters placed with its origin and resolution, y flipped like a ROS map): places, Otto as an arrow with its trail, LiDAR points, the patient's estimated position, the target and the metres left. Without a floor plan: the dots view with a note. Floor tabs when Otto has more than one map: another floor shows its spots and "Switch Otto to <map>" with "Otto is standing at <spot>". Zoom, fit, own light / dark switch (the plan is inverted in dark). Select a place and "Send Otto here" (`go_to`).
- `/logs` - what the robot app reports: live every 3 s, filter by level (and above) and source, search, pause (counts the new lines), copy, load older lines. Kept 24 h, at most 5000 lines per robot.
- `/walk/<code>` - the patient's phone page (no login, opened from the QR code on Otto's pickup screen): ticket, destination, metres left, big buttons Wait, Go on, Slower, Faster and Talk (the phone's speech-to-text, sent to Otto as `ask`). Works only while the journey is open and only while Otto is with that ticket. Rate limited.
- `/room` and `/room/<placeId>` - the room view for doctors' phones: who is in the waiting room ("Call in"), who is coming, who is here, with big buttons.
- `/settings` - one place to set things up, four tabs:
  - **Places** (`/settings/places`, the old `/places` link still works) - places Otto can walk to: name, room number, map spot, floor (Otto's map, empty = any floor), detour spot (`viaSpot`), kind (room, waiting room, reception, exit, other), minutes per patient, waiting room, self check-in on the robot, public, own Teams webhook, email. Grouped by floor once there is more than one. Each place shows where its messages go, rooms without a waiting room get a hint, and spots missing on Otto's map are marked.
  - **Notifications** (`/settings/notifications`, the old link works too) - the channels for messages to staff with one-click connect for Slack, Discord and Telegram, "Send test", who gets which messages, alerts on this device, and the clinic system integration. See below.
  - **Robot** (`/settings/robot`) - Otto's mode and its settings without a new app: walking, blocked ways, voice, sensors, screen. Each with its default, range and a line of help, "Reset to defaults" per group, and when Otto applied them.
  - **Debug** (`/settings/debug`) - Otto's version, server, map and floor, localised, settings version, screen speed and CPU from its log, errors of the last hour, "Recheck servers", the raw config.
- `/simulate` - Virtual Otto, a simulated robot with its own id (`otto-sim`): walks on the live map between your real places (along the corridors of the real floor plan when Otto sent one), "Play the whole demo", patient buttons. See below.
- `/login` - staff sign-in when `DASHBOARD_PASSWORD` is set.

At the top of every page: **Otto's mode**, Normal / Show / Calm. See "Modes" below.

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
- Without `DATABASE_URL` the channels are kept in `.data/store.json` with everything else (see "Run it fast on the laptop"), so they are still there after a restart. With `DATABASE_URL` run `npm run db:setup` (it adds the `notify_channels` table and `places.channel_ids`).
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

### Connect with one click (v3.4)

- **Slack** and **Discord**: the team creates its own app once (the steps and the redirect link to paste are on the page), enters the app's client ID and secret, and from then on "Connect Slack" / "Connect Discord" opens Slack or Discord, the team picks the channel there and comes back with the channel added (Slack scope `incoming-webhook`, Discord scope `webhook.incoming`). The secret stays on the server, the page shows its last 4 characters. A one-time state goes along, also in an httponly cookie of the same browser, so a callback from elsewhere is refused.
  - Slack only sends people back to **https** addresses. On the laptop (http) the page says so: paste a webhook link instead, or open the dashboard through an https address. Discord also takes `http://localhost`.
- **Telegram**: paste the bot token once, "Get the link" shows a link and a QR code to the bot with a one-time code (10 minutes). Pressing Start in Telegram (or adding the bot to a group with the second link) connects that chat: the server reads `/start <code>` by long-polling `getUpdates` and sends a hello. A bot with a webhook can't be used this way (the page says so).
- **Teams**: the Workflows steps, paste the link, "Add and send a test" saves it and sends a test right away.
- WhatsApp, email and the own webhook work as before. The old way (paste a webhook link, enter a chat id) stays under each connect box.

| Method | Path | Who | What |
|---|---|---|---|
| GET / PUT / DELETE | /api/integrations/apps | staff | the saved Slack / Discord app (client id, secret masked) and the redirect links. PUT `{ provider, clientId, clientSecret }`, an empty secret keeps the saved one |
| GET | /api/oauth/:provider/start | staff | off to Slack / Discord (`?default=0`: not a default channel) |
| GET | /api/oauth/:provider/callback | staff (same browser) | adds the channel, back to Settings with `?connected=<id>` or `?oauth_error=` |
| POST / GET / DELETE | /api/integrations/telegram | staff | `{ botToken }` -> `{ code, bot, url, groupUrl, expiresAt, status }`; `?code=` -> waiting / connected (with the channel) / expired / error |

### Waiting rooms

A room with a waiting room (pick it under Edit on `/settings/places`) works like this: reception sends the patient "via Waiting B", Otto walks them there, and when they sit down the room gets "Your visitor has arrived" with "Call in". "Call in" (in the message, on the reception or in the room view) sends Otto back to the waiting room to fetch the ticket and walk it to the room. The waiting room's map spot has to exist on Otto's map: the team adds it on the robot. `/places` shows a hint on rooms without a waiting room, and on waiting rooms whether their spot is on the map Otto sent last.

## Env

| Name | What |
|---|---|
| `DATABASE_URL` | Neon Postgres. Empty = in memory plus the file `STORE_FILE` |
| `STORE_FILE` | without a database: where the laptop keeps its copy, default `.data/store.json` (git-ignored). `off` = memory only |
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
| `SIM_SPEED` | optional, how much faster Virtual Otto walks than the real one, default 2.5 |
| `SLACK_OAUTH_URL` / `SLACK_API_URL` / `DISCORD_OAUTH_URL` / `DISCORD_API_URL` / `TELEGRAM_POLL_SECONDS` | optional, only for tests (mocks) |

## Virtual Otto (v3.4, `/simulate`)

A simulated Otto with its own robot id, `otto-sim`, always tagged SIMULATION. It only takes commands for `otto-sim` and only changes walks of `otto-sim`: the real robot's walks, commands, status and map are never touched, so it is safe to run while the real Otto works.

- It walks on the live map between your real places: on the spots of the map the real Otto sent (along the corridors of its floor plan, a* on the picture), else on a made-up floor built from your places (the page says so). Places missing on Otto's map are placed next to it and listed.
- **Play the whole demo**: a patient checks in on Virtual Otto's screen (or reception sends one), the team gets the message on its real channels (marked "Simulation: ..."), Virtual Otto picks the ticket up, walks with check-backs (the patient falls behind once), the patient sits in the waiting room ("Your visitor has arrived"), the doctor taps Call in, Otto walks them to the room, with the doctor, Done, walk to the exit, goodbye. Each step shows on the page; the reception and the room view show the walk live.
- **The patient** buttons while it walks: Walks fine (check-back), Falls behind (Otto waits, then walks slower), Path blocked (Otto asks to pass, then takes another way), Says "slower".
- **Send a patient yourself**: like the reception, but for Virtual Otto.
- Its walks don't count in today's numbers. Switching it off closes its open walks; leftovers from a restart are closed when it is switched on again.

| Method | Path | Who | What |
|---|---|---|---|
| GET | /api/sim | staff | on, the walk, the demo steps, what happened, the floor, the demo plan |
| POST | /api/sim | staff | `{ action: "on" | "off" | "demo" | "stop" | "patient", what?: "fine" | "behind" | "blocked" | "slower" }` |

## Modes (v3.4)

At the top of every page: **Normal** (receptionist), **Show** (face and animations, silent, does not react to patients) and **Calm** (stands still, no speech, no driving on its own, fine to be pushed). The mode is part of Otto's settings (`settings.mode` in `GET /api/robot/config`), a switch sends `{ type: "set_mode", mode }` and Otto's heartbeat reports `mode` back ("Otto: Show mode, since 18:42" on the robot card). In Show and Calm new walks are refused with a friendly note ("Otto is in Show mode. Switch to Normal to guide patients"); the robot refuses guides too, and in Show and Calm it doesn't say anything ("Say" and "Ask" come back as not done, with Otto's reason).

- A switch during a walk waits until the walk is over: the heartbeat's `modePending` shows "Normal mode until this walk is over, then Show", and new walks are already refused.
- A mode picked on Otto's staff menu counts until someone picks a mode here (the robot only follows `settings.mode` when it changes): the header and the robot card show it ("Show mode, picked on Otto"), and picking a mode here sends `set_mode` again even when `settings.mode` already says so.

| Method | Path | Who | What |
|---|---|---|---|
| GET / POST | /api/robot/mode | staff | `{ robot, mode, saved, applied: { mode, at }, pending, switching, onRobot, refused, online }`: `mode` is the one that counts, `saved` is the dashboard's choice (`settings.mode`, or demo); POST `{ mode }`, also `demo` |

## Demo mode (v3.5, contract `../docs/demo-mode.md`)

The rehearsed demo for the judges: Otto only says the script's lines, word for word, and the microphone stays off. The check-in, the walk at the chosen pace, the check-backs, obstacles and the way back stay real.

- `/demo`: the operator page for a phone (also fine on the laptop). Next, Hold / Resume, Restart, Start and Stop as big buttons, Say with quick lines, Otto's state from its heartbeat (part, step, what it waits for), a teleprompter "Patient says now" and the whole script with the current step marked. Otto's answers to every command show below it. "Which Otto" switches between Otto and Virtual Otto; "Speak Otto's lines on this device" reads the lines aloud for rehearsing (off by default).
- Settings, Demo script: the parts (reception, first check-back, patient fell back, arrival, obstacle, detour) and their steps (Otto says, Patient says, or an action: check-in, pace, wait until close). Add, remove and reorder steps, set the pause after a line and the time for a patient's line. Where Otto walks to and waits are place pickers (matched by place name, then by spot); the run starts when a person stands in front of Otto (0.5 to 3 m) or only with Next. The walk: the patient's side (right by default), how far Otto turns to look at the first check-back (0 to 90 degrees, 60), the seconds of walking after it until Patient fell back (2 to 30, 6) and the longest wait for the patient to catch up (3 to 20 s, 8). A script saved before these existed gets the defaults for them and keeps its edits. Kept in the store (the file on the laptop). Reset to default brings back the contract's script.
- The mode switch at the top has Demo: it sends `demo start` with the saved script; leaving Demo sends `demo stop`, then `set_mode` (`settings.mode` stays normal, show or calm). In demo mode walks from the board are refused with "Otto is in Demo mode. Switch to Normal to guide patients", while Otto's own check-in in the script works. "Tell Otto" waits, "Say it out loud" still works.
- Otto can't cancel a walk: on stop or restart it fails the open leg with "The demo was ended by the team", and the server closes the walk calmly (cancelled, no "needs you").
- Virtual Otto plays demo runs too (Start on `/demo` with Virtual Otto): its lines take about their speaking time, a patient's line 1.2 s + 0.4 s per word (or its own time), Next ends a wait. It sees a person 4 s after it stands at the reception. During its demo walk the patient buttons on `/simulate` work (falls behind: Patient fell back; path blocked: obstacle and detour), and Send Otto to ends the run and holds there like the robot.

| Method | Path | Who | What |
|---|---|---|---|
| GET | /api/demo?robot= | staff | the demo page: online, the mode, the heartbeat's `demo`, the script, Otto's answers. 304 while nothing changed |
| POST | /api/demo | staff | `{ robot?, op: start / next / hold / resume / restart / stop / say, text? }` -> the command `{ type: "demo", op, script?, text? }`. A start carries the saved script, and switches Virtual Otto on |
| GET / PUT / DELETE | /api/demo/script | staff | `{ script, isDefault, updatedAt }`; PUT `{ script }` (400 with the first problem); DELETE: back to the default |

- Heartbeats may send `demo: { running, part, step, total, waitingFor, line, nextPatientLine, ticket, runs }` (exactly these fields; `step` counts from 1) and `mode: "demo"`. `set_mode` with `mode: "demo"` through `/api/robot/commands` becomes the demo start.
- New robot setting `walk.turnSide`: right (default), left or shortest.

## Otto's real map, floors and settings (v3.4, contract `../docs/robot-setup.md`)

| Method | Path | Who | What |
|---|---|---|---|
| POST | /api/robot/map/image | robot | `{ robot, mapName, width, height, resolution, originX, originY, png: base64, hash }` -> 204. Latest per robot and map, kept like the rest. The png's own size wins |
| GET | /api/robot/map/image?robot=&map=&v=<hash> | dashboard | the png (cacheable with the hash) |
| GET | /api/robot/map | dashboard | as before plus `image` (the plan's numbers) and `floors` (Otto's maps, the current one, spots remembered per map). 304 while nothing changed |
| GET | /api/robot/floor?robot=&map= | dashboard | another floor: its spots and plan |
| POST | /api/robot/commands | dashboard | also `{ type: "switch_map", mapName, spot }` (409 while Otto walks with a patient), `{ type: "recheck" }`, `{ type: "config_changed" }`, `{ type: "set_mode", mode }` |
| GET | /api/robot/config | robot / dashboard | robot: `{ version, settings }` with every setting; dashboard: plus `overrides`, `updatedAt`, `applied { version, at }` |
| PATCH | /api/robot/config | dashboard | `{ settings: { walk: { speedNormal: 0.55 } }, reset?: "walk" | "all" }` -> new version + `config_changed`. Out of range -> 400 with the setting's name |
| GET | /api/robot/debug | dashboard | status, config versions, raw config, perf from "Perf (30 s)", errors, map and floors, recent commands |

- Places get `mapName` (empty = any floor). A walk from or to a place on another floor than the reception, the place it starts at, or the map Otto is on is refused with a clear message.
- The settings use the robot app's names, defaults and ranges, nested by group (`walk.speedNormal`, `walk.checkMinS`, `voice.callLanguages`, ...; the full list is in `../docs/robot-setup.md`, "Dashboard side as built"). `voice.volume` and `voice.callLanguages` are `null` until the team sets them: as set on the robot. The speeds must go normal >= slow >= very slow, and `walk.checkMinS` <= `walk.checkMaxS`.
- Heartbeats may send `mapName`, `localised` (or `localized`), `configVersion`, `mode`, `modePending` (the mode Otto takes after the walk under way) and `maps`; map frames may send `maps: [{ name, floor? }]`. The log line "Settings vN applied" also counts as applied.
- `POST /api/robot/commands/:id/done` takes Otto's answer `{ ok, detail }` (switch_map, set_mode, recheck and refused commands like "Otto is in Show mode" or "only on the current map"). "Sent to Otto" on the reception and the Debug page show it; a refused walk also fails its leg with the reason.

## Demo without the robot

1. Under Settings, Places add the reception, the exit, a waiting room and two rooms (one with the waiting room and self check-in on).
2. Open the reception in one window and `/simulate` in another, press "Play the whole demo". On the reception switch the live card to "Virtual Otto".
3. Or send a patient on `/simulate` yourself and press the patient buttons while it walks.
4. Under Settings, Notifications connect a channel and press "Send test". The next walk shows "Sent via ..." on its row.
