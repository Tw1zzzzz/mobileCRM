/** What a staff form shows after its Server Action: an error, a notice, or the second step of a code flow. */
export type FormState =
  | { status: "idle" }
  | { status: "error"; message: string }
  | { status: "codeSent"; email: string }
  | { status: "done"; message: string };

export const IDLE: FormState = { status: "idle" };
