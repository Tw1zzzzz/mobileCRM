-- The app's «Заметка» entries are shared with the team like any other entry, so `note` is a valid type.
alter table staff_lineups drop constraint staff_lineups_type_check;
alter table staff_lineups add constraint staff_lineups_type_check
  check (type in ('smoke', 'flash', 'molotov', 'grenade', 'script', 'note'));
