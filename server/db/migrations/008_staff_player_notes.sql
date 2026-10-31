-- Staff notes and images on a player's card. Only the staff website reads them (the player API never does).
-- They go with the player: account deletion and dismantling the team cascade; leaving the team is handled by
-- removeMember, which deletes the rows in its own transaction.
create table staff_player_notes (
  player_id uuid primary key references users (id) on delete cascade,
  team_id uuid not null references teams (id) on delete cascade,
  communication text not null,
  updated_at timestamptz not null,
  updated_by uuid
);

create table staff_player_images (
  player_id uuid not null references users (id) on delete cascade,
  kind text not null check (kind in ('scheme', 'roadmap', 'mindmap')),
  team_id uuid not null references teams (id) on delete cascade,
  content_type text not null,
  bytes bytea not null,
  updated_at timestamptz not null,
  updated_by uuid,
  primary key (player_id, kind)
);
