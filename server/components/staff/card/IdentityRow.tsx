import type { CardPlayer } from "@/lib/staff/card";
import { faceitLink } from "@/lib/staff/card-format";
import { NO_DATA } from "@/lib/staff/overview-format";
import styles from "./card.module.css";

/**
 * A link whose target the app does not have: shown, but off. A link role without `href` is not a link for the
 * browser, so it cannot navigate; it stays focusable and a screen reader hears «нет данных» (hidden text, named by
 * `aria-describedby`); sighted users get the same words as a tooltip.
 */
function Unavailable({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <a role="link" aria-disabled="true" tabIndex={0} aria-describedby={id} className={styles.btnGhost} title={NO_DATA}>
      {children}
      <span id={id} className={styles.srOnly}>
        {NO_DATA}
      </span>
    </a>
  );
}

/**
 * Top of the card: initials avatar, nickname, last activity and the three link buttons. The app stores no Telegram
 * or Steam address, so those two are off; FACEIT is a link only for a real faceit.com address. There is no role
 * pill: the questionnaire («Роль в CS2») is not readable by staff.
 */
export function IdentityRow({ player }: { player: Pick<CardPlayer, "nickname" | "initials" | "faceitUrl" | "lastActivity"> }) {
  const faceit = faceitLink(player.faceitUrl);
  return (
    <div className={styles.identity}>
      <span className={styles.avatar} aria-hidden="true">
        {player.initials}
      </span>
      <div className={styles.who}>
        <h2 id="pname" className={styles.name}>
          {player.nickname}
        </h2>
        <span className={styles.sub}>Последняя активность: {player.lastActivity}</span>
      </div>
      <div className={styles.links}>
        <Unavailable id="identity-telegram-off">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M21 4L3 11l6 2 2 6 3-4 5 4z" />
          </svg>
          Telegram
        </Unavailable>
        {faceit === null ? (
          <Unavailable id="identity-faceit-off">FACEIT</Unavailable>
        ) : (
          <a className={styles.btnGhost} href={faceit} target="_blank" rel="noopener noreferrer">
            FACEIT
          </a>
        )}
        <Unavailable id="identity-steam-off">Steam</Unavailable>
      </div>
    </div>
  );
}
