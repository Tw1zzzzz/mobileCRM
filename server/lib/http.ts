import type { ZodType } from "zod";

export type ErrorCode =
  | "bad_request"
  | "validation_failed"
  | "invalid_email"
  | "weak_password"
  | "invalid_credentials"
  | "unauthorized"
  | "token_expired"
  | "email_not_verified"
  | "team_not_found"
  | "email_taken"
  | "invalid_code"
  | "code_expired"
  | "too_many_attempts"
  | "rate_limited"
  | "mail_unavailable"
  | "internal";

/** An expected failure: turned into an error envelope by `handle`. */
export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  /** Message sent to the client; null stays null in the envelope. */
  readonly publicMessage: string | null;

  constructor(code: ErrorCode, message: string | null, status: number) {
    super(message ?? code);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.publicMessage = message;
  }
}

export function ok(data: unknown, status = 200, meta?: object): Response {
  const body = meta === undefined
    ? { success: true, data, error: null }
    : { success: true, data, error: null, meta };
  return Response.json(body, { status });
}

export function fail(code: ErrorCode, message: string | null, status: number): Response {
  return Response.json(
    { success: false, data: null, error: { code, message } },
    { status },
  );
}

export async function parseBody<T>(req: Request, schema: ZodType<T>): Promise<T> {
  let raw: unknown;
  try {
    raw = await req.json();
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
    if (e instanceof ApiError) return fail(e.code, e.publicMessage, e.status);
    console.error("unhandled error", e);
    return fail("internal", "Internal server error", 500);
  }
}
