// postgres store (neon). only type imports here so the sql can be tested outside next
import type { ChannelType, NotifyChannel } from "./channel-types";
import type { DemoOp, DemoScript, DemoState } from "./demo-script";
import type { MapFrame } from "./robot-data";
import type {
  ChannelPatch,
  JourneyChange,
  LogQuery,
  NewJourney,
  PlacePatch,
  PurgeCutoffs,
  RobotRecord,
  Store,
  StoredJourney,
} from "./store-types";
import type {
  Counters,
  FeedItem,
  HelpRequest,
  JourneyEvent,
  JourneyStatus,
  Leg,
  LegStatus,
  LogLevel,
  LogLine,
  LogSource,
  MapState,
  Pace,
  Place,
  PlaceKind,
  RobotCommand,
  RobotHeartbeat,
  RobotScreen,
  StatsRow,
  WalkAction,
} from "./types";

type Row = Record<string, unknown>;
// the demo command's ops (lib/demo-script.ts DEMO_OPS), here as text so this file keeps type imports only
const DEMO_OP_LIST = ["start", "next", "hold", "resume", "restart", "stop", "say"];
export type SqlClient = { query(text: string, params?: unknown[]): Promise<Row[]> };

function iso(value: unknown): string {
  return (value instanceof Date ? value : new Date(String(value))).toISOString();
}

function isoOrUndefined(value: unknown) {
  return value === null || value === undefined ? undefined : iso(value);
}

function textOrNull(value: unknown) {
  return value === null || value === undefined ? null : String(value);
}

function json<T>(value: unknown): T {
  return (typeof value === "string" ? JSON.parse(value) : value) as T;
}

function toPlace(r: Row): Place {
  const p: Place = {
    id: String(r.id),
    name: String(r.name),
    spot: String(r.spot),
    kind: String(r.kind) as PlaceKind,
    public: Boolean(r.public),
    avgMinutes: Number(r.avg_minutes),
    active: Boolean(r.active),
    selfCheckIn: Boolean(r.self_check_in),
    createdAt: iso(r.created_at),
  };
  if (r.room) p.room = String(r.room);
  if (r.teams_webhook_url) p.teamsWebhookUrl = String(r.teams_webhook_url);
  if (r.waiting_place_id) p.waitingPlaceId = String(r.waiting_place_id);
  if (r.via_spot) p.viaSpot = String(r.via_spot);
  if (r.notify_email) p.notifyEmail = String(r.notify_email);
  const ids = json<unknown>(r.channel_ids);
  if (Array.isArray(ids)) p.channelIds = ids.map(String);
  if (r.map_name) p.mapName = String(r.map_name);
  return p;
}

function toChannel(r: Row): NotifyChannel {
  const config = json<Record<string, unknown> | null>(r.config) ?? {};
  return {
    id: String(r.id),
    type: String(r.type) as ChannelType,
    label: String(r.label),
    config: Object.fromEntries(Object.entries(config).map(([k, v]) => [k, String(v)])),
    isDefault: Boolean(r.is_default),
    active: Boolean(r.active),
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
  };
}

function toLeg(r: Row): Leg {
  const leg: Leg = {
    id: String(r.id),
    fromPlaceId: r.from_place_id === null || r.from_place_id === undefined ? null : String(r.from_place_id),
    toPlaceId: String(r.to_place_id),
    toName: String(r.to_name),
    toSpot: String(r.to_spot),
    status: String(r.status) as LegStatus,
    createdAt: iso(r.created_at),
  };
  if (r.from_name) leg.fromName = String(r.from_name);
  if (r.from_spot) leg.fromSpot = String(r.from_spot);
  if (r.to_room) leg.toRoom = String(r.to_room);
  const started = isoOrUndefined(r.started_at);
  const arrived = isoOrUndefined(r.arrived_at);
  if (started) leg.startedAt = started;
  if (arrived) leg.arrivedAt = arrived;
  return leg;
}

function toEvent(r: Row): JourneyEvent {
  const e: JourneyEvent = { at: iso(r.at), type: String(r.type) };
  if (r.detail !== null && r.detail !== undefined) e.detail = String(r.detail);
  return e;
}

function toJourney(r: Row): StoredJourney {
  const j: StoredJourney = {
    id: String(r.id),
    ticket: String(r.ticket),
    robot: String(r.robot),
    pace: String(r.pace) as Pace,
    wheelchair: Boolean(r.wheelchair),
    status: String(r.status) as JourneyStatus,
    legs: (json<Row[] | null>(r.legs) ?? []).map(toLeg),
    createdBy: r.created_by === "robot" ? "robot" : "staff",
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    events: (json<Row[] | null>(r.events) ?? []).map(toEvent),
    version: Number(r.version),
  };
  if (r.for_place_id) j.forPlaceId = String(r.for_place_id);
  const ended = isoOrUndefined(r.ended_at);
  if (ended) j.endedAt = ended;
  if (r.walk_code) j.walkCode = String(r.walk_code);
  return j;
}

