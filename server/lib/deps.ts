import type { Sql } from "postgres";
import { createSql } from "@/db/sql";
import { ConsoleMailer, type Mailer } from "@/lib/mail";

export interface Deps {
  sql: Sql;
  mailer: Mailer;
  now: () => Date;
  secret: Uint8Array;
}

const MIN_SECRET_BYTES = 32;

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

let cached: Deps | undefined;

export function defaultDeps(): Deps {
  if (cached) return cached;
  const databaseUrl = requireEnv("DATABASE_URL");
  const secret = new TextEncoder().encode(requireEnv("AUTH_SECRET"));
  if (secret.length < MIN_SECRET_BYTES) {
    throw new Error(`AUTH_SECRET must be at least ${MIN_SECRET_BYTES} bytes`);
  }
  cached = {
    sql: createSql(databaseUrl),
    mailer: new ConsoleMailer(),
    now: () => new Date(),
    secret,
  };
  return cached;
}
