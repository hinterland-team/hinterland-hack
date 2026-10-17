# Hinterland Hack - Nova Clinic

Our entry for the Terra Robotics Challenge (Smart Receptionist). The robot (OrionStar Nova on AgentOS) does the reception of a doctor's practice: the patient just talks to it, Nova checks them in, the doctor gets a Teams card, and when the doctor is ready Nova walks the patient to the room and drives back.

## What's in here

- `robot-app/` - Android app for the robot (Kotlin, Jetpack Compose, AgentOS SDK 0.4.5). "Nova Clinic": Nova checks patients in, calls them when the doctor is ready and walks them to the doctor's room. See [Robot app](#robot-app).
- `team-inbox/` - Next.js. The reception board: visits per doctor, Teams cards with a "Call patient in" button, doctor list, and `/simulate` to demo the loop without the robot. See `team-inbox/README.md`.

## How it works

The API between the two parts is in `docs/api.md`.

1. The patient talks to Nova. The AgentOS LLM asks for name, doctor and appointment time, reads them back once and then picks `CHECK_IN`.
2. The app POSTs the visit to `team-inbox`, the doctor gets a Teams card, Nova says the reply and the screen shows the steps.
3. The doctor taps "Call patient in". The app picks this up from `/api/robot/next`.
4. Nova calls the patient, walks to the doctor's room, says goodbye and drives back to reception. Each step is PATCHed to the visit, so the dashboard follows along.

## Setup on this laptop (done 16.10.2026)

- Android Studio, Android SDK in `%LOCALAPPDATA%\Android\Sdk`, `adb` in its `platform-tools` (on PATH)
- `robot-app/local.properties` (not in git): `sdk.dir`, `teamInbox.url` (base url, no path), `teamInbox.token`, `robot.name`, `robot.homeSpot`
- `team-inbox/.env.local` (not in git): `TEAM_INBOX_TOKEN` (same token as above), `CALL_KEY`, `IOT_KEY`, `TEAMS_WEBHOOK_URL` (empty for now). All options are in `team-inbox/.env.example`

## Still open before it runs on the robot

1. **appId** - done. Agent "Team Relay" in the portal (Agent App Development), id is in `robot-app/app/src/main/assets/actionRegistry.json`.
2. **ROM version** - done. Our Nova (model OS-R-SG04, SN M03SHW2A30025032951B) is on OrionStar OS 12.1, so the app uses SDK 0.4.5-SNAPSHOT (newest one for V11.7 up to before 12.3). If the robot gets updated to 12.6, switch to 0.4.12-SNAPSHOT in `robot-app/app/build.gradle.kts`.
3. **Developer mode** - ADB is off by default. Swipe down, tap the time area many times, enter the temporary password from OrionStar support (send them the SN above). Turn on debug and persistent debug, reboot.
4. **Connect** - `adb connect <robot-ip>:5555` (same Wi-Fi) or micro-USB cable at the head.

## Run

Team inbox:

```
cd team-inbox
npm run dev:lan
```

The dashboard is on http://localhost:3000, and the robot talks to `http://<laptop-ip>:3000/api/...` (see `docs/api.md`). The first time, Windows Firewall asks about node, so allow it for private networks or the robot can't reach it.

Robot app: open `robot-app` in Android Studio, pick the robot as the device and press Run. Or from a terminal:

