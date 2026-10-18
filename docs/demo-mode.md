# Demo mode (contract)

A fixed, rehearsed demo for the judges. The microphone is OFF the whole time. Otto only says the script's lines, word for word (plain text-to-speech, no AI writing, no listening). The "patient" (a team member) says memorised lines. Everything else stays real: driving to reception, seeing the person, the check-in (real journey + ticket, dashboard and notifications), the walk at the chosen pace, the check-backs, noticing that the patient fell back, obstacles and detours, the way back.

## Flow

1. Demo starts (dashboard `/demo`, the mode switch, or the staff menu): Otto goes into demo mode, mic off, drives to the reception place and waits there with his face on.
2. Otto sees a person standing in front of him (camera, within `personM`, for ~1 s) or the operator presses Next: the `reception` part starts.
3. `reception`: greeting, the patient's answer, the check-in (a real self check-in journey to the destination, ticket on the screen), the pace question, then the walk starts.
4. Walk to the destination at the chosen pace, patient on Otto's `walkSide` (default right). The demo walk is a fixed routine (b20, the user's choice after the b19 field test: lock-guided turns were inconsistent at 96-106 deg, and a fixed 90 deg turn to the right faces both the patient and the audience):
   - `firstCheck`: Otto's first check-back stop (~10 s after leaving): he turns exactly `glanceDeg` (default 90) towards the walk side, plays the part, turns back by the same angle and drives on. No lock-guided turn, no corrections.
   - `fellBack`: only if the script has it: `fellBackAfterS` seconds of driving after the firstCheck part, or on Next. Otto stops facing forward (no turn), says the lines and waits until the patient is close again (`wait_close`). Skipped within 2 m of the destination. The demo script saved on the dashboard for the hackathon has no fellBack part (it fired at odd moments).
   Later check-backs in the demo do nothing (no stop, no turn). Obstacles and detours use the real handling with the fixed lines `obstacle` / `detour`.

### Ticket lock (all walks)

