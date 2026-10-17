# Nova Clinic - pitch text

Terra Robotics "Smart Receptionist" challenge, Hinterland Hack 2026. Robot: OrionStar GreetingBot Nova.

## Problem

Hospitals are hard to find your way in. Patients and visitors get lost, and then they ask the nearest nurse or doctor.

A 2025 study asked 301 hospital staff in the US. On average, each of them spent about 30 minutes per week helping people find their way. One help took about 7 minutes. Nearly 44% had faced incivility from people who were frustrated because they could not find their way.

Staff often don't just point. 29% walk the person all the way to where they need to go. That is time taken away from care, and Germany expects a large shortage of nursing staff in the coming decades.

The people who struggle most are often older or slow on their feet. In 2024, almost half (47%) of all inpatient cases in German hospitals were people aged 65 or older.

A sign or a map does not help much if you walk slowly, feel nervous, or don't speak German.

## Our idea

Nova Clinic turns the Nova robot into a guide that walks with the patient, not ahead of them.

- Staff at the desk decide where a patient goes. Or the patient checks in on Nova for the doctor they are here to see.
- Nova only gets an anonymous ticket (like A17) and a room. No name, no diagnosis.
- Nova walks at the patient's pace, checks they are still close, talks with them, and calls staff if someone gets lost.
- The doctor gets a Teams message and calls Nova back for the next room or the exit.

## How a visit works (step by step)

There are two ways to start.

**A) At the reception desk**
1. The receptionist picks the room and the walking pace: normal, slow, very slow, or wheelchair. This takes a few seconds.
2. The system creates a ticket, for example A17. No name.
3. Nova goes to the pickup point, shows "A17" in large letters and says: "Ticket A17, please come to Nova."
4. The patient taps "That's me" or says the ticket number.

**B) On the robot (self check-in)**
1. The patient walks up to Nova. Three big steps on the screen, no typing: "Who are you here to see?", "How shall I walk?", "Confirm".
2. The same works by voice.
3. The patient gets a ticket, for example A17. No name. "I'm not sure" sends them to reception.

**Then, for both:**

5. The doctor's room gets a Teams message with the ticket, the room, the walking pace and the expected time. After a self check-in it says: "Your visitor has arrived - ticket A17, waiting in the waiting room." No name.
6. Nova walks with the patient: straight to the room, or to the waiting room after a self check-in.
7. About every 25 seconds Nova stops and turns around. Its cameras face forward, so turning around is how it checks that someone is still close behind.
   - Someone close: "All good? We're almost there." Then it goes on.
   - A few metres behind: "I'll wait for you." It waits and walks slower from then on.
   - Nobody there: it calls out, waits, and then asks staff for help.
8. Nova talks and listens on the way. The patient can interrupt at any time: "wait", "slower", "how far is it?"
9. On arrival Nova says: "Please take a seat, the team knows you're here." The patient can give one-tap feedback on the screen (thumbs up or down). This is optional.
10. After a self check-in: when the doctor is ready, they tap "Call in" in Teams or on the dashboard. Nova picks up A17 in the waiting room and walks them to the room.
11. After the visit the doctor taps "Done - guide to exit" or sends the patient to the next room. Nova comes back and walks with them again.
12. 12 hours after the journey ends, it is deleted. Only anonymous counts stay: guides per day, average walk time, lost-patient events, feedback.

Nova speaks the language the patient speaks. The speech comes from the robot's own AI (OrionStar AgentOS), so we keep no language lists. The ticket call at pickup is in German and English.

## What makes it different

- Nova walks the way with the patient, at the patient's pace.
- It notices when someone falls behind and reacts: it waits, slows down, or calls staff.
- Staff stay in charge. Nova never decides where a patient should go for medical reasons.
- Anonymous from the start: a ticket, not a name.
- Two ways to start: at the desk, or on the robot by voice or with three taps.
- One robot covers the whole visit: to the waiting room, to the doctor, to the next room, to the exit. Staff call it back from Teams.
- Patients can talk to Nova and interrupt it at any time, in their own language.
- Staff see live on the dashboard what Nova is doing. They can also type any request to Nova ("Tell Nova anything").

## Privacy by design

GDPR still applies. We don't claim otherwise. We keep the data as small as we can.

- No names, birthdays, insurance numbers, reasons for the visit or symptoms. Not in the system, not in Teams, not on the screen, not in logs.
- A patient is only a ticket: one letter and two digits, picked at random each day.
- Needs are fixed choices only: walking pace, and wheelchair yes or no.
- The camera is used live on the robot only, to detect whether a person is close behind. No recording, no images saved, no face recognition. Nova does not know who the person is.
- Detecting a person without identifying them is not biometric processing under GDPR Article 9 (EDPB Guidelines 3/2019). A live camera image can still be personal data, so people must be told.
- The robot screen says: "Nova only knows your ticket and the room. Nothing is recorded."
- The Teams message has the ticket, room, pace and expected time. No name.
- The live dashboard shows what Nova does and says. What the patient says is shown only as a short intent, like "asked to slow down". Nova's own lines and these short intents are kept for at most 12 hours and are deleted when the journey ends. The patient's own words are not kept: a message from the phone is passed on to Nova and wiped as soon as Nova has it.
- Journeys are deleted 12 hours after they end. Only anonymous daily counts stay.
- Open point: Nova's speech runs through the robot's AI (OrionStar AgentOS). Before a real deployment we have to check where the audio is processed.

