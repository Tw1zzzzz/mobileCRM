export type MailPurpose = "verify" | "reset";

/** Sends one-time codes by email. Real implementations arrive with the mail task. */
export interface Mailer {
  sendCode(to: string, purpose: MailPurpose, code: string): Promise<void>;
}

/** Logs instead of sending. Never use where a real recipient matters. */
export class ConsoleMailer implements Mailer {
  async sendCode(to: string, purpose: MailPurpose, code: string): Promise<void> {
    console.log(`[mail:${purpose}] to=${to} code=${code}`);
  }
}
