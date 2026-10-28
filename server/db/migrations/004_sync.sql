create table sync_documents (
  user_id uuid not null references users (id) on delete cascade,
  collection text not null,
  version int not null,
  document jsonb not null,
  updated_at timestamptz not null,
  primary key (user_id, collection)
);
