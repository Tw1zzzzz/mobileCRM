import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { hashPassword, verifyPassword } from "../lib/auth/passwords";
import {
  issueSession,
  requireUser,
  revokeAll,
  revokeRefresh,
  rotateRefresh,
} from "../lib/auth/tokens";
import {
  isStrongPassword,
  isValidEmail,
  isValidFaceitUrl,
  normalizeEmail,
} from "../lib/auth/validation";
import { ApiError } from "../lib/http";
import { clientIp, rateLimit } from "../lib/rateLimit";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const MINUTE = 60_000;

beforeEach(() => resetDb(deps.sql));
afterAll(() => deps.sql.end());

async function insertUser(email = "nova@team.gg"): Promise<string> {
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at,
      created_at, updated_at)
    values (${email}, 'Nova', 'x', 'soloPlayer', ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  return row.id;
}

function bearer(token: string): Request {
  return new Request("http://localhost/v1/me", { headers: { authorization: `Bearer ${token}` } });
}

async function expectApiError(promise: Promise<unknown>, code: string, status: number) {
  const error = await promise.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ code, status });
}

describe("passwords", () => {
  it("passwordRoundTrip", async () => {
    const hash = await hashPassword("abcdefg1");
    expect(hash).toMatch(/^\$argon2id\$/);
    expect(await verifyPassword(hash, "abcdefg1")).toBe(true);
    expect(await verifyPassword(hash, "abcdefg2")).toBe(false);
  });

  it("verifyPassword is false for a malformed hash", async () => {
    expect(await verifyPassword("not-a-hash", "abcdefg1")).toBe(false);
  });
});

describe("access token", () => {
  it("accessTokenExpiresAfter15Minutes", async () => {
    const userId = await insertUser();
    const { access_token } = await issueSession(deps, userId);
    deps.advance(14 * MINUTE);
    expect(await requireUser(deps, bearer(access_token))).toBe(userId);
    deps.advance(2 * MINUTE);
    await expectApiError(requireUser(deps, bearer(access_token)), "token_expired", 401);
  });

  it("rejects a missing, malformed or forged token as unauthorized", async () => {
    const userId = await insertUser();
    const { access_token } = await issueSession(deps, userId);
    const noHeader = new Request("http://localhost/v1/me");
    await expectApiError(requireUser(deps, noHeader), "unauthorized", 401);
    await expectApiError(requireUser(deps, bearer("garbage")), "unauthorized", 401);
    const forged = `${access_token.slice(0, -2)}xx`;
    await expectApiError(requireUser(deps, bearer(forged)), "unauthorized", 401);
    const basic = new Request("http://localhost/v1/me", { headers: { authorization: "Basic abc" } });
    await expectApiError(requireUser(deps, basic), "unauthorized", 401);
  });

  it("rejects a token signed with another secret", async () => {
    const userId = await insertUser();
    const other = { ...deps, secret: new TextEncoder().encode("another-secret-another-secret-0123456789") };
    const { access_token } = await issueSession(other, userId);
    await expectApiError(requireUser(deps, bearer(access_token)), "unauthorized", 401);
  });

  it("rejects a valid token whose user was deleted", async () => {
    const userId = await insertUser();
    const { access_token } = await issueSession(deps, userId);
    await deps.sql`delete from users where id = ${userId}`;
    await expectApiError(requireUser(deps, bearer(access_token)), "unauthorized", 401);
  });
});

