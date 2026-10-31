import Link from "next/link";
import type { DishDialog as DishDialogData } from "@/lib/staff/nutrition";
import nutrition from "./nutrition.module.css";

/**
 * The dish dialog of the board, rendered by the server when `?meal=` names a meal of the roster. Closing is a link
 * to the same view without `meal` (the backdrop closes it too). Read-only: no comment to the player (plan D).
 */
export function DishDialog({ dialog }: { dialog: DishDialogData }) {
  return (
    <div className={nutrition.overlay}>
      <Link href={dialog.closeHref} scroll={false} className={nutrition.backdrop} aria-hidden="true" tabIndex={-1} />
      <div className={nutrition.dlg} role="dialog" aria-modal="true" aria-labelledby="dish-title">
        <div className={dialog.photo === null ? nutrition.dlgShot : `${nutrition.dlgShot} ${nutrition.shotPhoto}`}>
          {dialog.photo === null ? (
            <span className={nutrition.shotLabel}>
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M7 3v8a2 2 0 0 0 4 0V3M9 11v10M16 3c-2 2-2 6 0 8v10" />
              </svg>
              без фото
            </span>
          ) : (
            // eslint-disable-next-line @next/next/no-img-element -- a private, already small JPEG behind our own route
            <img src={dialog.photo} alt="Фото блюда" className={nutrition.shotImg} />
          )}
          <Link href={dialog.closeHref} scroll={false} className={nutrition.close} aria-label="Закрыть">
            ✕
          </Link>
        </div>
        <div className={nutrition.dlgBody}>
          <div className={nutrition.dlgHead}>
            <span className={nutrition.pills}>
              <span className={`${nutrition.st} ${nutrition.stStaff}`}>{dialog.nickname}</span>
              {dialog.source && (
                <span className={`${nutrition.st} ${dialog.source.tone === "ok" ? nutrition.stOk : nutrition.stMute}`}>
                  {dialog.source.label}
                </span>
              )}
            </span>
            <h2 id="dish-title" className={nutrition.dlgTitle}>
              {dialog.name}
            </h2>
            <span className={nutrition.sub}>{dialog.when}</span>
          </div>
          <div className={nutrition.tiles}>
            {dialog.tiles.map((tile) => (
              <div key={tile.label} className={nutrition.tile}>
                <span className={nutrition.tileLabel}>{tile.label}</span>
                <span className={nutrition.tileValue}>{tile.value}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