// fields a command type does not use stay absent
function toCommand(r: Row): RobotCommand {
  const id = String(r.id);
  const robot = String(r.robot);
  const createdAt = iso(r.created_at);
  let c: RobotCommand;
  if (r.type === "say") c = { id, robot, type: "say", text: String(r.text), createdAt };
  else if (r.type === "ask") {
    // old_text: the text before the delivery wiped a phone question
    const t = r.old_text ?? r.text;
    c = { id, robot, type: "ask", text: t === null || t === undefined ? "" : String(t), createdAt };
    if (r.journey_id) c.journeyId = String(r.journey_id);
    if (r.origin === "phone" || r.origin === "staff") c.origin = r.origin;
  } else if (r.type === "go_to") c = { id, robot, type: "go_to", spot: String(r.spot), createdAt };
  // switch_map keeps the map name in the text column (no extra column)
  else if (r.type === "switch_map") c = { id, robot, type: "switch_map", mapName: String(r.text ?? ""), spot: String(r.spot ?? ""), createdAt };
  else if (r.type === "config_changed") c = { id, robot, type: "config_changed", createdAt };
  else if (r.type === "recheck") c = { id, robot, type: "recheck", createdAt };
  else if (r.type === "set_mode") {
    const mode = r.action === "show" || r.action === "calm" ? r.action : "normal";
    c = { id, robot, type: "set_mode", mode, createdAt };
  } else if (r.type === "demo") {
    // the op in the action column, the say text or the start's script (json) in text
    const op = DEMO_OP_LIST.includes(String(r.action)) ? (String(r.action) as DemoOp) : "next";
    const d: RobotCommand = { id, robot, type: "demo", op, createdAt };
    if (op === "say" && r.text) d.text = String(r.text);
    if (op === "start" && r.text) {
      try {
        d.script = JSON.parse(String(r.text)) as DemoScript;
      } catch {
        // a broken script: otto uses its built-in one
      }
    }
    c = d;
  }
  else if (r.type === "walk_control") {
    c = { id, robot, type: "walk_control", journeyId: String(r.journey_id), action: String(r.action) as WalkAction, createdAt };
    if (r.origin === "phone" || r.origin === "staff") c.origin = r.origin;
  } else {
    c = {
      id,
      robot,
      type: "guide",
      journeyId: String(r.journey_id),
      legId: String(r.leg_id),
      ticket: String(r.ticket),
      pace: String(r.pace) as Pace,
      wheelchair: Boolean(r.wheelchair),
      from: { name: String(r.from_name), spot: String(r.from_spot) },
      to: {
        name: String(r.to_name),
        spot: String(r.to_spot),
        ...(r.to_room ? { room: String(r.to_room) } : {}),
        ...(r.to_via_spot ? { viaSpot: String(r.to_via_spot) } : {}),
      },
      ...(r.walk_code ? { walkCode: String(r.walk_code) } : {}),
      createdAt,
    };
  }
  const delivered = isoOrUndefined(r.delivered_at);
  const done = isoOrUndefined(r.done_at);
  if (delivered) c.deliveredAt = delivered;
  if (done) c.doneAt = done;
  if (r.done_ok !== null && r.done_ok !== undefined) c.ok = Boolean(r.done_ok);
  if (r.done_detail) c.detail = String(r.done_detail);
  return c;
}

function toHeartbeat(r: Row): RobotHeartbeat {
  return {
    robot: String(r.status_robot),
    battery: r.battery === null || r.battery === undefined ? null : Number(r.battery),
    charging: Boolean(r.charging),
    state: textOrNull(r.state),
    position: textOrNull(r.position),
    goal: textOrNull(r.goal),
    detail: textOrNull(r.detail),
    screen: r.screen === null || r.screen === undefined ? null : json<RobotScreen>(r.screen),
    server: textOrNull(r.server),
    version: textOrNull(r.app_version),
    mapName: textOrNull(r.map_name),
    localised: r.localised === null || r.localised === undefined ? null : Boolean(r.localised),
    configVersion: r.config_version === null || r.config_version === undefined ? null : Number(r.config_version),
    mode: textOrNull(r.mode),
    modePending: textOrNull(r.mode_pending),
    demo: r.demo === null || r.demo === undefined ? null : json<DemoState>(r.demo),
    at: iso(r.updated_at),
  };
}

function toLog(r: Row): LogLine {
  return {
    id: Number(r.id),
    robot: String(r.robot),
    at: iso(r.at),
    level: String(r.level) as LogLevel,
    source: String(r.source) as LogSource,
    text: String(r.text),
  };
}

