import Link from "next/link";
import { CARD_PATH, cardHref, cardQuery, type CardData, type CardTab, type GameSource, type TestType } from "@/lib/staff/card";
import { CorrTab } from "./CorrTab";
import { FilterSelect } from "./FilterSelect";
import { FoodTab } from "./FoodTab";
import { IdentityRow } from "./IdentityRow";
import { GameTab } from "./GameTab";
import { KpiStrip } from "./KpiStrip";
import { ProfileTab } from "./ProfileTab";
import { SleepTab } from "./SleepTab";
import { TestsTab } from "./TestsTab";
import { WheelTab } from "./WheelTab";
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

/** The board's «Источник игр»; practices and tournaments arrive with plan C, so they are listed but disabled. */
const SOURCE_OPTIONS: readonly { value: GameSource | "prak" | "tour"; label: string; disabled?: boolean }[] = [
  { value: "all", label: "Все" },
  { value: "faceit", label: "Faceit" },
  { value: "prak", label: "Пракки (скоро)", disabled: true },
  { value: "tour", label: "Турниры (скоро)", disabled: true },
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
      <IdentityRow player={player} />

      <div className={styles.filters}>
        <FilterSelect
          id="f-type"
          name="type"
          label="Тип теста"
          action={CARD_PATH}
          hidden={cardQuery(params).filter(([name]) => name !== "type")}
          value={params.type}
          options={TYPE_OPTIONS}
        />
        <FilterSelect
          id="f-src"
          name="src"
          label="Источник игр"
          action={CARD_PATH}
          hidden={cardQuery(params).filter(([name]) => name !== "src")}
          value={params.src}
          options={SOURCE_OPTIONS}
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

      {params.tab === "profile" && card.profile !== null ? (
        <ProfileTab archetype={card.archetype} profile={card.profile} params={params} />
      ) : params.tab === "tests" ? (
        <TestsTab tests={card.tests} />
      ) : params.tab === "game" && card.game !== null ? (
        <GameTab game={card.game} />
      ) : params.tab === "corr" && card.corr !== null ? (
        <CorrTab corr={card.corr} />
      ) : params.tab === "sleep" ? (
        <SleepTab sleep={card.sleep} />
      ) : params.tab === "wheel" ? (
        <WheelTab wheel={card.wheel} />
      ) : params.tab === "food" ? (
        <FoodTab food={card.food} />
      ) : (
        <Soon />
      )}
    </section>
  );
}
