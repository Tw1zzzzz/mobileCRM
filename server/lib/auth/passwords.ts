import { hash, verify } from "@node-rs/argon2";

/** argon2id (the library default) with its OWASP-grade default cost parameters. */
export function hashPassword(password: string): Promise<string> {
  return hash(password);
}

/** False for a wrong password and for a malformed hash; never throws. */
export async function verifyPassword(passwordHash: string, password: string): Promise<boolean> {
  try {
    return await verify(passwordHash, password);
  } catch {
    return false;
  }
}