## Not a medical device

Nova Clinic is a building guide. It does not diagnose, triage or monitor anyone's health. It never asks about symptoms. Staff decide medical destinations. Patients can only ask Nova for public places, like the exit or reception, or check in for the doctor they are here to see.

Under the EU Medical Device Regulation (EU) 2017/745, a product is a medical device when the manufacturer intends it for a medical purpose, like diagnosis, monitoring or treatment (Article 2(1)). The class depends on the intended purpose (Article 51). The regulation also says that software for general purposes is not a medical device just because it is used in a hospital (Recital 19).

So Nova Clinic is likely not a medical device. This still needs a proper regulatory check before real use. That is also why we describe it as navigation and not as a medical aid.

## Numbers

- 301 US hospital staff: each spent about 30 minutes per week on average helping people find their way (mean 31.8 min, median 15 min). [Jamshidi et al., HERD: Health Environments Research & Design Journal, 2025]
- Staff helped about 4 times per week. One help took 6.9 minutes on average. [Jamshidi et al., HERD, 2025]
- 29% of staff walked people all the way to their destination. [Jamshidi et al., HERD, 2025]
- Nearly 44% had faced incivility from people frustrated by wayfinding problems. [Jamshidi et al., HERD, 2025]
- Germany 2024: 1,841 hospitals and about 17.5 million inpatient cases. [Destatis, press release no. 398, 6 Nov 2025]
- Germany 2024: about 13.0 million outpatient emergency cases in hospitals, about 35,600 per day. [Destatis, press release no. N069, 3 Dec 2025]
- Germany 2024: 47% of inpatient cases were people aged 65 or older. [own calculation from Destatis table 23131-b01, Diagnosen der Krankenhauspatienten 2024]
- Germany, end of 2025: 7.8 million people with a severe disability, 9.4% of the population. [Destatis, press release no. 246, 13 Jul 2026]
- Germany: 280,000 to 690,000 nursing staff expected to be missing by 2049. [Destatis projection, press release no. 033, 24 Jan 2024]

The study is from the US and the answers are self-reported. We use it to show the size of the problem, not as a German figure.

## Illustrative value calculation

This is an example, not a measurement.

Assumptions:
- 25 guided walks per day per robot
- 3 minutes of staff time saved per walk (the US study found 6.9 minutes per help, we use less to stay careful)
- 250 working days per year
- €30 per staff hour (an assumed cost, not a sourced figure)

Calculation:
- 25 x 3 min = 75 min per day
- 75 min x 250 days = 312.5 hours per year
- 312.5 h x €30 = €9,375 per robot per year

Caveats:
- All inputs are assumptions. A pilot has to measure them.
- Freed staff time is not cash saved. It is time back for patients.
- Robot price, setup, maintenance and staff training are not included.
- Staff still assign rooms at the desk, which takes a few seconds per patient.

## Demo at the hackathon

One full loop, live on the robot:

1. A patient walks up to Nova and checks in: three taps (doctor, walking pace, confirm) or by voice. No name. Nova gives ticket A17.
2. The doctor gets a Teams message: "Your visitor has arrived - ticket A17, waiting in the waiting room."
3. Nova walks with the patient to the waiting room. On the way it stops, turns around, checks they are close, and talks with them. The patient can say "slower" or "wait" at any time.
4. The doctor taps "Call in". Nova picks up A17 in the waiting room and brings them to the doctor's room.
5. On arrival the patient can tap thumbs up or down.
6. After the visit the doctor taps "Done - guide to exit". Nova walks the patient to the exit: "Goodbye and get well soon."
7. The whole time, the dashboard shows live what Nova is doing: a mirror of the robot screen, the conversation feed and the journey timeline. Nova's lines and the short intents are deleted when the journey ends, at the latest after 12 hours; the patient's own words are not kept.

The same loop also starts from the reception desk: staff pick the room and the pace, and Nova calls ticket A17 at the pickup point.

## Next steps

- Pilot in one clinic area with real patients and staff.
- Measure real numbers with the anonymous counters: guides per day, walk time, lost-patient events, feedback.
- Data protection check with the hospital's data protection officer, and a notice that the robot uses a camera.
- Check with OrionStar where the robot's speech AI processes audio.
- Regulatory check of the intended purpose under the MDR.
- Better navigation in real corridors: no-go lines and safe routes on the map, and a new route when the way is blocked.
- Connect to the hospital's own queue system, while keeping the patient anonymous.

## One-line summary

We don't just know the way. We make sure people get there.
