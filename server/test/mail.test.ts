import { describe, expect, it } from "vitest";
import nodemailer from "nodemailer";
import { createMailer } from "../lib/deps";
import { ConsoleMailer, ResendMailer, SmtpMailer } from "../lib/mail";

describe("createMailer", () => {
  it("createMailerPicksSmtpWhenPasswordSet", () => {
    const mailer = createMailer({ SMTP_PASSWORD: "app-pass", SMTP_USER: "pulse@gmail.com", RESEND_API_KEY: "re_x" });
    expect(mailer).toBeInstanceOf(SmtpMailer);
    expect((mailer as SmtpMailer).from).toBe("Pulse <pulse@gmail.com>");
  });

  it("uses MAIL_FROM for the SMTP sender when set", () => {
    const mailer = createMailer({ SMTP_PASSWORD: "p", SMTP_USER: "u@gmail.com", MAIL_FROM: "Pulse Beta <u@gmail.com>" });
    expect((mailer as SmtpMailer).from).toBe("Pulse Beta <u@gmail.com>");
  });

  it("createMailerNeedsSmtpUser", () => {
    expect(() => createMailer({ SMTP_PASSWORD: "app-pass" })).toThrow(/SMTP_USER/);
  });

  it("createMailerFallsBackToResendThenConsole", () => {
    expect(createMailer({ RESEND_API_KEY: "re_x", MAIL_FROM: "a@b.co" })).toBeInstanceOf(ResendMailer);
    expect(() => createMailer({ RESEND_API_KEY: "re_x" })).toThrow(/MAIL_FROM/);
    expect(createMailer({})).toBeInstanceOf(ConsoleMailer);
  });

  it("createMailerRequiresMailInProduction", () => {
    expect(() => createMailer({ VERCEL_ENV: "production" })).toThrow(/SMTP_PASSWORD or RESEND_API_KEY/);
  });
});

describe("SmtpMailer", () => {
  const config = { host: "smtp.gmail.com", port: 465, secure: true, user: "u@gmail.com", password: "app-pass", from: "Pulse <u@gmail.com>" };

  it("smtpMailerSendsMessage", async () => {
    const transport = nodemailer.createTransport({ jsonTransport: true });
    const sent: unknown[] = [];
    const original = transport.sendMail.bind(transport);
    transport.sendMail = (async (options: Parameters<typeof original>[0]) => {
      const info = await original(options);
      sent.push(JSON.parse(info.message as string));
      return info;
    }) as typeof transport.sendMail;

    await new SmtpMailer(config, transport).send({ to: "nova@team.gg", subject: "Код", text: "Ваш код: 123456" });

    expect(sent).toHaveLength(1);
    const message = sent[0] as { from: { address: string; name: string }; to: { address: string }[]; subject: string; text: string };
    expect(message.from).toEqual({ address: "u@gmail.com", name: "Pulse" });
    expect(message.to).toEqual([{ address: "nova@team.gg", name: "" }]);
    expect(message.subject).toBe("Код");
    expect(message.text).toBe("Ваш код: 123456");
  });

  it("wraps failures without leaking the password", async () => {
    const transport = nodemailer.createTransport({ jsonTransport: true });
    transport.sendMail = (async () => {
      throw new Error("Invalid login");
    }) as typeof transport.sendMail;
    const error = await new SmtpMailer(config, transport)
      .send({ to: "a@b.co", subject: "s", text: "t" })
      .catch((e: Error) => e);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toBe("SMTP failed to send mail: Invalid login");
    expect((error as Error).message).not.toContain("app-pass");
  });
});