describe("refresh tokens", () => {
  it("stores only a hash of the refresh token", async () => {
    const userId = await insertUser();
    const { refresh_token } = await issueSession(deps, userId);
    const rows = await deps.sql<{ token_hash: string }[]>`select token_hash from refresh_tokens`;
    expect(rows).toHaveLength(1);
    expect(rows[0].token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(rows[0].token_hash).not.toContain(refresh_token);
  });

  it("refreshRotates", async () => {
    const userId = await insertUser();
    const first = await issueSession(deps, userId);
    const rotated = await rotateRefresh(deps, first.refresh_token);
    expect(rotated.userId).toBe(userId);
    expect(rotated.tokens.refresh_token).not.toBe(first.refresh_token);
    expect(await requireUser(deps, bearer(rotated.tokens.access_token))).toBe(userId);
    // The new token keeps working.
    const again = await rotateRefresh(deps, rotated.tokens.refresh_token);
    expect(again.userId).toBe(userId);
  });

  it("reusedRefreshTokenRevokesAll", async () => {
    const userId = await insertUser();
    const a = await issueSession(deps, userId);
    const b = await rotateRefresh(deps, a.refresh_token);
    await expectApiError(rotateRefresh(deps, a.refresh_token), "unauthorized", 401);
    await expectApiError(rotateRefresh(deps, b.tokens.refresh_token), "unauthorized", 401);
  });

  it("rejects an unknown refresh token", async () => {
    await expectApiError(rotateRefresh(deps, "nope"), "unauthorized", 401);
  });

  it("rejects an expired refresh token", async () => {
    const userId = await insertUser();
    const { refresh_token } = await issueSession(deps, userId);
    deps.advance(31 * 24 * 60 * MINUTE);
    await expectApiError(rotateRefresh(deps, refresh_token), "unauthorized", 401);
  });

  it("revokeRefresh logs one session out without touching others", async () => {
    const userId = await insertUser();
    const phone = await issueSession(deps, userId);
    const tablet = await issueSession(deps, userId);
    await revokeRefresh(deps, phone.refresh_token);
    await expectApiError(rotateRefresh(deps, phone.refresh_token), "unauthorized", 401);
    // A logged-out token is not a replay: the other session survives.
    const next = await rotateRefresh(deps, tablet.refresh_token);
    expect(next.userId).toBe(userId);
  });

  it("revokeAll rejects every refresh token of the user", async () => {
    const userId = await insertUser();
    const otherId = await insertUser("other@team.gg");
    const one = await issueSession(deps, userId);
    const two = await issueSession(deps, userId);
    const other = await issueSession(deps, otherId);
    await revokeAll(deps, userId);
    await expectApiError(rotateRefresh(deps, one.refresh_token), "unauthorized", 401);
    await expectApiError(rotateRefresh(deps, two.refresh_token), "unauthorized", 401);
    expect((await rotateRefresh(deps, other.refresh_token)).userId).toBe(otherId);
  });

  it("two concurrent rotations of one token never both succeed", async () => {
    const userId = await insertUser();
    const { refresh_token } = await issueSession(deps, userId);
    const results = await Promise.allSettled([
      rotateRefresh(deps, refresh_token),
      rotateRefresh(deps, refresh_token),
    ]);
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    const rejected = results.find((r) => r.status === "rejected");
    expect((rejected as PromiseRejectedResult).reason).toMatchObject({ code: "unauthorized" });
  });
});

describe("rateLimit", () => {
  it("rateLimitBlocksEleventh", async () => {
    for (let i = 0; i < 10; i++) await rateLimit(deps, "ip:1.2.3.4", 10, MINUTE);
    await expectApiError(rateLimit(deps, "ip:1.2.3.4", 10, MINUTE), "rate_limited", 429);
    // Other keys are independent.
    await rateLimit(deps, "ip:5.6.7.8", 10, MINUTE);
    deps.advance(MINUTE);
    await rateLimit(deps, "ip:1.2.3.4", 10, MINUTE);
  });

  it("counts atomically under concurrency", async () => {
    const results = await Promise.allSettled(
      Array.from({ length: 15 }, () => rateLimit(deps, "ip:9.9.9.9", 10, MINUTE)),
    );
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(10);
    expect(results.filter((r) => r.status === "rejected")).toHaveLength(5);
  });

  it("clientIp prefers the first x-forwarded-for entry, then x-real-ip, then unknown", () => {
    const h = (headers: Record<string, string>) => new Request("http://localhost/", { headers });
    expect(clientIp(h({ "x-forwarded-for": " 1.1.1.1 , 2.2.2.2", "x-real-ip": "3.3.3.3" }))).toBe("1.1.1.1");
    expect(clientIp(h({ "x-real-ip": "3.3.3.3" }))).toBe("3.3.3.3");
    expect(clientIp(h({}))).toBe("unknown");
  });
});

describe("validation", () => {
  it("validationMatchesApp", () => {
    expect(isStrongPassword("abcdefg1")).toBe(true);
    expect(isStrongPassword("abcdefgh")).toBe(false);
    expect(isStrongPassword("1234567a")).toBe(true);
    expect(isStrongPassword("abc1")).toBe(false);
    expect(normalizeEmail(" Nova@Team.GG ")).toBe("nova@team.gg");
    expect(isValidEmail("nova@team.gg")).toBe(true);
    expect(isValidEmail("nova@team")).toBe(false);
    expect(isValidEmail("nova team@x.gg")).toBe(false);
    expect(isValidFaceitUrl("https://www.faceit.com/ru/players/x")).toBe(true);
    expect(isValidFaceitUrl("https://faceit.com/en/players/x")).toBe(true);
    expect(isValidFaceitUrl("https://evil.com")).toBe(false);
    expect(isValidFaceitUrl("https://notfaceit.com/players/x")).toBe(false);
    expect(isValidFaceitUrl("https://faceit.com.evil.com/x")).toBe(false);
    expect(isValidFaceitUrl("ftp://faceit.com/x")).toBe(false);
  });
});
