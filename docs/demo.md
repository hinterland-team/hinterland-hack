# Nova Clinic - 3-minute demo

One loop, live: a patient checks in on Otto, walks with it to the room, controls the walk from their phone, the doctor gets the message and sends Otto to the exit. The judges watch the dashboard on a big screen.

## People and places

| Who | Where | Has |
|---|---|---|
| **Speaker** | next to the big screen, facing the judges | the clicker or nothing; talks the whole time |
| **Desk** (receptionist) | at the laptop with the dashboard (Reception page on the big screen) | the laptop |
| **Patient** | in front of Otto at the reception spot | a phone with the camera open (for the QR code) |
| **Doctor** | at the doctor's spot ("Dr. Blue" on Otto's map), 8-15 m away, visible to the judges | a phone with the Teams channel open (or the room view `/room`) |

Otto stands on its **Reception Point** spot, the way to Dr. Blue is free of bags and chairs.

## Before you go on (5 minutes before)

1. Otto: Nova Clinic started **from the robot's home screen** (otherwise it can't drive). Battery above 50 %. Volume up.
2. Dashboard, Reception page: the robot card says **Online**, shows the server it uses, no red "Out of service" box. The live map shows Otto at the reception.
3. Places: Dr. Blue has "Patients can check in for it on Otto" on. If Otto's map has a waiting room spot, set it as Dr. Blue's waiting room; if not, leave it empty and Otto walks straight to Dr. Blue (skip step 4 below).
4. Cancel old open journeys on the board, so the screen is clean.
5. Turn **"Show what patients say (demo)"** on in the Conversation card (turn it off again after the demo).
6. Second tab on the laptop: `/map`. Third tab: `/logs?level=warn`.
7. Doctor's phone: Teams channel open. If email is set up for Dr. Blue, the inbox works too.

## The script

**0:00 - 0:20 Problem** (Speaker)
"Hospital staff spend about 30 minutes a week walking lost patients around. Otto does that walk, at the patient's pace, and knows nothing about the patient but a ticket."

**0:20 - 0:50 Check-in** (Patient at Otto)
- Patient taps Dr. Blue, "slower", confirm. Otto shows the ticket (for example A17) and a QR code.
- Patient scans the QR code with the phone: the phone page opens with A17 and "Going to Dr. Blue".
- Judges see: a new row "A17" on the board, Otto's screen mirrored in the robot card, the conversation feed.
- Speaker: "No name, no reason for the visit. Just a ticket and a room."

**0:50 - 1:40 The walk** (Patient walks next to Otto, on his left, a little behind - Otto asks for it at the start; his radar sees there, not straight behind)
- Otto starts walking. Speaker switches the big screen to `/map` (or points at the live map on the Reception page): "This is Otto, this is its trail, these dots are what its LiDAR sees, and this orange dot is the patient beside it. 9 metres to go."
- Patient taps **Slower** on the phone, a few seconds later **Wait**: Otto stops. Then **Go on**.
- Patient taps **Talk** and says "How far is it?". Otto answers out loud.
- While the radar sees the patient beside him, Otto just says a short line and walks on. Every 2nd check he stops for a few seconds, turns to the patient ("All good?") and goes on. If the patient drops back for 5 s, he turns to where they were last seen.
- Judges see: on the map the arrow moving and the metres going down; in the conversation "asked Otto to walk slower (phone)" and the question; on the journey timeline every step.
- Speaker: "The patient can steer Otto from behind, where the robot's mics can't hear well."

**1:40 - 2:15 Arrival and the doctor** (Doctor)
- Otto arrives: "Please take a seat, the team knows you're here." The patient can tap thumbs up.
- The doctor's phone shows the Teams card: "A17 is on the way" or, with a waiting room, "Your visitor has arrived - ticket A17". Doctor holds it up to the judges: ticket and room, nothing else.
- With a waiting room: the doctor taps **Call in**, Otto brings A17 from the waiting room to the doctor.

**2:15 - 2:40 Exit** (Doctor)
- The doctor taps **Done - guide to exit**. Otto picks A17 up and walks to the exit: "Goodbye and get well soon."
- The phone page now says the walk has ended. The link no longer works.
- Speaker: "When the walk ends, the phone link dies. Finished journeys are deleted after 12 hours. Only anonymous counts stay."

**2:40 - 3:00 Close** (Speaker, Reception page on the big screen)
- Point at today's numbers (guides, average walk, lost patients, feedback) and at the robot card: server, errors of the last hour, the logs one click away.
- "Nova Clinic: the robot walks with your patients, not ahead of them. Any clinic can connect its own system with one API call."

## If something goes wrong on stage

| What | Do |
|---|---|
| Otto shows "can't drive" / red Out of service box | Start Nova Clinic again from the robot's home screen (not from a notification or the installer). |
| Otto is blocked by people | Ask the audience to step aside. Otto asks twice (about 25 s), backs off a little and tries again, then takes a map spot around the jam, then calls the team and waits with the patient: the help alert on the board is part of the story. |
| The phone page does not open | Show the Wait / Go on / Slower / Faster buttons in the robot card on the dashboard: same commands. |
| Talk does nothing | Speech-to-text needs https. On the laptop server (http) the patient types the question instead. |
| No Teams card | Show the room view `/room` on the doctor's phone instead, it updates every 2 seconds. |

## Plan B: no venue Wi-Fi

1. **Phone hotspot.** One phone opens a hotspot, the laptop and Otto join it. On the laptop run `npm run dev:lan` (port 3000, memory store, nothing online needed). Otto checks `/api/health` on its server list every 60 s and switches to the laptop by itself if the laptop address is in `teamInbox.urls`. Test this once before the event with the same hotspot, because the laptop gets a new IP address there. The robot card shows which server Otto uses.
2. **The patient's phone** is the hotspot phone, so it reaches the laptop. The phone page works over plain http, only Talk needs typing instead of speaking.
3. **No Teams without internet:** the doctor uses the room view on the laptop or a second phone on the hotspot.
4. **Nothing works on the robot:** run the same loop with `/simulate` in a second window next to the Reception page. Say clearly that it is the simulator. The board, the conversation, the map panel (empty without the robot) and the logs page behave the same.

## After the demo

- Turn "Show what patients say (demo)" off.
- Cancel or finish the demo journeys.