```
cd robot-app
gradlew.bat assembleDebug
adb connect <robot-ip>:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then start "Nova Clinic" from the robot's home screen. OrionStar says to start apps through RobotOS Home, otherwise the app may not get the rights to drive.

## Robot app

`robot-app/` is "Nova Clinic". The patient just talks. Nova asks only for name, doctor and appointment time, never for symptoms, and gives no medical advice.

Actions (in `ClinicActions.kt`, the LLM picks them by their description):

- `com.hinterland.clinic.CHECK_IN` - `patient_name`, `doctor`, `appointment_time` (optional), `language` (optional), `reply`. Saves the visit (POST /api/visits), then Nova says the reply. If the backend doesn't know the doctor, Nova still checks in and says the team will help.
- `com.hinterland.clinic.GUIDE_TO_DOCTOR` - `doctor`, `patient_name` (optional). Walks to the doctor's map spot, says goodbye, drives back to `robot.homeSpot`.
- `com.hinterland.clinic.CALL_STAFF` - `summary` (no medical details), `urgency` normal / urgent. POST /api/help. For urgent ones Nova also says to call 112.
- `com.hinterland.clinic.WAITING_STATUS` - `patient_name`. Reads the visit and tells where it stands.

Nova also acts on her own (`NovaBrain.kt`, the priorities are in `Brain.kt`). Every second she picks the most important thing: stay put after an urgent help call, run the team's commands from `/api/robot/next` (`call_patient`, `go_to`, `say`; a patient who is talking gets up to 20 s first), greet a visitor who walks up, drive to the charger at 20 % battery and leave it at 30 %, and drive back to `robot.homeSpot` after a minute away. She never starts something new while someone is talking to her.

When she calls a patient she waits up to 30 s for someone to come close. If nobody comes, the visit goes back to `called` and the team gets a help message. After a check-in she says the place in the queue and the expected wait.

Every 15 s she sends battery, state, position and goal to `/api/robot/heartbeat`. The screen shows the same goal under her face. Turning towards the person she talks to (focus following) can be switched off with `robot.focusFollow=false` in `local.properties`.

Things Nova says on its own (calling the patient, goodbye, errors) are written by the AgentOS LLM in the visit's language. Doctor names and spots come from the backend and the robot map. So the code has no language lists and no doctor or room names.

The screen: Nova's face on the left (blinks, looks up while it thinks) with what was just said, the steps of the current visit on the right (details, doctor informed, waiting, walking, arrived, back at reception).

Map: every doctor needs a spot on the robot map with the name from the dashboard (`spot`), plus the home spot (now "Reception Point"). The robot has to be localized on that map.

Tests for the plain logic (JSON, name matching, timeline, Nova's priorities): `gradlew.bat testDebugUnitTest`.

Test the inbox without the robot: open http://localhost:3000/simulate, it plays the robot. Or check someone in by hand (token is in `team-inbox/.env.local`):

```
curl -X POST http://localhost:3000/api/visits -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d "{\"patientName\":\"Max Weber\",\"doctor\":\"Dr. Blue\",\"appointmentTime\":\"10:00\",\"robot\":\"nova\"}"
```

## Deploy to the robot

Nova Clinic is deployed by Nova Deploy (separate repo `nova-deploy`): push to `main`, GitHub runs the unit tests and builds the APK signed with our robot key, and Nova Deploy on the robot installs it. Redeploy and rollback are on the Nova Deploy dashboard. What this repo has for it (`nova-deploy.json`, the workflow, signing, the secrets, the busy hold) is in `docs/deploy.md`.

- Nova Deploy never replaces Nova Clinic while she walks, fetches a patient or drives back: the app sends it a busy hold.
- Without ADB, Android 9 asks for one tap on "Install" per update. Nova Deploy's optional auto-tap helper presses it.
- After an update the robot's home screen comes to the front. Start Nova Clinic from there, otherwise it can't drive.

One-time setup on the robot: uninstall the old Nova Clinic once (it was signed with the laptop's key), install Nova Deploy from a link in Chrome, allow installs for it and open it once.

## Notes

- The laptop IP changes at the venue. Update `teamInbox.url` in `local.properties` (just `http://<ip>:3000`) and rebuild.
- Without `DATABASE_URL` everything lives in memory, so a restart wipes it. With a Neon url, run `npm run db:setup` once.
- The dashboard has no login unless `DASHBOARD_PASSWORD` is set. Fine on the local network, set it before a public deploy.
- On AgentOS the old RobotAPI voice stuff (ASR/TTS/NLP) is off. Everything voice goes through the AgentOS SDK.

## Links

- AgentOS SDK docs: https://github.com/orionagent/agentos-sdk
- Official sample: https://github.com/orionagent/AgentSDKSample
- Old RobotOS jars: https://github.com/OrionStarGIT/RobotSample/releases
- Cloud OpenAPI: https://openapi.orionstar.com/opendocs/zh/index
- Robot portal: https://global-robotmp.orionstar.com
