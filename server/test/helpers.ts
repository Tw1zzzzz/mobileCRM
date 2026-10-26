import type { Deps } from "../lib/deps";
import { createSql } from "../db/sql";
import type { MailMessage, Mailer } from "../lib/mail";

/** Test-only mailer that records what would have been sent. */
export class MemoryMailer implements Mailer {
  readonly sent: MailMessage[] = [];
  async send(m: MailMessage): Promise<void> {
    this.sent.push({ to: m.to, subject: m.subject, text: m.text });
  }

  /** The 6-digit code from the latest message to `to`, if any. */
  lastCode(to: string): string | undefined {
    const last = [...this.sent].reverse().find((m) => m.to === to);
    return last?.text.match(/\b(\d{6})\b/)?.[1];
  }
}

export const TEST_START = new Date("2026-10-26T12:00:00Z");
const testUrl = process.env.TEST_DATABASE_URL ?? "postgres://localhost/pulse_test";

export interface TestDeps extends Deps {
  mailer: MemoryMailer;
  /** Moves the fake clock forward. */
  advance: (ms: number) => void;
}

export function testDeps(): TestDeps {
  let current = TEST_START.getTime();
  return {
    sql: createSql(testUrl),
    mailer: new MemoryMailer(),
    now: () => new Date(current),
    secret: new TextEncoder().encode("test-secret-test-secret-test-secret-0123456789"),
    advance: (ms) => {
      current += ms;
    },
  };
}

/** Truncates every table except schema_migrations. */
export async function resetDb(sql: Deps["sql"]): Promise<void> {
  const tables = await sql<{ tablename: string }[]>`
    select tablename from pg_tables
    where schemaname = 'public' and tablename <> 'schema_migrations'`;
  if (tables.length === 0) return;
  const list = tables.map((t) => `"${t.tablename}"`).join(", ");
  await sql.unsafe(`truncate ${list} restart identity cascade`);
}
