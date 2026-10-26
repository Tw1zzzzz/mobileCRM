export interface MailMessage {
  to: string;
  subject: string;
  text: string;
}

export interface Mailer {
  send(m: MailMessage): Promise<void>;
}

/** Logs instead of sending. Never use where a real recipient matters. */
export class ConsoleMailer implements Mailer {
  async send(m: MailMessage): Promise<void> {
    console.log(`[mail] to=${m.to} subject=${m.subject}\n${m.text}`);
  }
}
