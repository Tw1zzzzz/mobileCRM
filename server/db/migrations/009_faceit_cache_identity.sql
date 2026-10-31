-- The Faceit cache row now remembers WHO it was fetched for and WHEN Faceit last failed.
--   faceit_ref: the player id the matches belong to, or 'nick:<nickname>' when resolved by nickname. A row with a
--     different ref than the player's current Faceit identity is a miss and is never served as stale.
--   failed_at / failure: the last failed fetch and its status, remembered for a couple of minutes so an outage
--     does not make every page load wait for Faceit again.
-- A row may now hold only a failure (no matches yet), so matches and fetched_at become nullable.
alter table faceit_cache
  alter column matches drop not null,
  alter column fetched_at drop not null,
  add column faceit_ref text,
  add column failed_at timestamptz,
  add column failure text;
