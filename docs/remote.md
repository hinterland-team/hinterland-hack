# Nova Remote

A dev tool to work on the robot from your laptop over the same Wi-Fi: see its screen, tap and type on it, drive it with the arrow keys, and install APKs. It's a normal app (`com.novaremote.agent`, Gradle module `robot-app/remote`) and gets built and shipped by Nova Deploy like Nova Clinic.

It does **not** root the robot or get around OrionStar's lock. Every power comes from a normal Android API that the robot has to allow:

| Feature | How it works | What the robot asks |
|---|---|---|
| See the screen | MediaProjection → JPEG frames over a websocket | the system "Start capturing?" dialog, each time you start it |
| Tap / swipe / type | an Accessibility service sends the gestures | you switch it on once in Settings → Accessibility |
| Install an APK | the file goes to Android's package installer | the normal "Install?" prompt, every time |
| Drive (arrow keys) | RobotApi `motionArcWithObstacles` / `stopMove` | nothing — but only while Nova Remote is in front and was started from the robot home screen (same chassis rule as Nova Clinic) |

What it can't do: no shell on the robot, no other apps' logs, no silent installs, no locked system settings. All of that needs ADB, and the way to get ADB is the debug password from Mert / OrionStar (see `mail-mert.txt`).

## The lock

Three gates, all have to pass before a laptop gets a session token:

1. **Same Wi-Fi** — the laptop must be on the robot's `/24`. A device on another subnet is refused before the code is even checked.
2. **MAC allow list** (optional) — if you set `remote.macAllow`, only those laptops pass. The robot reads the laptop's MAC from its own ARP table by the laptop's IP, which only works for a device on the same Wi-Fi. A MAC is easy to spoof, so this is a convenience gate, not the real lock.
3. **The 9-digit code** — shown on the robot screen, re-rolled every time Nova Remote starts. Five wrong tries from one device → locked out for 60 s. This is the real lock.

The token is dropped when the app stops. Nothing is open to the internet; the server only listens on the robot's Wi-Fi address.

## Use it

1. Start Nova Remote **from the robot home screen** (so driving works). It shows `http://<robot-ip>:8080` and the 9-digit code.
2. On the robot, tap what you need: **Start screen view**, **Enable remote taps**, **Allow app installs**. (Screen view and taps are only needed for those features; driving and install work without them.)
3. On your laptop (same Wi-Fi), open that address, type the code.
4. You get the live screen (tap = tap, drag = swipe), Back/Home/Recents, a drive pad + arrow keys, a text box, APK drag-and-drop, and the app list.

Driving is a short pulse the robot repeats while you hold the key; it stops the wheels the moment you let go or the laptop drops off Wi-Fi (0.5 s watchdog). Speeds are gentle indoor defaults (0.35 m/s, 0.7 rad/s).

## Config

In `robot-app/local.properties` (local builds) or `NOVA_APP_PROPERTIES` (CI, see `deploy.md`):

```
remote.port=8080          # lan port; websocket is port+1
remote.macAllow=          # empty = any device on the wifi + code; else aa:bb:.. ,cc:dd:..
```

Both are baked in at build time, so changing them needs a rebuild.

## Code

`robot-app/remote/src/main/java/com/novaremote/agent/`

- `MainActivity` — the on-robot screen: address, code, and the three allow buttons.
- `RemoteService` — foreground service; owns the servers, pairing, screen size, capture, driving.
- `Pairing` — the 9-digit code, tokens, MAC gate, rate-limit (unit-tested).
- `Net` — Wi-Fi IP, same-subnet check, MAC lookup from `/proc/net/arp` (unit-tested).
- `WebServer` (NanoHTTPD) — control page, `/pair`, app list, launch/uninstall, APK upload.
- `WsServer` + `RemoteSocket` (NanoWSD) — screen frames out, taps/keys/drive in.
- `ScreenCapture` — MediaProjection → downscaled JPEG (~8 fps).
- `RemoteAccessibilityService` — the remote taps/swipes/text; off until switched on.
- `Installer`, `AppsInfo`, `Driving` — APK install, app list/battery, chassis motion.
- `assets/web/` — the laptop control page (`index.html`, `app.js`, `style.css`).
