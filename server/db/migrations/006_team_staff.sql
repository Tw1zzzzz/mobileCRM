-- A team now has two codes: the player code (invite_code) and a staff code that lets more staff join.
alter table teams add column staff_code text;

-- Existing teams get a random code from the same alphabet and length as invite_code, unique across both columns.
do $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  team record;
  candidate text;
begin
  for team in select id from teams where staff_code is null loop
    loop
      candidate := (
        select string_agg(substr(alphabet, 1 + floor(random() * 32)::int, 1), '')
        from generate_series(1, 6)
      );
      exit when not exists (
        select 1 from teams where invite_code = candidate or staff_code = candidate
      );
    end loop;
    update teams set staff_code = candidate where id = team.id;
  end loop;
end $$;

alter table teams alter column staff_code set not null;
alter table teams add constraint teams_staff_code_key unique (staff_code);
-- Uniqueness across the two columns is kept by code generation; this stops a team's own two codes from matching.
alter table teams add constraint teams_codes_differ check (staff_code <> invite_code);
