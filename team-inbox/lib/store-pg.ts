// postgres store. only type imports here so the sql can be tested outside next
import type { DoctorPatch, RobotRecord, Store, VisitPatch, VisitQuery } from "./store-types";
import type {
  CallCommand,
  Doctor,
  HelpRequest,
  HelpStatus,
  RobotCommand,
  RobotHeartbeat,
  Visit,
  VisitEvent,
  VisitStatus,
} from "./types";

type Row = Record<string, unknown>;
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

function toDoctor(r: Row): Doctor {
  const d: Doctor = {
    id: String(r.id),
    name: String(r.name),
    spot: String(r.spot),
    active: Boolean(r.active),
    avgConsultMinutes: Number(r.avg_consult_minutes),
    createdAt: iso(r.created_at),
  };
  if (r.teams_webhook_url) d.teamsWebhookUrl = String(r.teams_webhook_url);
  return d;
}

function toEvents(value: unknown): VisitEvent[] {
  const list = (typeof value === "string" ? JSON.parse(value) : value) as Row[] | null;
  return (list ?? []).map((e) => {
    const event: VisitEvent = { at: iso(e.at), type: String(e.type) };
    if (e.detail !== null && e.detail !== undefined) event.detail = String(e.detail);
    return event;
  });
}

function toVisit(r: Row): Visit {
  const v: Visit = {
    id: String(r.id),
    patientName: String(r.patient_name),
    doctorId: r.doctor_id === null ? null : String(r.doctor_id),
    doctorName: String(r.doctor_name),
    robot: String(r.robot),
    status: String(r.status) as VisitStatus,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    events: toEvents(r.events),
  };
  if (r.appointment_time) v.appointmentTime = String(r.appointment_time);
  if (r.language) v.language = String(r.language);
  return v;
}

// fields a command type does not use stay absent
function toCommand(r: Row): RobotCommand {
  const id = String(r.id);
  const robot = String(r.robot);
  const createdAt = iso(r.created_at);
  let c: RobotCommand;
  if (r.type === "say") c = { id, robot, type: "say", text: String(r.text), createdAt };
  else if (r.type === "go_to") c = { id, robot, type: "go_to", spot: String(r.spot), createdAt };
  else {
    c = {
      id,
      robot,
      type: "call_patient",
      visitId: String(r.visit_id),
      patientName: String(r.patient_name),
      doctorName: String(r.doctor_name),
      spot: String(r.spot),
      createdAt,
    };
  }
  const delivered = isoOrUndefined(r.delivered_at);
  const done = isoOrUndefined(r.done_at);
  if (delivered) c.deliveredAt = delivered;
  if (done) c.doneAt = done;
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
    at: iso(r.updated_at),
  };
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
  const handled = isoOrUndefined(r.handled_at);
  if (handled) h.handledAt = handled;
  return h;
}

const VISIT_SELECT = `
  select v.id, v.patient_name, v.doctor_id, v.doctor_name, v.appointment_time, v.language, v.robot,
         v.status, v.created_at, v.updated_at,
         coalesce((
           select json_agg(json_build_object('at', e.at, 'type', e.type, 'detail', e.detail) order by e.at, e.id)
           from visit_events e where e.visit_id = v.id
         ), '[]'::json) as events
  from visits v`;

