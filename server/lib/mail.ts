import nodemailer, { type Transporter } from "nodemailer";
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

export interface SmtpConfig {
  host: string;
  port: number;
  secure: boolean;
  user: string;
  password: string;
  /** Sender shown to the recipient, e.g. `Pulse <pulse@gmail.com>`. */
  from: string;
}

/** Hanging Gmail must fail fast (-> 503) rather than hit the serverless platform timeout. */
export const SMTP_TIMEOUT_MS = 10_000;

/** Transport options: bounded timeouts, and STARTTLS is mandatory unless the connection is implicit TLS. */
export function smtpTransportOptions(config: SmtpConfig) {
  return {
    host: config.host,
    port: config.port,
    secure: config.secure,
    requireTLS: !config.secure,
    connectionTimeout: SMTP_TIMEOUT_MS,
    greetingTimeout: SMTP_TIMEOUT_MS,
    socketTimeout: SMTP_TIMEOUT_MS,
    auth: { user: config.user, pass: config.password },
  };
}

/** Sends through an SMTP account (Gmail with an app password needs no domain). */
export class SmtpMailer implements Mailer {
  readonly from: string;
  private readonly transport: Transporter;

  /** `transport` is injectable for tests; by default a real SMTP connection is used. */
  constructor(config: SmtpConfig, transport?: Transporter) {
    this.from = config.from;
    this.transport =
      transport ??
      nodemailer.createTransport(smtpTransportOptions(config));
  }

  async send(m: MailMessage): Promise<void> {
    try {
      await this.transport.sendMail({ from: this.from, to: m.to, subject: m.subject, text: m.text });
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      throw new Error(`SMTP failed to send mail: ${reason}`);
    }
  }
}
