create table staff_lineups (
  id uuid primary key default gen_random_uuid(),
  team_id uuid not null references teams (id) on delete cascade,
  created_by uuid references users (id) on delete set null,
  title text not null check (char_length(title) between 1 and 120),
  map text not null check (map in ('mirage', 'inferno', 'nuke', 'ancient', 'anubis', 'dust2', 'train')),
  side text not null check (side in ('t', 'ct')),
  type text not null check (type in ('smoke', 'flash', 'molotov', 'grenade', 'script')),
  details text not null default '' check (char_length(details) <= 4000),
  variations jsonb not null default '[]'::jsonb check (jsonb_typeof(variations) = 'array'),
  video_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (video_url is null or char_length(video_url) <= 2048)
);

create index staff_lineups_team_recent_idx on staff_lineups (team_id, updated_at desc);

create table staff_lineup_favorites (
  lineup_id uuid not null references staff_lineups (id) on delete cascade,
  staff_user_id uuid not null references users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (lineup_id, staff_user_id)
);