export function createPgStore(sql: SqlClient): Store {
  async function getVisit(id: string) {
    const rows = await sql.query(`${VISIT_SELECT} where v.id = $1`, [id]);
    return rows[0] ? toVisit(rows[0]) : null;
  }

  return {
    kind: "postgres",

    async listDoctors() {
      const rows = await sql.query(`select * from doctors order by lower(name), created_at`);
      return rows.map(toDoctor);
    },
    async getDoctor(id) {
      const rows = await sql.query(`select * from doctors where id = $1`, [id]);
      return rows[0] ? toDoctor(rows[0]) : null;
    },
    async createDoctor(d) {
      const rows = await sql.query(
        `insert into doctors (id, name, spot, teams_webhook_url, active, avg_consult_minutes, created_at)
         values ($1, $2, $3, $4, $5, $6, $7::timestamptz) returning *`,
        [d.id, d.name, d.spot, d.teamsWebhookUrl ?? null, d.active, d.avgConsultMinutes, d.createdAt],
      );
      return toDoctor(rows[0]);
    },
    async updateDoctor(id, patch: DoctorPatch) {
      const rows = await sql.query(
        `update doctors set
           name = coalesce($2::text, name),
           spot = coalesce($3::text, spot),
           active = coalesce($4::boolean, active),
           teams_webhook_url = case when $5::boolean then $6::text else teams_webhook_url end,
           avg_consult_minutes = coalesce($7::integer, avg_consult_minutes)
         where id = $1 returning *`,
        [
          id,
          patch.name ?? null,
          patch.spot ?? null,
          patch.active ?? null,
          patch.teamsWebhookUrl !== undefined,
          patch.teamsWebhookUrl || null,
          patch.avgConsultMinutes ?? null,
        ],
      );
      return rows[0] ? toDoctor(rows[0]) : null;
    },

    async createVisit(v, event) {
      await sql.query(
        `with v as (
           insert into visits (id, patient_name, doctor_id, doctor_name, appointment_time, language, robot, status, created_at, updated_at)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz, $10::timestamptz)
           returning id
         )
         insert into visit_events (visit_id, at, type, detail)
         select id, $11::timestamptz, $12::text, $13::text from v`,
        [
          v.id,
          v.patientName,
          v.doctorId,
          v.doctorName,
          v.appointmentTime ?? null,
          v.language ?? null,
          v.robot,
          v.status,
          v.createdAt,
          v.updatedAt,
          event.at,
          event.type,
          event.detail ?? null,
        ],
      );
      return (await getVisit(v.id))!;
    },
    getVisit,
    async listVisits(q: VisitQuery) {
      const rows = await sql.query(
        `${VISIT_SELECT}
         where ($1::boolean is not true or v.status not in ('done', 'cancelled'))
           and ($2::timestamptz is null or v.created_at >= $2::timestamptz or v.status not in ('done', 'cancelled'))
         order by v.created_at desc
         limit 500`,
        [q.active ?? false, q.since ?? null],
      );
      return rows.map(toVisit);
    },
    async updateVisit(id, patch: VisitPatch, event) {
      const rows = await sql.query(
        `with u as (
           update visits set
             status = coalesce($2::text, status),
             doctor_id = case when $3::boolean then $4::text else doctor_id end,
             doctor_name = coalesce($5::text, doctor_name),
             updated_at = $6::timestamptz
           where id = $1 and ($7::text[] is null or status = any($7::text[]))
           returning id
         )
         insert into visit_events (visit_id, at, type, detail)
         select id, $6::timestamptz, $8::text, $9::text from u
         returning visit_id`,
        [
          id,
          patch.status ?? null,
          patch.doctorId !== undefined,
          patch.doctorId ?? null,
          patch.doctorName ?? null,
          event.at,
          patch.onlyIf ?? null,
          event.type,
          event.detail ?? null,
        ],
      );
      return rows[0] ? getVisit(id) : null;
    },
    async addEvent(id, event) {
      const rows = await sql.query(
        `with u as (update visits set updated_at = $2::timestamptz where id = $1 returning id)
         insert into visit_events (visit_id, at, type, detail)
         select id, $2::timestamptz, $3::text, $4::text from u
         returning visit_id`,
        [id, event.at, event.type, event.detail ?? null],
      );
      return rows[0] ? getVisit(id) : null;
    },
    async listQueue() {
      const rows = await sql.query(
        `select id, doctor_id, status, created_at from visits
         where status in ('checked_in', 'notified', 'escorting', 'with_doctor')`,
      );
      return rows.map((r) => ({
        id: String(r.id),
        doctorId: r.doctor_id === null ? null : String(r.doctor_id),
        status: String(r.status) as VisitStatus,
        createdAt: iso(r.created_at),
      }));
    },

    async createCommand(command) {
      const c: RobotCommand = command;
      const call = c.type === "call_patient" ? c : null;
      const rows = await sql.query(
        `insert into robot_commands (id, robot, type, visit_id, patient_name, doctor_name, spot, text, created_at)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz) returning *`,
        [
          c.id,
          c.robot,
          c.type,
          call?.visitId ?? null,
          call?.patientName ?? null,
          call?.doctorName ?? null,
          c.type === "say" ? null : c.spot,
          c.type === "say" ? c.text : null,
          c.createdAt,
        ],
      );
      return toCommand(rows[0]) as typeof command;
    },
    async openCommandForVisit(visitId) {
      const rows = await sql.query(
        `select * from robot_commands where visit_id = $1 and done_at is null order by created_at desc limit 1`,
        [visitId],
      );
      const c = rows[0] ? toCommand(rows[0]) : null;
      return c?.type === "call_patient" ? (c as CallCommand) : null;
    },
    async takeNextCommand(robot, now) {
      const rows = await sql.query(
        `update robot_commands set delivered_at = $2::timestamptz
         where id = (
           select id from robot_commands
           where robot = $1 and delivered_at is null and done_at is null
           order by created_at, id
           limit 1
           for update skip locked
         )
         returning *`,
        [robot, now],
      );
      return rows[0] ? toCommand(rows[0]) : null;
    },
    async finishCommand(id, now) {
      const rows = await sql.query(
        `update robot_commands set done_at = coalesce(done_at, $2::timestamptz) where id = $1 returning *`,
        [id, now],
      );
      return rows[0] ? toCommand(rows[0]) : null;
    },
    async dropUndeliveredCommands(visitId, now) {
      await sql.query(
        `update robot_commands set done_at = $2::timestamptz
         where visit_id = $1 and delivered_at is null and done_at is null`,
        [visitId, now],
      );
    },
    async listCommands(robot, limit) {
      const rows = await sql.query(
        `select * from robot_commands where robot = $1 order by created_at desc, id desc limit $2`,
        [robot, limit],
      );
      return rows.map(toCommand);
    },

    async touchRobot(name, now) {
      await sql.query(
        `insert into robots (name, last_seen_at) values ($1, $2::timestamptz)
         on conflict (name) do update set last_seen_at = excluded.last_seen_at`,
        [name, now],
      );
    },
    async listRobots() {
      const rows = await sql.query(`select name, last_seen_at from robots order by last_seen_at desc`);
      return rows.map((r) => ({ name: String(r.name), lastSeenAt: iso(r.last_seen_at) }));
    },
    async saveHeartbeat(h) {
      await sql.query(
        `with s as (
           insert into robot_status (robot, battery, charging, state, position, goal, detail, updated_at)
           values ($1, $2, $3, $4, $5, $6, $7, $8::timestamptz)
           on conflict (robot) do update set
             battery = excluded.battery, charging = excluded.charging, state = excluded.state,
             position = excluded.position, goal = excluded.goal, detail = excluded.detail,
             updated_at = excluded.updated_at
           returning robot
         )
         insert into robots (name, last_seen_at) select robot, $8::timestamptz from s
         on conflict (name) do update set last_seen_at = excluded.last_seen_at`,
        [h.robot, h.battery, h.charging, h.state, h.position, h.goal, h.detail, h.at],
      );
    },
    async getRobot(name): Promise<RobotRecord> {
      const rows = await sql.query(
        `select r.last_seen_at, s.robot as status_robot, s.battery, s.charging, s.state, s.position, s.goal, s.detail, s.updated_at
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
        `insert into help_requests (id, robot, summary, urgency, status, notified, created_at)
         values ($1, $2, $3, $4, $5, $6, $7::timestamptz) returning *`,
        [h.id, h.robot, h.summary, h.urgency, h.status, h.notified, h.createdAt],
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
    async updateHelp(id, status: HelpStatus, now) {
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
  };
}