Otto locks onto the person who got the ticket and ignores everyone and everything else. No face recognition, no face templates, no images: only positions, in memory, for this walk.
- Start: the person standing in front at check-in (camera, nearest within `personM`); at the walk start the first LiDAR candidate within 1 m of that spot is the locked patient.
- Candidates: person-sized LiDAR clusters within 2.5 m. Static things (a cluster that keeps its map position while Otto drives on: walls, pillars, chairs) are never the patient. Other people (moving differently from Otto, or more than 0.7 m from the predicted spot) are ignored.
- Prediction: the patient walks with Otto, so the spot keeps its place next to him (last relative position and speed, turned along with Otto's own turns).
- Lost: no candidate at the predicted spot for walk.besideLostS while driving = fell back (predicted: behind on the walk side).
- Check-backs (normal walks): turn towards the predicted spot, then re-measure and correct until the patient is straight ahead (more than 15 deg off, at most 2 corrections), the camera picks the person nearest to the spot, everyone else is ignored, the lock is anchored there again; driving on turns back by the total turned.
5. `arrive`: the arrival lines at the destination.
6. Otto drives back to the reception place without talking and waits for the next person (step 2 again), until the demo is stopped.

## Default script

What the patient says (learn these by heart):

1. "Yes, I have an appointment with Dr. Green."
2. "A little slower, please."
3. "No, it's perfect, thank you."
4. "Sorry, here I am."
5. "Thank you, Otto!"

The whole run:

| Part | Who | Line |
|---|---|---|
| reception | Otto | Hello, I'm Otto. How can I help you? Do you have an appointment? |
| | Patient | Yes, I have an appointment with Dr. Green. |
| | (check-in) | real journey to Dr. Green, ticket on the screen |
| | Otto | Thank you. You're checked in for Dr. Green. |
| | Otto | Your ticket number is {ticket}. I only use this number, never your name. |
| | Otto | Shall I walk at a normal pace, or a little slower? |
| | Patient | A little slower, please. |
| | (pace slow) | |
| | Otto | Of course. Please walk on my right. Let's go! |
| firstCheck | Otto | Is everything okay? Am I too fast? |
| | Patient | No, it's perfect, thank you. |
| | Otto | Great, let's go on. |
| fellBack | Otto | I'll wait for you. Take your time. |
| | (wait until close) | |
| | Patient | Sorry, here I am. |
| | Otto | No problem. We're almost there. |
| arrive | Otto | Here we are, this is Dr. Green's room. Please take a seat. Dr. Green's team knows you're here. |
| | Patient | Thank you, Otto! |
| | Otto | You're welcome. Get well soon! |
| obstacle | Otto | Excuse me, may I pass? |
| detour | Otto | This way is blocked. Let's take another way. |

## Script format

```json
{
  "version": 1,
  "destination": "Dr. Green",
  "reception": "Reception Point",
  "start": "person",
  "personM": 1.2,
  "walkSide": "right",
  "glanceDeg": 60,
  "fellBackAfterS": 6,
  "closeMaxS": 8,
  "parts": {
    "reception": [
      { "who": "otto", "text": "Hello, I'm Otto. How can I help you? Do you have an appointment?" },
      { "who": "patient", "text": "Yes, I have an appointment with Dr. Green." },
      { "who": "action", "do": "check_in" },
      { "who": "otto", "text": "Thank you. You're checked in for Dr. Green." },
      { "who": "otto", "text": "Your ticket number is {ticket}. I only use this number, never your name." },
      { "who": "otto", "text": "Shall I walk at a normal pace, or a little slower?" },
      { "who": "patient", "text": "A little slower, please." },
      { "who": "action", "do": "pace", "value": "slow" },
      { "who": "otto", "text": "Of course. Please walk on my right. Let's go!" }
    ],
    "firstCheck": [
      { "who": "otto", "text": "Is everything okay? Am I too fast?" },
      { "who": "patient", "text": "No, it's perfect, thank you." },
      { "who": "otto", "text": "Great, let's go on." }
    ],
    "fellBack": [
      { "who": "otto", "text": "I'll wait for you. Take your time." },
      { "who": "action", "do": "wait_close" },
      { "who": "patient", "text": "Sorry, here I am." },
      { "who": "otto", "text": "No problem. We're almost there." }
    ],
    "arrive": [
      { "who": "otto", "text": "Here we are, this is Dr. Green's room. Please take a seat. Dr. Green's team knows you're here." },
      { "who": "patient", "text": "Thank you, Otto!" },
      { "who": "otto", "text": "You're welcome. Get well soon!" }
    ],
    "obstacle": [{ "who": "otto", "text": "Excuse me, may I pass?" }],
    "detour": [{ "who": "otto", "text": "This way is blocked. Let's take another way." }]
  }
}
```

- `destination` / `reception`: place names (or ids) on Otto's current map. `start`: `person` (camera) or `next` (operator only). `personM`: how close the person must stand (0.5-3 m).
- `walkSide`: `right` | `left`, the patient's side on the demo walk (the setting walk.walkSide is not used in demo runs). `glanceDeg`: the firstCheck turn towards the walk side and back (0-90, default 90). `fellBackAfterS`: seconds of driving after the firstCheck part until fellBack (2-30). `closeMaxS`: the longest `wait_close` (3-20 s).
- `otto` step: Otto says `text` exactly (placeholders `{ticket}`, `{place}`), waits until it was said, then `waitS` (default: the voice setting `linePauseS`).
- `patient` step: Otto is silent and waits for the patient's line: `waitS` if set, else 1.2 s + 0.4 s per word. Next ends the wait at once. Not shown on Otto's screen.
- `action` steps: `check_in` (self check-in journey to `destination`, `{ticket}` is set from then on), `pace` (`value` normal | slow | very_slow), `wait_close` (until a person is within 1.5 m beside, by LiDAR, or in front, by camera, for 1 s; at most `closeMaxS`, or Next).
- Missing parts are skipped. Unknown fields are ignored. A broken script falls back to the built-in default (log a warning).

## Robot (robot-app)

- New mode `demo` next to normal / show / calm. Mic off and kept off (like show / calm), system voice bar hidden, listening without wake word off, no greetings or self check-in of normal mode, no AI lines at all (also no walk-line LLM call), no driving home of normal mode. Otto's own words are only the script's lines and `say` lines from the operator. Speech still goes through the turn manager (one line at a time), but demo lines are never dropped.
- Entering demo mode starts the run (built-in script, or the last script the dashboard sent). Leaving it (stop, set_mode, staff menu) ends a running journey cleanly and goes back to normal mode (or the mode asked for).
- Command `{ type: "demo", op, script?, text? }`:
  - `start` (+ `script`): demo mode on, drive to reception, wait for a person.
  - `next`: ends the current wait at once (person, patient line, wait_close). During the walk with no part running, it plays the next walk part not yet played (`firstCheck`, then `fellBack`).
  - `hold` / `resume`: pause before the next step; during the walk Otto also stops driving until resume.
  - `restart`: ends the running journey cleanly, back to reception, wait for the next person.
  - `stop`: demo mode off (see above).
  - `say` (+ `text`): Otto says this text right away (fixed text, mic stays off).
  The command is answered with done `{ ok, detail }`. In demo mode Otto refuses `guide` and `ask` (detail "Otto is in Demo mode"); `say`, `go_to`, `set_mode`, `stop` and the settings commands still work.
- If the check-in call fails (server not reachable), the demo goes on with a local ticket (log a warning); the walk uses the place on Otto's map.
- Screen: the normal screens (face with his line as subtitle, the ticket card after check-in, the walk screen). No "demo" label on the robot.
- Heartbeat: `demo: { running, part, step, total, waitingFor: "reception" | "person" | "patient" | "close" | "walk" | "hold" | "back" | null, line, nextPatientLine, ticket, runs }`. `line` = what Otto is saying or last said, `nextPatientLine` = the patient line that comes next (or is awaited now), `runs` = finished runs since the demo started. Every step is logged (info, source APP).
- Staff menu: Demo start / stop, Next, and the current step.
- Turns on the spot in every walk (walk.turnSide `right`, the default): the shorter way, except a real turn-around (the target more than 150 deg away on the left, behind) goes via the right. Never more than 210 deg. `left` mirrors it, `shortest` always takes the shorter way.

## Dashboard (team-inbox)

- `/demo`: phone-friendly operator page. Big buttons Start / Next / Hold-Resume / Restart / Stop, a "Say" box with a few quick lines ("One moment, please." / "Sorry, could you repeat that?"), Otto's state from the heartbeat (part, step, waiting for), a teleprompter "Patient says now: ..." in large type, and the whole script with the current step highlighted. Works on the laptop server without login problems on a phone (same rules as the other staff pages).
- Settings -> Demo script: edit the parts (add, remove, reorder steps, text, waitS), destination and reception (place pickers), start (person / next), person distance, "Reset to default". Saved in the store; Start sends it with the command.
- The mode switch in the header gets Demo (sends `demo start` with the saved script; leaving Demo sends `demo stop` / `set_mode`).
- The demo journey shows on reception, the map and the notifications like any other journey.
