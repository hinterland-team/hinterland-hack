---
name: agentos-nova
description: Use when writing, reviewing or debugging the Nova Clinic robot app in robot-app/ (OrionStar GreetingBot Nova, AgentOS SDK 0.4.5, RobotAPI) - Actions, AppAgent/PageAgent, persona, TTS/LLM calls, proactive speech, navigation, person detection, or the Compose UI on the robot screen. Distilled from OrionStar's official AI dev rules (orionagent/agentos-sdk, AI_Rules/doc-devguide.mdc) plus what we learned on our robot.
---

# AgentOS on our Nova

OrionStar ships these rules for AI coding assistants (original, in Chinese: `references/orionstar-ai-rules-original.mdc`). This is the English version, adapted to our robot.

## 0. Our robot

- GreetingBot Nova, model OS-R-SG04, SN M03SHW2A30025032951B, name 小豹, OrionStar OS **12.1**, AgentOS (never switch it to RobotOS).
- SDK `com.orionstar.agent:sdk:0.4.5-SNAPSHOT` (newest one below ROM 12.3). It bundles RobotAPI (RobotCore). Do not use APIs that only exist in 0.4.11+ (`ttsParallel`, `ttsRequest*`, `setVoiceMode`).
- Screen: 1920 x 1080 px, density 3.5, so about 549 x 308 dp. OrionStar says: keep layouts compact, small text and components, no deep nesting, nothing outside the screen.
- Map spots on 小豹 right now: Reception Point, Charging Pole1, Home, Elevator, Stairs Down, Dr. Blue, Dr. Red, Dr. Yellow, Dr. Green, Dr. Purple. Spot names come from the backend (`/api/doctors`), never from code.
- Installing needs ADB, which needs OrionStar's dynamic password (see `references/APP_DEPLOYMENT.md`). Until then nothing can be tested on the robot.

## 1. Read the docs before writing code

Order, always:
1. Agent questions: `references/agentos-sdk-0.4.5/AgentOS_SDK_Doc_v0.4.5_en.md`, then `API_Reference.md` for exact signatures.
2. Robot control (navigation, person, head, camera, battery): `references/robot-api/RobotAPI_en.md`.
3. How to write it: `SampleCodes.md`. Imports: `ClassPathList.md` (exact package paths).
4. Problems: `references/FAQ.md`.
Only if the docs have nothing, look at the web or general Android docs. Do not guess a signature - check it in the docs or with `javap` on the jar in the Gradle cache.

## 2. Agent structure

- One `AppAgent` per app (Application class). One `PageAgent` per page (Activity/Fragment).
- `assets/actionRegistry.json` is required. Its `appId` becomes `AgentCore.appId`. Ours: agent "Team Relay" in the portal.
- Persona / style / objective: app level sets the base, page level narrows it. Persona = who, objective = what to achieve, style = how to talk.
- Dynamic actions need an `executor`. Static actions (callable from outside the app) go into `actionRegistry.json` and are handled in `AppAgent.onExecuteAction`.

## 3. Action design (this decides if the LLM picks the right action)

- One action = one job. If you need a `type` parameter to decide what to do, split the action.
- 1 to 3 parameters. Clear `desc` for the action and every parameter. Use `ParameterType.ENUM` + `enumValues` for closed sets.
- Name: `com.hinterland.clinic.ACTION_NAME` (upper case after the last dot).
- Parameter names in English with underscores, not the same as fields of `Action`/`Parameter`.

## 4. Async rules (break these and the agent hangs)

- `ActionExecutor.onExecute()` has a **2 second limit**. Return `true` at once and do the work in `AOCoroutineScope.launch { }` (network on `Dispatchers.IO`).
- Call `action.notify()` exactly once on every path, and only **after** TTS/LLM work is finished. Failure: `action.notify(ActionResult(ActionStatus.FAILED))`.
- `notify(isTriggerFollowUp = true)` lets the robot proactively suggest the next step.
- `TaskResult.status`: 1 = success, 2 = failure.

## 5. Voice and "thinking"

- On AgentOS the old RobotAPI ASR/TTS/NLP are switched off. All speech goes through `AgentCore` (`tts`/`ttsSync`, `llm`/`llmSync`, `query`).
- The mic plus the LLM pick actions by themselves. Use `llm`/`llmSync` only when an action can't express it (free text generation, summaries, robot-initiated sentences in the patient's language).
- To start talking on its own (proactive): `AgentCore.query("...")` (acts as if the user said it) or `notify(isTriggerFollowUp = true)`.
- Tell the LLM what is on screen with `AgentCore.uploadInterfaceInfo(...)`. It **replaces** the previous info each call, so send the full state.
- `AgentCore.clearContext()` when a patient is done, so the next one starts fresh.
- Wake-free (`isEnableWakeFree`, default on): mic only listens when someone faces the robot. Good for a busy reception. Wake word mode exists in 0.4.5 (`enableWakeupMode`).
- Listeners (`setOnTranscribeListener`, `setOnAgentStatusChangedListener`) run on a worker thread; return `false` to keep the system subtitles / voice bar.

## 6. Robot control

- Connect RobotAPI first (`RobotApi.getInstance().connectServer(...)`), see RobotAPI doc "Connect to Server".
- Navigation to a map spot by exact name; handle the error codes (blocked, out of map, not localized). Do not mix navigation with manual turn/move calls at the same time (resource conflict, see FAQ).
- Person detection / face: use PersonApi. Do not open the camera with the Android API, that breaks the robot's own vision. For frames use the shared stream (SurfaceShare).
- "Leading" (startLead) only works on Xiaobao Express, not on Nova.

## 7. Our project rules

- Backend contract: `docs/api.md`. Keep it in sync when an endpoint changes.
- No hard-coded doctors, spots, languages or phrase lists in code. Robot-initiated sentences: English instruction + BCP-47 language tag into `llmSync`.
- Never collect symptoms, reason for the visit, birthday, insurance. Name, doctor, time only.
- Build: `cd robot-app && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug` (Gradle daemon is pinned to JDK 21). Tests: `testDebugUnitTest`.
- Kotlin only, no Java-style code mixed in. Comments short and plain.
- Do not write summary/README files unless asked (OrionStar rule, and the user dislikes AI-sounding docs).
