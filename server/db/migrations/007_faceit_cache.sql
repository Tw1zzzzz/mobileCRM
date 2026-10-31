-- The last Faceit match list fetched for a player, shared by every staff member who opens the card.
-- Replaced on each successful fetch; kept past its TTL so it can be served when Faceit fails.
create table faceit_cache (
  user_id uuid primary key references users (id) on delete cascade,
  matches jsonb not null,
  fetched_at timestamptz not null
);
