-- KAST % and utility damage per round of a hand-entered player line (both optional, like the board's columns).
alter table team_game_rows
  add column kast numeric(4, 1) check (kast between 0 and 100),
  add column udr numeric(4, 1) check (udr between 0 and 500);
