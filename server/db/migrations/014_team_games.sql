-- Games the staff enter by hand (pracc or tournament): a game has 1-5 maps, a map has up to 10 player lines.
-- Limits mirror the form validation in lib/staff/games.ts; the checks are the last line of defence.
create table team_games (
  id uuid primary key default gen_random_uuid(),
  team_id uuid not null references teams (id) on delete cascade,
  kind text not null check (kind in ('pracc', 'tournament')),
  played_on date not null,
  opponent text check (opponent is null or char_length(opponent) between 1 and 60),
  created_by uuid references users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index team_games_team_played_idx on team_games (team_id, played_on desc);

create table team_game_maps (
  id uuid primary key default gen_random_uuid(),
  game_id uuid not null references team_games (id) on delete cascade,
  position int not null check (position between 0 and 4),
  map text not null check (map in ('mirage', 'inferno', 'nuke', 'ancient', 'anubis', 'dust2', 'train', 'overpass', 'vertigo')),
  our_score int not null check (our_score between 0 and 30),
  their_score int not null check (their_score between 0 and 30),
  pistol1 text not null check (pistol1 in ('won', 'lost')),
  pistol2 text not null check (pistol2 in ('won', 'lost')),
  unique (game_id, position)
);

-- A player line keeps the nickname it was entered with: it survives the player leaving the team or the app.
create table team_game_rows (
  id uuid primary key default gen_random_uuid(),
  map_id uuid not null references team_game_maps (id) on delete cascade,
  position int not null check (position between 0 and 9),
  player_id uuid references users (id) on delete set null,
  nickname text not null check (char_length(nickname) between 1 and 40),
  kills int not null check (kills between 0 and 99),
  deaths int not null check (deaths between 0 and 99),
  assists int not null check (assists between 0 and 99),
  adr numeric(4, 1) not null check (adr between 0 and 300),
  fk int check (fk between 0 and 30),
  fd int check (fd between 0 and 30),
  multikills numeric(3, 1) check (multikills between 0 and 10),
  clutches int check (clutches between 0 and 20),
  flash_time numeric(3, 1) check (flash_time between 0 and 60),
  unique (map_id, position)
);

create index team_game_rows_player_idx on team_game_rows (player_id) where player_id is not null;
