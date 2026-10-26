// Same rules as `AuthValidator` / `WebLinks` in core/.../model.

const EMAIL_PATTERN = /^[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const WEB_LINK_PATTERN = /^(https?):\/\/([^/?#:@\s]+@)?([^/?#:\s]+)(:\d+)?([/?#]\S*)?$/i;

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

export function isValidEmail(email: string): boolean {
  return EMAIL_PATTERN.test(email);
}

/** At least 8 characters with both letters and digits. */
export function isStrongPassword(password: string): boolean {
  return password.length >= 8 && /\p{L}/u.test(password) && /\p{Nd}/u.test(password);
}

/** http(s) link on faceit.com or any *.faceit.com host. */
export function isValidFaceitUrl(text: string): boolean {
  const host = WEB_LINK_PATTERN.exec(text.trim())?.[3]?.toLowerCase();
  if (!host) return false;
  return host === "faceit.com" || host.endsWith(".faceit.com");
}
