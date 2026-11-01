create table staff_calendar_events (
  id uuid primary key default gen_random_uuid(),
  team_id uuid not null references teams (id) on delete cascade,
  created_by uuid references users (id) on delete set null,
  title text not null check (char_length(title) between 1 and 120),
  details text not null default '' check (char_length(details) <= 4000),
  tag text not null check (tag in ('personal', 'team', 'player')),
  player_id uuid references users (id) on delete cascade,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  links text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (ends_at > starts_at),
  check ((tag = 'player' and player_id is not null) or (tag <> 'player' and player_id is null))
);

create index staff_calendar_events_team_starts_idx on staff_calendar_events (team_id, starts_at);

-- Preserve existing shared events that were previously copied into each member's private calendar document.
with source_events as (
  select u.team_id, u.id as source_user_id, e as event,
    e->>'id' as event_id,
    case when e->'tag' ? 'team' then 'team' else 'player' end as tag,
    e->'tag'->'player'->>'_0' as player_nickname
  from sync_documents s
  join users u on u.id = s.user_id and u.team_id is not null
  cross join lateral jsonb_array_elements(
    case when jsonb_typeof(s.document) = 'array' then s.document else '[]'::jsonb end
  ) as entries(e)
  where s.collection = 'calendar'
    and (e->'tag' ? 'team' or e->'tag' ? 'player')
    and e->>'id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    and e->>'start' ~ '^\d{4}-\d{2}-\d{2}T'
    and e->>'end' ~ '^\d{4}-\d{2}-\d{2}T'
    and char_length(coalesce(e->>'title', '')) between 1 and 120
    and char_length(coalesce(e->>'details', '')) <= 4000
), resolved as (
  select distinct on (s.team_id, s.event_id)
    s.team_id, s.source_user_id, s.event, s.event_id, s.tag,
    case when s.tag = 'player' then p.id else null end as player_id,
    s.source_user_id as created_by,
    s.event->>'title' as title,
    coalesce(s.event->>'details', '') as details,
    (s.event->>'start')::timestamptz as starts_at,
    (s.event->>'end')::timestamptz as ends_at,
    case when jsonb_typeof(s.event->'links') = 'array'
      then array(select jsonb_array_elements_text(s.event->'links')) else '{}'::text[] end as links
  from source_events s
  left join users p on s.tag = 'player' and p.team_id = s.team_id and p.role = 'teamPlayer'
    and p.nickname = s.player_nickname and p.email_verified_at is not null
  order by s.team_id, s.event_id, s.source_user_id
)
insert into staff_calendar_events (id, team_id, created_by, title, details, tag, player_id, starts_at, ends_at, links)
select event_id::uuid, team_id, created_by, title, details, tag, player_id, starts_at, ends_at, links
from resolved
where starts_at < ends_at and (tag = 'team' or player_id is not null)
on conflict (id) do nothing;
