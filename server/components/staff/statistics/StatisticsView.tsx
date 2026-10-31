import { FaceitNotice } from "@/components/staff/card/FaceitNotice";
import { GameCard } from "@/components/staff/statistics/GameCard";
import { LinkCard } from "@/components/staff/statistics/LinkCard";
import { Matrix } from "@/components/staff/statistics/Matrix";
import { Picker } from "@/components/staff/statistics/Picker";
import { PeriodChips, WhoChips } from "@/components/staff/statistics/Scope";
import { SmallMultiples } from "@/components/staff/statistics/SmallMultiples";
import type { StatsPage } from "@/lib/staff/statistics-page";
import styles from "./statistics.module.css";

type DataPage = Extract<StatsPage, { kind: "data" }>;

function Empty({ playerCode }: { playerCode: string }) {
  return (
    <section className={`${styles.card} ${styles.empty}`}>
      <h2 className={styles.cardTitle}>В команде пока нет игроков</h2>
      <span className={styles.sub}>Код игрока</span>
      <code className={styles.code}>{playerCode}</code>
      <p className={styles.emptyText}>Отправьте этот код игрокам: они введут его в приложении Pulse и появятся в команде.</p>
    </section>
  );
}

function NoData() {
  return (
    <section className={`${styles.card} ${styles.empty}`}>
      <span className={styles.emptyIcon}>
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
        </svg>
      </span>
      <h2 className={styles.cardTitle}>Пока нет данных за этот период</h2>
      <p className={styles.emptyText}>
        Игроки ещё не заполнили состояние или не подключили Faceit — графики появятся, когда данные синхронизируются.
      </p>
    </section>
  );
}

function Links({ page }: { page: DataPage }) {
  const { links, linksMessage, pooled } = page.view;
  return (
    <section className={styles.card}>
      <div className={styles.cardTitles}>
        <h2 className={styles.cardTitle}>Главные связи</h2>
        <span className={styles.sub}>Сильнейшие связи между выбранными метриками за период — с примером на данных</span>
        {pooled && (
          <span className={styles.sub}>Вся команда: дни всех игроков объединены, поэтому на r влияют и различия между игроками.</span>
        )}
      </div>
      {linksMessage !== null ? (
        <span className={styles.sub}>{linksMessage}</span>
      ) : (
        <div className={styles.linksGrid}>
          {links.map((link) => (
            <LinkCard key={link.title} link={link} />
          ))}
        </div>
      )}
    </section>
  );
}

function Body({ page }: { page: DataPage }) {
  const { view, params } = page;
  const analysed = params.metrics.length > 0;
  return (
    <>
      <Picker params={params} />
      {view.notices.map((notice) => (
        <FaceitNotice key={notice.message} notice={notice} />
      ))}
      {analysed && !view.hasAnyData ? (
        <NoData />
      ) : (
        <>
          <Links page={page} />
          {analysed && <SmallMultiples multiples={view.multiples} team={view.team} />}
          {view.matrix !== null && <Matrix matrix={view.matrix} />}
          {view.game !== null && <GameCard game={view.game} />}
          {view.coverage !== null && <span className={styles.sub}>{view.coverage}</span>}
        </>
      )}
    </>
  );
}

/** «Статистика»: correlation analysis of the team or one player; the selection lives in the URL. */
export function StatisticsView({ page }: { page: StatsPage }) {
  if (page.kind === "empty") {
    return (
      <div className={styles.page}>
        <Empty playerCode={page.playerCode} />
      </div>
    );
  }
  const { view, params, players } = page;
  return (
    <div className={styles.page}>
      <section className={styles.hero}>
        <div className={styles.heroText}>
          <span className={styles.eyebrow}>Что из состояния влияет на игру</span>
          <p className={styles.lead}>Как менялись состояние и форма — и как это связано с игрой.</p>
        </div>
        <div className={styles.controls}>
          <WhoChips params={params} players={players} />
          <PeriodChips params={params} />
          <span className={styles.range}>{view.rangeLabel}</span>
          {view.truncated !== null && (
            <p className={styles.truncated}>{`Вся команда — показаны первые ${view.truncated.shown} игроков из ${view.truncated.total}.`}</p>
          )}
        </div>
      </section>
      <Body page={page} />
    </div>
  );
}
