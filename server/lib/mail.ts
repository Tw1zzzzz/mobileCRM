import { Resend } from "resend";

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

/** Sends through Resend; `from` must be a sender verified in the Resend account. */
export class ResendMailer implements Mailer {
  private readonly client: Resend;

  constructor(
    apiKey: string,
    private readonly from: string,
  ) {
    this.client = new Resend(apiKey);
  }

  async send(m: MailMessage): Promise<void> {
    const { error } = await this.client.emails.send({
      from: this.from,
      to: m.to,
      subject: m.subject,
      text: m.text,
    });
    if (error) throw new Error(`Resend failed to send mail: ${error.name}: ${error.message}`);
  }
}
