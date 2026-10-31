import type { Archetype } from "@/lib/staff/archetype";
import { cardQuery, type CardParams } from "@/lib/staff/card";
import type { ProfileNotes } from "@/lib/staff/notes";
import { IMAGE_KINDS } from "@/lib/staff/notes-shared";
import { NO_DATA } from "@/lib/staff/overview-format";
import { ImageSlot } from "./ImageSlot";
import { NotesForm } from "./NotesForm";
import styles from "./card.module.css";

/**
 * «Профиль»: the «Архетип» card, then the staff notes and the three image drops («Коммуникативная линия»).
 * «Роль в CS2» is not built (the questionnaire stays unreadable for staff).
 */
export function ProfileTab({
  archetype,
  profile,
  params,
}: {
  archetype: Archetype | null;
  profile: ProfileNotes;
  params: CardParams;
}) {
  const hidden = cardQuery(params);
  const scoped = (scope: string) => (profile.notice?.scope === scope ? profile.notice : null);
  return (
    <div className={styles.profile}>
      <div className={styles.profileGrid}>
        <section className={styles.profileCard} aria-labelledby="arch-title">
          <h3 id="arch-title" className={styles.grp}>
            Архетип
          </h3>
          {archetype === null ? (
            <p className={styles.profileEmpty}>{NO_DATA}</p>
          ) : (
            <>
              <span className={styles.archName}>{archetype.title}</span>
              <span className={styles.archText}>{archetype.summary}</span>
              {archetype.tags.length > 0 && (
                <ul className={styles.tags}>
                  {archetype.tags.map((tag) => (
                    <li key={tag} className={styles.tag}>
                      {tag}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>
      </div>
      <div id="notes" className={styles.profile}>
        {scoped("form") !== null && (
          <p role="alert" className={styles.statusError}>
            {profile.notice?.text}
          </p>
        )}
        <NotesForm hidden={hidden} note={profile.note} notice={scoped("note")} />
        <div className={styles.slots}>
          {IMAGE_KINDS.map((kind) => (
            <ImageSlot
              key={kind}
              kind={kind}
              playerId={params.player}
              hidden={hidden}
              image={profile.images[kind]}
              notice={scoped(kind)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
