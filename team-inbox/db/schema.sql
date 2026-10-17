-- nova clinic v3 schema. safe to run again (npm run db:setup).
-- scripts/schema.mjs runs the one-time cleanup of the v1/v2 tables first

create table if not exists places (
  id text primary key,
  name text not null,
  room text,
  spot text not null,
  kind text not null check (kind in ('room', 'waiting', 'reception', 'exit', 'other')),
  public boolean not null default false,
  avg_minutes integer not null default 10 check (avg_minutes between 1 and 120),
  teams_webhook_url text,
  active boolean not null default true,
  waiting_place_id text,
  self_check_in boolean not null default false,
  created_at timestamptz not null default now()
);

-- v3.1 on a v3 database
alter table places add column if not exists waiting_place_id text;
alter table places add column if not exists self_check_in boolean not null default false;
alter table places drop constraint if exists places_kind_check;
alter table places add constraint places_kind_check check (kind in ('room', 'waiting', 'reception', 'exit', 'other'));

-- a patient is only a ticket. no names, no notes
create table if not exists journeys (
  id text primary key,
  ticket text not null,
  ticket_day text not null,
  robot text not null,
  pace text not null check (pace in ('normal', 'slow', 'very_slow')),
  wheelchair boolean not null default false,
  status text not null check (status in ('waiting_pickup', 'walking', 'arrived', 'with_staff', 'done', 'cancelled')),
  for_place_id text,
  created_by text not null check (created_by in ('staff', 'robot')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  ended_at timestamptz,
  version integer not null default 1
);

alter table journeys add column if not exists for_place_id text;

create unique index if not exists journeys_ticket_day_idx on journeys (ticket_day, ticket);
create index if not exists journeys_open_idx on journeys (created_at desc) where ended_at is null;
create index if not exists journeys_ended_idx on journeys (ended_at) where ended_at is not null;

create table if not exists journey_legs (
  id text primary key,
  journey_id text not null references journeys(id) on delete cascade,
  seq integer not null,
  from_place_id text,
  from_name text,
  from_spot text,
  to_place_id text not null,
  to_name text not null,
  to_spot text not null,
  to_room text,
  status text not null check (status in ('queued', 'pickup', 'walking', 'arrived', 'failed')),
  created_at timestamptz not null,
  started_at timestamptz,
  arrived_at timestamptz
);

create unique index if not exists journey_legs_seq_idx on journey_legs (journey_id, seq);

create table if not exists journey_events (
  id bigserial primary key,
  journey_id text not null references journeys(id) on delete cascade,
  at timestamptz not null,
  type text not null,
  detail text
);

create index if not exists journey_events_journey_idx on journey_events (journey_id, at);

create table if not exists robot_commands (
  id text primary key,
  robot text not null,
  type text not null check (type in ('guide', 'say', 'ask', 'go_to')),
  journey_id text references journeys(id) on delete cascade,
  leg_id text,
  ticket text,
  pace text,
  wheelchair boolean,
  from_name text,
  from_spot text,
  to_name text,
  to_spot text,
  to_room text,
  spot text,
  text text,
  created_at timestamptz not null,
  delivered_at timestamptz,
  done_at timestamptz
);

-- v3.1: ask commands on a v3 database
alter table robot_commands drop constraint if exists robot_commands_type_check;
alter table robot_commands add constraint robot_commands_type_check check (type in ('guide', 'say', 'ask', 'go_to'));

create index if not exists robot_commands_open_idx on robot_commands (robot, created_at) where delivered_at is null and done_at is null;

create table if not exists help_requests (
  id text primary key,
  robot text not null,
  summary text not null,
  urgency text not null check (urgency in ('normal', 'urgent')),
  status text not null default 'open' check (status in ('open', 'done')),
  notified boolean not null default false,
  created_at timestamptz not null default now(),
  handled_at timestamptz
);

alter table help_requests add column if not exists journey_id text;
alter table help_requests add column if not exists ticket text;

create table if not exists robots (
  name text primary key,
  last_seen_at timestamptz not null
);

-- latest heartbeat per robot
create table if not exists robot_status (
  robot text primary key,
  battery integer,
  charging boolean not null default false,
  state text,
  position text,
  goal text,
  detail text,
  updated_at timestamptz not null
);

alter table robot_status add column if not exists screen jsonb;

-- anonymous counters per day and place ('' = not tied to a place)
create table if not exists daily_stats (
  day text not null,
  place_id text not null default '',
  journeys integer not null default 0,
  guides integer not null default 0,
  done integer not null default 0,
  cancelled integer not null default 0,
  walks integer not null default 0,
  walk_seconds bigint not null default 0,
  lost_patients integer not null default 0,
  feedback_up integer not null default 0,
  feedback_down integer not null default 0,
  primary key (day, place_id)
);

alter table daily_stats add column if not exists feedback_up integer not null default 0;
alter table daily_stats add column if not exists feedback_down integer not null default 0;

create table if not exists settings (
  key text primary key,
  value text not null
);

-- wrong staff passwords per hashed address, for the rate limit. kept 1 h
create table if not exists login_failures (
  key text not null,
  at timestamptz not null
);

create index if not exists login_failures_idx on login_failures (key, at);
