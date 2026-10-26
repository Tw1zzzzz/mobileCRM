import postgres from "postgres";
import { migrate } from "../db/migrate";

const url = process.env.TEST_DATABASE_URL ?? "postgres://localhost/pulse_test";

/** vitest globalSetup: create the test database if missing and migrate it. */
export default async function setup() {
  const target = new URL(url);
  const dbName = decodeURIComponent(target.pathname.slice(1));
  if (!/^[a-z0-9_]+$/i.test(dbName)) throw new Error(`Unsafe test database name: ${dbName}`);

  const admin = new URL(url);
  admin.pathname = "/postgres";
  const adminSql = postgres(admin.toString(), { max: 1, onnotice: () => {} });
  try {
    const found = await adminSql`select 1 from pg_database where datname = ${dbName}`;
    if (found.length === 0) await adminSql.unsafe(`create database "${dbName}"`);
  } finally {
    await adminSql.end();
  }

  const sql = postgres(url, { max: 1, onnotice: () => {} });
  try {
    await migrate(sql);
  } finally {
    await sql.end();
  }
}
