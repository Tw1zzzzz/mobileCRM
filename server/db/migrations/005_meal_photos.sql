create table meal_photos (
  user_id uuid not null references users (id) on delete cascade,
  meal_id uuid not null,
  jpeg bytea not null,
  updated_at timestamptz not null,
  primary key (user_id, meal_id)
);
