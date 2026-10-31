/** One overall Faceit deadline per page: slower Faceit data counts as unavailable instead of holding the page. */
export const FACEIT_PAGE_DEADLINE_MS = 5_000;

/**
 * Resolves with `work`'s result, or with `fallback` once `ms` have passed. A rejection before the deadline is passed
 * on; the work is never cancelled, and a result or rejection that arrives after the deadline is dropped, so it can
 * never become an unhandled rejection.
 */
export function raceDeadline<T>(work: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      settled = true;
      resolve(fallback);
    }, ms);
    const finish = (settle: () => void): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      settle();
    };
    work.then(
      (value) => finish(() => resolve(value)),
      (error: unknown) => finish(() => reject(error)),
    );
  });
}
