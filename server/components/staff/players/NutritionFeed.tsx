import Link from "next/link";
import {
  ALL_PLAYERS,
  MAX_PLAYERS,
  NUTRITION_DAYS,
  nutritionHref,
  type MealCard,
  type NutritionData,
} from "@/lib/staff/nutrition";
import { DaySummary } from "./DaySummary";
import { DishDialog } from "./DishDialog";
import { Segments } from "./Segments";
import nutrition from "./nutrition.module.css";
import styles from "./players.module.css";

const PERIOD_LABEL: Record<(typeof NUTRITION_DAYS)[number], string> = { 1: "Сегодня", 3: "3 дня", 7: "7 дней" };

export const EMPTY_FEED =
  "Записей нет. Попробуйте выключить «Только с фото» или выбрать другой период";

function Chip({ href, on, children }: { href: string; on: boolean; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      scroll={false}
      className={on ? `${styles.chip} ${styles.chipOn}` : styles.chip}
      aria-current={on ? "true" : undefined}
    >
      {children}
    </Link>
  );
}

function FilterBar({ data }: { data: NutritionData }) {
  // Links never keep the dialog open.
  const { params, players } = data;
  const base = { ...params, meal: null };
  return (
    <div className={nutrition.filters}>
      <div role="group" aria-label="Игрок" className={`${nutrition.group} ${nutrition.groupWho}`}>
        <Chip href={nutritionHref(base, { who: ALL_PLAYERS })} on={params.who === ALL_PLAYERS}>
          Все игроки
        </Chip>
        {players.map((p) => (
          <Chip key={p.id} href={nutritionHref(base, { who: p.id })} on={params.who === p.id}>
            {p.nickname}
          </Chip>
        ))}
      </div>
      <div role="group" aria-label="Период" className={nutrition.group}>
        {NUTRITION_DAYS.map((days) => (
          <Chip key={days} href={nutritionHref(base, { days })} on={params.days === days}>
            {PERIOD_LABEL[days]}
          </Chip>
        ))}
      </div>
      <Chip href={nutritionHref(base, { photos: !params.photos })} on={params.photos}>
        <svg className={nutrition.chipIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
          <circle cx="12" cy="13" r="3.5" />
        </svg>
        Только с фото
      </Chip>
    </div>
  );
}

function MealBody({ meal }: { meal: MealCard }) {
  return (
    <>
      <span className={meal.photo === null ? nutrition.shot : `${nutrition.shot} ${nutrition.shotPhoto}`}>
        {meal.photo === null ? (
          <span className={nutrition.shotLabel}>
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M7 3v8a2 2 0 0 0 4 0V3M9 11v10M16 3c-2 2-2 6 0 8v10" />
            </svg>
            без фото
          </span>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element -- a private, already small JPEG behind our own route
          <img src={meal.photo} alt="" loading="lazy" className={nutrition.shotImg} />
        )}
        <span className={nutrition.nick}>{meal.nickname}</span>
      </span>
      <span className={nutrition.mealText}>
        <span className={nutrition.dish}>{meal.name}</span>
        <span className={nutrition.mealMeta}>
          {meal.kcal} ккал · {meal.time}
        </span>
      </span>
    </>
  );
}

function Meal({ meal }: { meal: MealCard }) {
  return (
    <li className={nutrition.mealItem}>
      {meal.href === null ? (
        <div className={nutrition.meal}>
          <MealBody meal={meal} />
        </div>
      ) : (
        <Link href={meal.href} scroll={false} className={nutrition.meal}>
          <MealBody meal={meal} />
        </Link>
      )}
    </li>
  );
}

/** «Питание команды»: filters, the feed by day, «Сводка за день» and, when `?meal=` names a meal of the roster, its dialog. */
export function NutritionFeed({ data }: { data: NutritionData }) {
  return (
    <>
      <Segments active="nutrition" />
      {data.players.length === 0 ? (
        <p className={styles.empty}>В команде пока нет игроков</p>
      ) : (
        <>
          <FilterBar data={data} />
          {data.hidden > 0 && <p className={nutrition.note}>Показаны первые {MAX_PLAYERS} игроков</p>}
          <div className={nutrition.layout}>
            <div className={nutrition.feed}>
              <span className={nutrition.count}>{data.countText}</span>
              {data.groups.map((group) => (
                <section key={group.label} aria-label={group.label} className={nutrition.day}>
                  <div className={nutrition.dayHead}>
                    <h2 className={nutrition.eyebrow}>{group.label}</h2>
                    <span className={nutrition.daySub}>{group.sub}</span>
                  </div>
                  <ul className={nutrition.meals}>
                    {group.meals.map((meal) => (
                      <Meal key={meal.key} meal={meal} />
                    ))}
                  </ul>
                </section>
              ))}
              {data.groups.length === 0 && <p className={nutrition.empty}>{EMPTY_FEED}</p>}
            </div>
            <aside className={nutrition.aside}>
              <DaySummary sub={data.summarySub} rows={data.summary} />
            </aside>
          </div>
        </>
      )}
      {data.dialog && <DishDialog dialog={data.dialog} />}
    </>
  );
}
