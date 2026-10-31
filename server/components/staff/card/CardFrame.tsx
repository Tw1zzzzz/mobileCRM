import Link from "next/link";
import { CARD_PATH, cardHref, cardQuery, type CardData, type CardTab, type TestType } from "@/lib/staff/card";
import { KpiStrip } from "./KpiStrip";
import { SleepTab } from "./SleepTab";
import { TestsTab } from "./TestsTab";
import { TypeFilter } from "./TypeFilter";
import styles from "./card.module.css";

const TABS: readonly { id: CardTab; name: string }[] = [
  { id: "profile", name: "Профиль" },
  { id: "tests", name: "Тесты" },
  { id: "game", name: "Игра" },
  { id: "corr", name: "Корреляции" },
  { id: "wheel", name: "Колесо баланса" },
  { id: "food", name: "Питание" },
  { id: "sleep", name: "Сон" },
];

const TYPE_OPTIONS: readonly { value: TestType; label: string }[] = [
  { value: "all", label: "Все тесты" },
  { value: "cog", label: "Когнитивные" },
  { value: "game", label: "Игровые до/после" },
];

/** A tab whose body is built in a later step. */
function Soon() {
  return <p className={styles.soonPanel}>Скоро</p>;
}

/**
 * The selected player's card under the roster: identity, filters, the KPI strip, the seven tabs and the body of
 * the chosen one. Everything comes from `loadCard`; nothing here reads data.
 */
export function CardFrame({ card }: { card: CardData }) {
  if (card.kind === "none") return null;
  const { params, player } = card;
  return (
    <section id="player-card" className={styles.card} aria-labelledby="pname">
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
      </div>

      <div className={styles.filters}>
        <TypeFilter
          action={CARD_PATH}
          hidden={cardQuery(params).filter(([name]) => name !== "type")}
          value={params.type}
          options={TYPE_OPTIONS}
        />
        <span className={styles.range}>{card.rangeLabel}</span>
      </div>

      <KpiStrip tiles={card.kpis} />

      <nav aria-label="Разделы карточки" className={styles.tabs}>
        {TABS.map((tab) => {
          const on = tab.id === params.tab;
          return (
            <Link
              key={tab.id}
              href={cardHref(params, { tab: tab.id })}
              scroll={false}
              className={on ? `${styles.tab} ${styles.tabOn}` : styles.tab}
              aria-current={on ? "page" : undefined}
            >
              {tab.name}
            </Link>
          );
        })}
      </nav>

      {params.tab === "tests" ? (
        <TestsTab tests={card.tests} />
      ) : params.tab === "sleep" ? (
        <SleepTab sleep={card.sleep} />
      ) : (
        <Soon />
      )}
    </section>
  );
}
