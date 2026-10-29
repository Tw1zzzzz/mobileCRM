import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { Sql } from "postgres";
import { createSql } from "./sql";

const here = path.dirname(fileURLToPath(import.meta.url));
const defaultDir = path.join(here, "migrations");

/** Applies pending `*.sql` files in name order, each in its own transaction. Returns applied names. */
export async function migrate(sql: Sql, dir: string = defaultDir): Promise<string[]> {
  await sql`
    create table if not exists schema_migrations (
      name text primary key,
      applied_at timestamptz not null default now()
    )`;
  const done = new Set((await sql<{ name: string }[]>`select name from schema_migrations`).map((r) => r.name));
  const files = (await readdir(dir)).filter((f) => f.endsWith(".sql")).sort();
  const applied: string[] = [];
  for (const file of files) {
    if (done.has(file)) continue;
    const text = await readFile(path.join(dir, file), "utf8");
    await sql.begin(async (tx) => {
      await tx.unsafe(text);
      await tx`insert into schema_migrations (name) values (${file})`;
    });
    applied.push(file);
  }
  return applied;
}

/**
 * `npm run build` migrates only a production deploy or a local build (no `VERCEL_ENV`). Preview deploys share the
 * production `DATABASE_URL`, so a branch's migration must never reach that database before the branch is released.
 */
export function shouldMigrate(env: Record<string, string | undefined>): boolean {
  const target = env.VERCEL_ENV;
  return !target || target === "production";
}

async function main() {
  if (!shouldMigrate(process.env)) {
    console.log(`migrations skipped: VERCEL_ENV=${process.env.VERCEL_ENV} (only production builds migrate)`);
    return;
  }
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("Missing required environment variable DATABASE_URL");
  const sql = createSql(url);
  try {
    const applied = await migrate(sql);
    console.log(applied.length ? `applied: ${applied.join(", ")}` : "migrations up to date");
  } finally {
    await sql.end();
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(e instanceof Error ? e.message : e);
    process.exit(1);
  });
}
