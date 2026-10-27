import type { Sql } from "postgres";
import { createSql } from "@/db/sql";
import { ConsoleMailer, ResendMailer, SmtpMailer, type Mailer } from "@/lib/mail";

export interface Deps {
  sql: Sql;
  mailer: Mailer;
  now: () => Date;
  secret: Uint8Array;
}

const MIN_SECRET_BYTES = 32;

type Env = Record<string, string | undefined>;

function requireEnv(name: string, env: Env = process.env): string {
  const value = env[name];
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

const DEFAULT_SMTP_HOST = "smtp.gmail.com";
const DEFAULT_SMTP_PORT = 465;

/** SMTP wins when `SMTP_PASSWORD` is set, then Resend; production needs one of them. */
export function createMailer(env: Env = process.env): Mailer {
  if (env.SMTP_PASSWORD) {
    const user = requireEnv("SMTP_USER", env);
    const port = env.SMTP_PORT ? Number(env.SMTP_PORT) : DEFAULT_SMTP_PORT;
    if (!Number.isInteger(port) || port <= 0) throw new Error("SMTP_PORT must be a port number");
    return new SmtpMailer({
      host: env.SMTP_HOST || DEFAULT_SMTP_HOST,
      port,
      secure: port === 465,
      user,
      password: env.SMTP_PASSWORD,
      from: env.MAIL_FROM || `Pulse <${user}>`,
    });
  }
  if (env.RESEND_API_KEY) return new ResendMailer(env.RESEND_API_KEY, requireEnv("MAIL_FROM", env));
  if (env.VERCEL_ENV === "production") {
    throw new Error("SMTP_PASSWORD or RESEND_API_KEY is required in production");
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