function toMap(r: Row): MapState {
  const m: MapState = {
    robot: String(r.robot),
    pose: json<MapState["pose"]>(r.pose),
    places: json<MapState["places"] | null>(r.places) ?? [],
    scan: json<MapState["scan"] | null>(r.scan) ?? [],
    trail: json<MapState["trail"] | null>(r.trail) ?? [],
    updatedAt: iso(r.updated_at),
  };
  if (r.map_name) m.mapName = String(r.map_name);
  if (r.follower) m.follower = json<NonNullable<MapState["follower"]>>(r.follower);
  if (r.target) m.target = json<NonNullable<MapState["target"]>>(r.target);
  if (r.remaining_meters !== null && r.remaining_meters !== undefined) m.remainingMeters = Number(r.remaining_meters);
  const maps = json<MapState["maps"] | null>(r.maps);
  if (Array.isArray(maps)) m.maps = maps;
  return m;
}

function toFeed(r: Row): FeedItem {
  const f: FeedItem = {
    id: String(r.id),
    robot: String(r.robot),
    at: iso(r.at),
    who: r.who === "patient" ? "patient" : "nova",
    kind: String(r.kind) as FeedItem["kind"],
    text: String(r.text),
  };
  if (r.journey_id) f.journeyId = String(r.journey_id);
  return f;
}

function toHelp(r: Row): HelpRequest {
  const h: HelpRequest = {
    id: String(r.id),
    robot: String(r.robot),
    summary: String(r.summary),
    urgency: r.urgency === "urgent" ? "urgent" : "normal",
    status: r.status === "done" ? "done" : "open",
    notified: Boolean(r.notified),
    createdAt: iso(r.created_at),
  };
  if (r.journey_id) h.journeyId = String(r.journey_id);
  if (r.ticket) h.ticket = String(r.ticket);
  const handled = isoOrUndefined(r.handled_at);
  if (handled) h.handledAt = handled;
  return h;
}

const JOURNEY_SELECT = `
  select j.*,
    coalesce((
      select json_agg(json_build_object(
        'id', l.id, 'from_place_id', l.from_place_id, 'from_name', l.from_name, 'from_spot', l.from_spot,
        'to_place_id', l.to_place_id, 'to_name', l.to_name, 'to_spot', l.to_spot, 'to_room', l.to_room,
        'status', l.status, 'created_at', l.created_at, 'started_at', l.started_at, 'arrived_at', l.arrived_at
      ) order by l.seq)
      from journey_legs l where l.journey_id = j.id
    ), '[]'::json) as legs,
    coalesce((
      select json_agg(json_build_object('at', e.at, 'type', e.type, 'detail', e.detail) order by e.at, e.id)
      from journey_events e where e.journey_id = j.id
    ), '[]'::json) as events
  from journeys j`;

const EVENTS_JSON = `json_to_recordset($EV::json) as x(at timestamptz, type text, detail text)`;

function eventsParam(events: JourneyEvent[]) {
  return JSON.stringify(events.map((e) => ({ at: e.at, type: e.type, detail: e.detail ?? null })));
}

const STAT_COLUMNS: [keyof Counters, string][] = [
  ["journeys", "journeys"],
  ["guides", "guides"],
  ["done", "done"],
  ["cancelled", "cancelled"],
  ["walks", "walks"],
  ["walkSeconds", "walk_seconds"],
  ["lostPatients", "lost_patients"],
  ["feedbackUp", "feedback_up"],
  ["feedbackDown", "feedback_down"],
];

