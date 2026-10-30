/** What a staff form shows after its Server Action: an error, or the second step of a password reset. */
export type FormState =
  | { status: "idle" }
  | { status: "error"; message: string }
  | { status: "codeSent"; email: string };

export const IDLE: FormState = { status: "idle" };
