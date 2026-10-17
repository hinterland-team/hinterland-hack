// applies db/schema.sql and the one-time move from v1/v2 (doctors, visits) to v3.
// used by db-setup.mjs. query(text, params) -> rows

export function statements(sql) {
  return sql
    .split(/;\s*(?:\r?\n|$)/)
    .map((s) => s.replace(/^\s*--.*$/gm, "").trim())
    .filter(Boolean);
}

async function exists(query, table) {
  const rows = await query(`select to_regclass($1) as t`, [`public.${table}`]);
  return rows[0]?.t !== null && rows[0]?.t !== undefined;
}

async function hasColumn(query, table, column) {
  const rows = await query(
    `select 1 from information_schema.columns where table_schema = 'public' and table_name = $1 and column_name = $2`,
    [table, column],
  );
  return rows.length > 0;
}

export async function setupDatabase(query, schemaSql, log = () => {}) {
  // v1/v2 kept patient names in visits and in call commands: those go, v3 has none
  const oldVisits = await exists(query, "visits");
  if ((await exists(query, "robot_commands")) && (await hasColumn(query, "robot_commands", "visit_id"))) {
    await query(`drop table robot_commands`);
    log("dropped the v2 robot_commands table (it held patient names)");
  }
  if (oldVisits) {
    await query(`drop table if exists visit_events`);
    await query(`drop table if exists visits cascade`);
    log("dropped the v2 visits tables (patient names)");
    if (await exists(query, "help_requests")) {
      await query(`delete from help_requests`);
      log("cleared the v2 help requests");
    }
  }

  const list = statements(schemaSql);
  for (const s of list) {
    await query(s);
    log(`ok: ${s.split("\n")[0]}`);
  }

  // doctors become rooms, once. the table is dropped afterwards so deleted places don't come back
  if (await exists(query, "doctors")) {
    const minutes = (await hasColumn(query, "doctors", "avg_consult_minutes")) ? "avg_consult_minutes" : "10";
    const rows = await query(
      `insert into places (id, name, spot, kind, public, avg_minutes, teams_webhook_url, active, self_check_in, created_at)
       select id, name, spot, 'room', false, ${minutes}, teams_webhook_url, active, false, created_at from doctors
       on conflict (id) do nothing
       returning id`,
    );
    await query(`drop table doctors cascade`);
    log(`moved ${rows.length} doctors to places (kind room) and dropped the doctors table`);
  }
  return list.length;
}