export function createPgStore(sql: SqlClient): Store {
  async function getJourney(id: string) {
    const rows = await sql.query(`${JOURNEY_SELECT} where j.id = $1`, [id]);
    return rows[0] ? toJourney(rows[0]) : null;
  }

  return {
    kind: "postgres",

    async listPlaces() {
      const rows = await sql.query(
        `select * from places
         order by array_position(array['reception','waiting','room','other','exit'], kind), lower(name), created_at`,
      );
      return rows.map(toPlace);
    },
    async getPlace(id) {
      const rows = await sql.query(`select * from places where id = $1`, [id]);
      return rows[0] ? toPlace(rows[0]) : null;
    },
    async createPlace(p) {
      const rows = await sql.query(
        `insert into places (id, name, room, spot, kind, public, avg_minutes, teams_webhook_url, active, waiting_place_id, self_check_in, created_at, via_spot, notify_email, channel_ids, map_name)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::timestamptz, $13, $14, $15::jsonb, $16) returning *`,
        [
          p.id,
          p.name,
          p.room ?? null,
          p.spot,
          p.kind,
          p.public,
          p.avgMinutes,
          p.teamsWebhookUrl ?? null,
          p.active,
          p.waitingPlaceId ?? null,
          p.selfCheckIn,
          p.createdAt,
          p.viaSpot ?? null,
          p.notifyEmail ?? null,
          p.channelIds ? JSON.stringify(p.channelIds) : null,
          p.mapName ?? null,
        ],
      );
      return toPlace(rows[0]);
    },
    async updatePlace(id, patch: PlacePatch) {
      const rows = await sql.query(
        `update places set
           name = coalesce($2::text, name),
           spot = coalesce($3::text, spot),
           kind = coalesce($4::text, kind),
           public = coalesce($5::boolean, public),
           avg_minutes = coalesce($6::integer, avg_minutes),
           active = coalesce($7::boolean, active),
           self_check_in = coalesce($8::boolean, self_check_in),
           room = case when $9::boolean then $10::text else room end,
           teams_webhook_url = case when $11::boolean then $12::text else teams_webhook_url end,
           waiting_place_id = case when $13::boolean then $14::text else waiting_place_id end,
           via_spot = case when $15::boolean then $16::text else via_spot end,
           notify_email = case when $17::boolean then $18::text else notify_email end,
           channel_ids = case when $19::boolean then $20::jsonb else channel_ids end,
           map_name = case when $21::boolean then $22::text else map_name end
         where id = $1 returning *`,
        [
          id,
          patch.name ?? null,
          patch.spot ?? null,
          patch.kind ?? null,
          patch.public ?? null,
          patch.avgMinutes ?? null,
          patch.active ?? null,
          patch.selfCheckIn ?? null,
          patch.room !== undefined,
          patch.room || null,
          patch.teamsWebhookUrl !== undefined,
          patch.teamsWebhookUrl || null,
          patch.waitingPlaceId !== undefined,
          patch.waitingPlaceId || null,
          patch.viaSpot !== undefined,
          patch.viaSpot || null,
          patch.notifyEmail !== undefined,
          patch.notifyEmail || null,
          patch.channelIds !== undefined,
          patch.channelIds ? JSON.stringify(patch.channelIds) : null,
          patch.mapName !== undefined,
          patch.mapName || null,
        ],
      );
      return rows[0] ? toPlace(rows[0]) : null;
    },
    async deletePlace(id) {
      const rows = await sql.query(
        `with d as (delete from places where id = $1 returning id),
         u as (update places set waiting_place_id = null where waiting_place_id in (select id from d) returning id)
         select id from d`,
        [id],
      );
      return rows.length > 0;
    },

    async createJourney(j: NewJourney, leg: Leg, events) {
      const rows = await sql.query(
        `with j as (
           insert into journeys (id, ticket, ticket_day, robot, pace, wheelchair, status, for_place_id, created_by, created_at, updated_at, version, walk_code)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::timestamptz, $11::timestamptz, 1, $23)
           on conflict (ticket_day, ticket) do nothing
           returning id
         ), l as (
           insert into journey_legs (id, journey_id, seq, from_place_id, from_name, from_spot, to_place_id, to_name, to_spot, to_room, status, created_at)
           select $12, j.id, 1, $13, $14, $15, $16, $17, $18, $19, $20, $21::timestamptz from j
           returning id
         ), e as (
           insert into journey_events (journey_id, at, type, detail)
           select j.id, x.at, x.type, x.detail from j, ${EVENTS_JSON.replace("$EV", "$22")}
           returning id
         )
         select j.id from j`,
        [
          j.id,
          j.ticket,
          j.ticketDay,
          j.robot,
          j.pace,
          j.wheelchair,
          j.status,
          j.forPlaceId ?? null,
          j.createdBy,
          j.createdAt,
          j.updatedAt,
          leg.id,
          leg.fromPlaceId,
          leg.fromName ?? null,
          leg.fromSpot ?? null,
          leg.toPlaceId,
          leg.toName,
          leg.toSpot,
          leg.toRoom ?? null,
          leg.status,
          leg.createdAt,
          eventsParam(events),
          j.walkCode ?? null,
        ],
      );
      return rows[0] ? getJourney(j.id) : null;
    },
    async usedTickets(day) {
      const rows = await sql.query(`select ticket from journeys where ticket_day = $1`, [day]);
      return rows.map((r) => String(r.ticket));
    },
    getJourney,
    async findOpenByTicket(ticket) {
      const rows = await sql.query(
        `${JOURNEY_SELECT} where j.ticket = $1 and j.status not in ('done', 'cancelled') order by j.created_at desc limit 1`,
        [ticket],
      );
      return rows[0] ? toJourney(rows[0]) : null;
    },
    async listJourneys(q) {
      const rows = await sql.query(
        `${JOURNEY_SELECT}
         where ($1::boolean is not true or j.status not in ('done', 'cancelled'))
         order by j.created_at desc, j.id desc
         limit 300`,
        [q.active ?? false],
      );
      return rows.map(toJourney);
    },
    async saveJourney(id, version, change: JourneyChange) {
      const nl = change.newLeg;
      const rows = await sql.query(
        `with j as (
           update journeys set
             status = coalesce($3::text, status),
             ended_at = coalesce($4::timestamptz, ended_at),
             updated_at = $5::timestamptz,
             for_place_id = case when $12::boolean then $13::text else for_place_id end,
             version = version + 1
           where id = $1 and version = $2
           returning id
         ), l as (
           update journey_legs set
             status = coalesce($7::text, status),
             started_at = coalesce(started_at, $8::timestamptz),
             arrived_at = coalesce(arrived_at, $9::timestamptz)
           where id = $6::text and journey_id in (select id from j)
           returning id
         ), n as (
           insert into journey_legs (id, journey_id, seq, from_place_id, from_name, from_spot, to_place_id, to_name, to_spot, to_room, status, created_at)
           select x.id, j.id, (select coalesce(max(seq), 0) + 1 from journey_legs where journey_id = j.id),
                  x.from_place_id, x.from_name, x.from_spot, x.to_place_id, x.to_name, x.to_spot, x.to_room, x.status, x.created_at
           from j, json_to_recordset($10::json) as x(
             id text, from_place_id text, from_name text, from_spot text, to_place_id text, to_name text,
             to_spot text, to_room text, status text, created_at timestamptz)
           returning id
         ), e as (
           insert into journey_events (journey_id, at, type, detail)
           select j.id, x.at, x.type, x.detail from j, ${EVENTS_JSON.replace("$EV", "$11")}
           returning id
         )
         select j.id from j`,
        [
          id,
          version,
          change.status ?? null,
          change.endedAt ?? null,
          change.updatedAt,
          change.leg?.id ?? null,
          change.leg?.status ?? null,
          change.leg?.startedAt ?? null,
          change.leg?.arrivedAt ?? null,
          JSON.stringify(
            nl
              ? [
                  {
                    id: nl.id,
                    from_place_id: nl.fromPlaceId,
                    from_name: nl.fromName ?? null,
                    from_spot: nl.fromSpot ?? null,
                    to_place_id: nl.toPlaceId,
                    to_name: nl.toName,
                    to_spot: nl.toSpot,
                    to_room: nl.toRoom ?? null,
                    status: nl.status,
                    created_at: nl.createdAt,
                  },
                ]
              : [],
          ),
          eventsParam(change.events),
          change.forPlaceId !== undefined,
          change.forPlaceId ?? null,
        ],
      );
      return rows[0] ? getJourney(id) : null;
    },

    async createCommand(command) {
      const c: RobotCommand = command;
      const g = c.type === "guide" ? c : null;
      const journeyId = c.type === "guide" || c.type === "walk_control" ? c.journeyId : c.type === "ask" ? c.journeyId : undefined;
      const rows = await sql.query(
        `insert into robot_commands (id, robot, type, journey_id, leg_id, ticket, pace, wheelchair,
           from_name, from_spot, to_name, to_spot, to_room, spot, text, created_at, to_via_spot, walk_code, action, origin)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16::timestamptz, $17, $18, $19, $20) returning *`,
        [
          c.id,
          c.robot,
          c.type,
          journeyId ?? null,
          g?.legId ?? null,
          g?.ticket ?? null,
          g?.pace ?? null,
          g ? g.wheelchair : null,
          g?.from.name ?? null,
          g?.from.spot ?? null,
          g?.to.name ?? null,
          g?.to.spot ?? null,
          g?.to.room ?? null,
          c.type === "go_to" || c.type === "switch_map" ? c.spot : null,
          // demo: the say text, or the script of a start as json (no extra column)
          c.type === "say" || c.type === "ask"
            ? c.text
            : c.type === "switch_map"
              ? c.mapName
              : c.type === "demo"
                ? c.op === "start"
                  ? c.script
                    ? JSON.stringify(c.script)
                    : null
                  : (c.text ?? null)
                : null,
          c.createdAt,
          g?.to.viaSpot ?? null,
          g?.walkCode ?? null,
          c.type === "walk_control" ? c.action : c.type === "set_mode" ? c.mode : c.type === "demo" ? c.op : null,
          c.type === "walk_control" || c.type === "ask" ? (c.origin ?? null) : null,
        ],
      );
      return toCommand(rows[0]) as typeof command;
    },
    async takeNextCommand(robot, now) {
      // a phone question loses its text in the db once otto has it (old_text is the value before)
      const rows = await sql.query(
        `update robot_commands c set delivered_at = $2::timestamptz,
           text = case when c.type = 'ask' and c.origin = 'phone' then null else c.text end
         from (
           select id, text as old_text from robot_commands
           where robot = $1 and delivered_at is null and done_at is null
           order by created_at, id
           limit 1
           for update skip locked
         ) p
         where c.id = p.id
         returning c.*, p.old_text`,
        [robot, now],
      );
      return rows[0] ? toCommand(rows[0]) : null;
    },
    async finishCommand(id, now, result) {
      // the first answer stays (done_ok on the right side is the value before this update)
      const rows = await sql.query(
        `update robot_commands set done_at = coalesce(done_at, $2::timestamptz),
           done_ok = coalesce(done_ok, $3::boolean),
           done_detail = case when done_ok is null then $4::text else done_detail end
         where id = $1 returning *`,
        [id, now, result ? result.ok : null, result?.detail ?? null],
      );
      return rows[0] ? toCommand(rows[0]) : null;
    },
    async closeJourneyCommands(journeyId, now, onlyUndelivered) {
      await sql.query(
        `update robot_commands set done_at = $2::timestamptz
         where journey_id = $1 and done_at is null and ($3::boolean is not true or delivered_at is null)`,
        [journeyId, now, onlyUndelivered],
      );
    },
    async listCommands(robot, limit) {
      const rows = await sql.query(`select * from robot_commands where robot = $1 order by created_at desc, id desc limit $2`, [
        robot,
        limit,
      ]);
      return rows.map(toCommand);
    },

    async touchRobot(name, now) {
      await sql.query(
        `insert into robots (name, last_seen_at) values ($1, $2::timestamptz)
         on conflict (name) do update set last_seen_at = excluded.last_seen_at`,
        [name, now],
      );
    },
    async saveHeartbeat(h) {
      await sql.query(
        `with s as (
           insert into robot_status (robot, battery, charging, state, position, goal, detail, screen, updated_at, server, app_version, map_name, localised, config_version, mode, mode_pending, demo)
           values ($1, $2, $3, $4, $5, $6, $7, $9::jsonb, $8::timestamptz, $10, $11, $12, $13, $14, $15, $16, $17::jsonb)
           on conflict (robot) do update set
             battery = excluded.battery, charging = excluded.charging, state = excluded.state,
             position = excluded.position, goal = excluded.goal, detail = excluded.detail,
             screen = excluded.screen, updated_at = excluded.updated_at, server = excluded.server,
             app_version = excluded.app_version, map_name = excluded.map_name, localised = excluded.localised,
             config_version = excluded.config_version, mode = excluded.mode, mode_pending = excluded.mode_pending,
             demo = excluded.demo
           returning robot
         )
         insert into robots (name, last_seen_at) select robot, $8::timestamptz from s
         on conflict (name) do update set last_seen_at = excluded.last_seen_at`,
        [
          h.robot,
          h.battery,
          h.charging,
          h.state,
          h.position,
          h.goal,
          h.detail,
          h.at,
          h.screen ? JSON.stringify(h.screen) : null,
          h.server,
          h.version,
          h.mapName ?? null,
          h.localised ?? null,
          h.configVersion ?? null,
          h.mode ?? null,
          h.modePending ?? null,
          h.demo ? JSON.stringify(h.demo) : null,
        ],
      );
    },
    async getRobot(name): Promise<RobotRecord> {
      const rows = await sql.query(
        `select r.last_seen_at, s.robot as status_robot, s.battery, s.charging, s.state, s.position, s.goal, s.detail, s.screen, s.server, s.app_version, s.map_name, s.localised, s.config_version, s.mode, s.mode_pending, s.demo, s.updated_at
         from (select $1::text as name) q
         left join robots r on r.name = q.name
         left join robot_status s on s.robot = q.name`,
        [name],
      );
      const r = rows[0] ?? {};
      return {
        lastSeenAt: isoOrUndefined(r.last_seen_at),
        heartbeat: r.status_robot === null || r.status_robot === undefined ? undefined : toHeartbeat(r),
      };
    },

    async createHelp(h) {
      const rows = await sql.query(
        `insert into help_requests (id, robot, summary, urgency, status, notified, journey_id, ticket, created_at)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz) returning *`,
        [h.id, h.robot, h.summary, h.urgency, h.status, h.notified, h.journeyId ?? null, h.ticket ?? null, h.createdAt],
      );
      return toHelp(rows[0]);
    },
    async listHelp(q) {
      const rows = await sql.query(
        `select * from help_requests where ($1::boolean is not true or status = 'open')
         order by created_at desc limit 100`,
        [q.open ?? false],
      );
      return rows.map(toHelp);
    },
    async updateHelp(id, status, now) {
      const rows = await sql.query(
        `update help_requests set status = $2::text,
           handled_at = case when $2::text = 'done' then $3::timestamptz else null end
         where id = $1 returning *`,
        [id, status, now],
      );
      return rows[0] ? toHelp(rows[0]) : null;
    },
    async setHelpNotified(id, notified) {
      await sql.query(`update help_requests set notified = $2 where id = $1`, [id, notified]);
    },

    async bumpStats(day, placeId, delta: Partial<Counters>) {
      const values = STAT_COLUMNS.map(([k]) => Math.round(delta[k] ?? 0));
      const cols = STAT_COLUMNS.map(([, c]) => c);
      await sql.query(
        `insert into daily_stats (day, place_id, ${cols.join(", ")})
         values ($1, $2, ${cols.map((_, i) => `$${i + 3}`).join(", ")})
         on conflict (day, place_id) do update set
           ${cols.map((c) => `${c} = daily_stats.${c} + excluded.${c}`).join(", ")}`,
        [day, placeId, ...values],
      );
    },
    async getStats(day) {
      const rows = await sql.query(`select * from daily_stats where day = $1`, [day]);
      return rows.map((r) => {
        const row = { placeId: String(r.place_id) } as StatsRow;
        for (const [k, c] of STAT_COLUMNS) row[k] = Number(r[c] ?? 0);
        return row;
      });
    },

    async getSetting(key) {
      const rows = await sql.query(`select value from settings where key = $1`, [key]);
      return rows[0] ? String(rows[0].value) : null;
    },
    async setSetting(key, value) {
      await sql.query(
        `insert into settings (key, value) values ($1, $2) on conflict (key) do update set value = excluded.value`,
        [key, value],
      );
    },

    async listChannels() {
      const rows = await sql.query(`select * from notify_channels order by created_at, id`);
      return rows.map(toChannel);
    },
    async createChannel(c) {
      const rows = await sql.query(
        `insert into notify_channels (id, type, label, config, is_default, active, created_at, updated_at)
         values ($1, $2, $3, $4::jsonb, $5, $6, $7::timestamptz, $8::timestamptz) returning *`,
        [c.id, c.type, c.label, JSON.stringify(c.config), c.isDefault, c.active, c.createdAt, c.updatedAt],
      );
      return toChannel(rows[0]);
    },
    async updateChannel(id, patch: ChannelPatch) {
      const rows = await sql.query(
        `update notify_channels set
           label = coalesce($2::text, label),
           config = coalesce($3::jsonb, config),
           is_default = coalesce($4::boolean, is_default),
           active = coalesce($5::boolean, active),
           updated_at = $6::timestamptz
         where id = $1 returning *`,
        [
          id,
          patch.label ?? null,
          patch.config ? JSON.stringify(patch.config) : null,
          patch.isDefault ?? null,
          patch.active ?? null,
          patch.updatedAt,
        ],
      );
      return rows[0] ? toChannel(rows[0]) : null;
    },
    async deleteChannel(id) {
      const rows = await sql.query(
        `with d as (delete from notify_channels where id = $1 returning id),
         u as (
           update places set channel_ids = case
               when jsonb_array_length(channel_ids - $1::text) = 0 then null
               else channel_ids - $1::text end
           where channel_ids @> jsonb_build_array($1::text) and exists (select 1 from d)
           returning id
         )
         select id from d`,
        [id],
      );
      return rows.length > 0;
    },

    async countLoginFailures(key, since) {
      const rows = await sql.query(`select count(*)::int as n from login_failures where key = $1 and at >= $2::timestamptz`, [
        key,
        since,
      ]);
      return Number(rows[0]?.n ?? 0);
    },
    async addLoginFailure(key, at) {
      await sql.query(`insert into login_failures (key, at) values ($1, $2::timestamptz)`, [key, at]);
    },
    async clearLoginFailures(key) {
      await sql.query(`delete from login_failures where key = $1`, [key]);
    },

    async findJourneyByWalkCode(code) {
      const rows = await sql.query(`${JOURNEY_SELECT} where j.walk_code = $1`, [code]);
      return rows[0] ? toJourney(rows[0]) : null;
    },

    async addLogs(robot, items, receivedAt, keepSince, maxRows) {
      if (items.length) {
        await sql.query(
          `insert into robot_logs (robot, at, received_at, level, source, text)
           select $1, x.at, $2::timestamptz, x.level, x.source, x.text
           from json_to_recordset($3::json) as x(at timestamptz, level text, source text, text text)`,
          [robot, receivedAt, JSON.stringify(items)],
        );
      }
      await sql.query(
        `delete from robot_logs where robot = $1 and (
           received_at < $2::timestamptz
           or id <= coalesce((select id from robot_logs where robot = $1 order by id desc offset $3 limit 1), 0)
         )`,
        [robot, keepSince, maxRows],
      );
      return items.length;
    },
    async listLogs(q: LogQuery) {
      const tail = q.after !== undefined;
      const rows = await sql.query(
        `select * from robot_logs
         where robot = $1
           and ($2::bigint is null or id > $2::bigint)
           and ($3::bigint is null or id < $3::bigint)
           and ($4::text[] is null or level = any($4::text[]))
           and ($5::text[] is null or source = any($5::text[]))
           and ($6::text is null or strpos(lower(text), lower($6::text)) > 0)
         order by id ${tail ? "asc" : "desc"}
         limit $7`,
        [q.robot, q.after ?? null, q.before ?? null, q.levels ?? null, q.sources ?? null, q.search ?? null, q.limit],
      );
      const list = rows.map(toLog);
      return tail ? list : list.reverse();
    },
    async countLogs(robot, levels, since) {
      const rows = await sql.query(
        `select count(*)::int as n, max(at) as last_at from robot_logs
         where robot = $1 and level = any($2::text[]) and received_at >= $3::timestamptz`,
        [robot, levels, since],
      );
      return { count: Number(rows[0]?.n ?? 0), lastAt: isoOrUndefined(rows[0]?.last_at) ?? null };
    },

    async saveMap(f: MapFrame, trailMax) {
      const pose = JSON.stringify({ x: f.pose.x, y: f.pose.y, at: f.at });
      const rows = await sql.query(
        `insert into robot_maps (robot, map_name, pose, places, scan, follower, target, remaining_meters, trail, updated_at, maps)
         values ($1, $2, $3::jsonb, coalesce($4::jsonb, '[]'::jsonb), $5::jsonb, $6::jsonb, $7::jsonb, $8, jsonb_build_array($9::jsonb), $10::timestamptz, $12::jsonb)
         on conflict (robot) do update set
           map_name = coalesce(excluded.map_name, robot_maps.map_name),
           pose = excluded.pose,
           places = case
             when $4::jsonb is not null then excluded.places
             when excluded.map_name is not null and robot_maps.map_name is distinct from excluded.map_name then '[]'::jsonb
             else robot_maps.places end,
           scan = excluded.scan,
           follower = excluded.follower,
           target = excluded.target,
           remaining_meters = excluded.remaining_meters,
           maps = coalesce(excluded.maps, robot_maps.maps),
           trail = case
             when excluded.map_name is not null and robot_maps.map_name is distinct from excluded.map_name then excluded.trail
             when jsonb_array_length(robot_maps.trail) > 0
               and abs((robot_maps.trail->-1->>'x')::float8 - ($9::jsonb->>'x')::float8) < 0.05
               and abs((robot_maps.trail->-1->>'y')::float8 - ($9::jsonb->>'y')::float8) < 0.05
               then robot_maps.trail
             else (
               select coalesce(jsonb_agg(e order by ord), '[]'::jsonb) from (
                 select e, ord from jsonb_array_elements(robot_maps.trail || excluded.trail) with ordinality as t(e, ord)
                 order by ord desc limit $11
               ) s
             ) end,
           updated_at = excluded.updated_at
         returning *`,
        [
          f.robot,
          f.mapName ?? null,
          JSON.stringify(f.pose),
          f.places ? JSON.stringify(f.places) : null,
          JSON.stringify(f.scan),
          f.follower ? JSON.stringify(f.follower) : null,
          f.target ? JSON.stringify(f.target) : null,
          f.remainingMeters ?? null,
          pose,
          f.at,
          trailMax,
          f.maps ? JSON.stringify(f.maps) : null,
        ],
      );
      return toMap(rows[0]);
    },
    async getMap(robot) {
      const rows = await sql.query(`select * from robot_maps where robot = $1`, [robot]);
      return rows[0] ? toMap(rows[0]) : null;
    },

    async addFeedItems(robot, items, maxPerRobot, since) {
      if (items.length) {
        await sql.query(
          `insert into feed_items (id, robot, journey_id, at, who, kind, text)
           select x.id, $1, x.journey_id, x.at, x.who, x.kind, x.text
           from json_to_recordset($2::json) as x(id text, journey_id text, at timestamptz, who text, kind text, text text)
           on conflict (id) do nothing`,
          [
            robot,
            JSON.stringify(
              items.map((i) => ({ id: i.id, journey_id: i.journeyId ?? null, at: i.at, who: i.who, kind: i.kind, text: i.text })),
            ),
          ],
        );
      }
      await sql.query(
        `delete from feed_items where robot = $1 and (
           at < $2::timestamptz
           or id in (select id from feed_items where robot = $1 order by at desc, id desc offset $3)
         )`,
        [robot, since, maxPerRobot],
      );
    },
    async listFeedItems(robot, limit, since) {
      const rows = await sql.query(
        `select * from feed_items where robot = $1 and at >= $2::timestamptz order by at desc, id desc limit $3`,
        [robot, since, limit],
      );
      return rows.map(toFeed);
    },
    async deleteFeedForJourney(journeyId) {
      await sql.query(`delete from feed_items where journey_id = $1`, [journeyId]);
    },

    async countHits(key, since) {
      const rows = await sql.query(`select count(*)::int as n from rate_hits where key = $1 and at >= $2::timestamptz`, [key, since]);
      return Number(rows[0]?.n ?? 0);
    },
    async addHit(key, at) {
      await sql.query(`insert into rate_hits (key, at) values ($1, $2::timestamptz)`, [key, at]);
    },

    async purge(c: PurgeCutoffs) {
      const rows = await sql.query(
        `with j as (
           delete from journeys
           where (ended_at is not null and ended_at < $1::timestamptz) or updated_at < $2::timestamptz
           returning id
         ), f as (
           delete from feed_items where at < $1::timestamptz or journey_id in (select id from j)
           returning id
         ), lg as (
           delete from robot_logs where received_at < $4::timestamptz returning id
         ), m as (
           delete from robot_maps where updated_at < $4::timestamptz returning robot
         ), rh as (
           delete from rate_hits where at < $3::timestamptz returning key
         ), c as (
           delete from robot_commands
           where journey_id is null and coalesce(done_at, created_at) < $1::timestamptz
           returning id
         ), h as (
           delete from help_requests
           where (handled_at is not null and handled_at < $1::timestamptz) or created_at < $2::timestamptz
           returning id
         ), l as (
           delete from login_failures where at < $3::timestamptz returning key
         )
         select id from j`,
        [c.ended, c.stale, c.logins, c.logs],
      );
      return rows.map((r) => String(r.id));
    },
  };
}
