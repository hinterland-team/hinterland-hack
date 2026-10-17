# Deploy to the robot

Nova Clinic is deployed by Nova Deploy, our "Vercel for OrionStar robots" in the separate repo `nova-deploy`. Push to `main`, GitHub tests, builds and signs the APK, and the Nova Deploy app on the robot installs it. How Nova Deploy works (dashboard, robot app, build step) is in that repo, `docs/contract.md`.

## What this repo has

- `nova-deploy.json` - the apps Nova Deploy builds from here. One app: `nova-clinic` (Gradle project `robot-app`, module `app`, package `com.hinterland.relay`). `launch: home` brings the robot's home screen to the front after an update, because OrionStar only gives an app the wheels when it is started from there.
- `.github/workflows/nova-deploy.yml` - runs the shared build step `hinterland-team/nova-deploy-action@main` on every push to `main` and on Redeploy from the dashboard: unit tests, `robot-app/app` APK, one GitHub release per build (`nova-clinic-b12`). The dashboard writes this file, don't change it by hand.
- Signing and version in `robot-app`:
  - `robot-app/build.gradle.kts` signs the debug build with `signing.*` from `local.properties` when `signing.storeFile` is set. One key for all robot apps, alias `nova`. Android only installs an update signed with the same key.
  - `robot-app/app/build.gradle.kts` takes `-PversionCode` / `-PversionName` (CI passes `1000 + build number` and `b<N>-<sha7>`, local builds keep 3 / "0.3").
- Busy hold: while Nova walks, fetches a patient or drives back, the app tells Nova Deploy to wait (broadcast `com.novadeploy.action.HOLD`, 60 s, renewed every 15 s, `seconds = 0` once she stands still). So an update never replaces the app in the middle of a walk. If the app crashes, the hold runs out by itself. Code: `BusyHold.kt` (when to send), `DeployHold.kt` (the broadcast). Nova Deploy's package is `novaDeploy.package` in `local.properties`, default `com.novadeploy.agent`.

## Repo secrets

| name | value |
|---|---|
| `NOVA_KEYSTORE_B64` | base64 of the shared signing keystore |
| `NOVA_KEYSTORE_PASSWORD` | its store and key password |
| `NOVA_APP_PROPERTIES` | the `robot-app/local.properties` lines the app needs at build time |

"Set it up" on the Nova Deploy dashboard sets all three. `NOVA_APP_PROPERTIES` looks like this:

```
teamInbox.url=http://192.168.2.214:3000
teamInbox.token=<same as TEAM_INBOX_TOKEN>
robot.name=nova
robot.homeSpot=Reception Point
```

The laptop IP changes at the venue: update `teamInbox.url` in the secret, then Redeploy.

## Once on the robot

The Nova Clinic installed from the laptop earlier was signed with another key. Uninstall it once (Settings → Apps → Nova Clinic → Uninstall), after that every build installs on top.
