import postgres, { type Sql } from "postgres";

/** Plain postgres.js client; works against any Postgres (Neon, Supabase, local). */
export function createSql(url: string): Sql {
  return postgres(url, { max: 5, idle_timeout: 20, onnotice: () => {} });
}
