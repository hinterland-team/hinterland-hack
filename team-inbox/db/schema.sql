-- nova clinic schema. safe to run again (npm run db:setup)

create table if not exists doctors (
  id text primary key,
  name text not null,
  spot text not null,
  teams_webhook_url text,
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists visits (
  id text primary key,
  patient_name text not null,
  doctor_id text references doctors(id) on delete set null,
  doctor_name text not null,
  appointment_time text,
  language text,
  robot text not null,
  status text not null check (status in ('checked_in', 'notified', 'called', 'escorting', 'with_doctor', 'done', 'cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists visits_created_at_idx on visits (created_at desc);
create index if not exists visits_status_idx on visits (status);

create table if not exists visit_events (
  id bigserial primary key,
  visit_id text not null references visits(id) on delete cascade,
  at timestamptz not null default now(),
  type text not null,
  detail text
);

create index if not exists visit_events_visit_idx on visit_events (visit_id, at);

create table if not exists robot_commands (
  id text primary key,
  robot text not null,
  type text not null,
  visit_id text not null references visits(id) on delete cascade,
  patient_name text not null,
  doctor_name text not null,
  spot text not null,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  done_at timestamptz
);

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

create table if not exists robots (
  name text primary key,
  last_seen_at timestamptz not null
);

-- v2: waiting time estimate, robot heartbeat, say and go_to commands. works on old and new databases

alter table doctors add column if not exists avg_consult_minutes integer not null default 10
  check (avg_consult_minutes between 1 and 120);

-- say and go_to commands have no visit
alter table robot_commands alter column visit_id drop not null;
alter table robot_commands alter column patient_name drop not null;
alter table robot_commands alter column doctor_name drop not null;
alter table robot_commands alter column spot drop not null;
alter table robot_commands add column if not exists text text;

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
