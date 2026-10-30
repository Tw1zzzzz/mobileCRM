import type { ZodType } from "zod";

export type ErrorCode =
  | "bad_request"
  | "validation_failed"
  | "invalid_email"
  | "weak_password"
  | "invalid_credentials"
  | "unauthorized"
  | "forbidden"
  | "token_expired"
  | "email_not_verified"
  | "team_not_found"
  | "email_taken"
  | "invalid_code"
  | "code_expired"
  | "too_many_attempts"
  | "rate_limited"
  | "mail_unavailable"
  | "not_found"
  | "version_conflict"
  | "payload_too_large"
  | "internal";

/** An expected failure: turned into an error envelope by `handle`. */
export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  /** Message sent to the client; null stays null in the envelope. */
  readonly publicMessage: string | null;
  /** Extra envelope data (e.g. `{ current_version }`), sent as `meta` when set. */
  readonly meta: object | undefined;

  constructor(code: ErrorCode, message: string | null, status: number, meta?: object) {
    super(message ?? code);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.publicMessage = message;
    this.meta = meta;
  }
}

export function ok(data: unknown, status = 200, meta?: object): Response {
  const body = meta === undefined
    ? { success: true, data, error: null }
    : { success: true, data, error: null, meta };
  return Response.json(body, { status });
}

export function fail(code: ErrorCode, message: string | null, status: number, meta?: object): Response {
  const error = { code, message };
  const body = meta === undefined
    ? { success: false, data: null, error }
    : { success: false, data: null, error, meta };
  return Response.json(body, { status });
}

/**
 * Reads the body as bytes, refusing more than `maxBytes`. A declared
 * `content-length` over the limit is rejected before reading; otherwise the
 * stream is cut off as soon as the limit is exceeded.
 */
export async function readBytesLimited(req: Request, maxBytes: number): Promise<Buffer> {
  const tooLarge = () => new ApiError("payload_too_large", `Body is larger than ${maxBytes} bytes`, 413);
  const declared = Number(req.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maxBytes) throw tooLarge();
  if (!req.body) return Buffer.alloc(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw tooLarge();
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks);
}

/** `maxBytes` makes oversized bodies fail with `413 payload_too_large` before parsing. */
export async function parseBody<T>(req: Request, schema: ZodType<T>, maxBytes?: number): Promise<T> {
  let raw: unknown;
  const text = maxBytes === undefined ? undefined : (await readBytesLimited(req, maxBytes)).toString("utf8");
  try {
    raw = text === undefined ? await req.json() : JSON.parse(text);
  } catch {
    throw new ApiError("bad_request", "Request body is not valid JSON", 400);
  }
  const parsed = schema.safeParse(raw);
  if (!parsed.success) {
    const message = parsed.error.issues
      .map((i) => `${i.path.join(".") || "body"}: ${i.message}`)
      .join("; ");
    throw new ApiError("validation_failed", message, 422);
  }
  return parsed.data;
}

export async function handle(fn: () => Promise<Response>): Promise<Response> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof ApiError) return fail(e.code, e.publicMessage, e.status, e.meta);
    console.error("unhandled error", e);
    return fail("internal", "Internal server error", 500);
  }
}
