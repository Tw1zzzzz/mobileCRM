import type { Sql } from "postgres";
import { createSql } from "@/db/sql";
import { ConsoleMailer, ResendMailer, type Mailer } from "@/lib/mail";

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

function createMailer(): Mailer {
  const apiKey = process.env.RESEND_API_KEY;
  if (apiKey) return new ResendMailer(apiKey, requireEnv("MAIL_FROM"));
  if (process.env.VERCEL_ENV === "production") {
    throw new Error("RESEND_API_KEY is required in production");
  }
  return new ConsoleMailer();
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
    mailer: createMailer(),
    now: () => new Date(),
    secret,
  };
  return cached;
}
