create table teams (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  invite_code text unique not null,
  owner_id uuid,
  created_at timestamptz not null default now()
);

create table users (
  id uuid primary key default gen_random_uuid(),
  email text unique not null,
  nickname text not null,
  password_hash text not null,
  role text not null check (role in ('soloPlayer', 'teamPlayer', 'staff')),
  tariff text not null default 'free',
  team_id uuid references teams (id) on delete set null,
  faceit_url text,
  email_verified_at timestamptz,
  accepted_terms_at timestamptz not null,
  accepted_privacy_at timestamptz not null,
  beta_terms_version text not null,
  beta_terms_accepted_at timestamptz not null,
  install_source text not null default 'beta',
  created_at timestamptz not null,
  updated_at timestamptz not null
);

alter table teams
  add constraint teams_owner_id_fkey foreign key (owner_id) references users (id) on delete cascade;

create table refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users (id) on delete cascade,
  token_hash text unique not null,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  replaced_by uuid,
  created_at timestamptz not null
);

create table email_codes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users (id) on delete cascade,
  purpose text not null check (purpose in ('verify', 'reset')),
  code_hash text not null,
  expires_at timestamptz not null,
  attempts int not null default 0,
  consumed_at timestamptz,
  created_at timestamptz not null
);

create table rate_limits (
  key text not null,
  window_start timestamptz not null,
  count int not null,
  primary key (key, window_start)
);
