/** The staff sidebar, in the order of the design's «Меню Staff». Items without `href` are «Скоро» and not links. */

export interface NavItem {
  name: string;
  /** Route to open; null while the section is not built. */
  href: string | null;
  /** Path of the 24x24 stroke icon. */
  icon: string;
  /** The item is highlighted only on this path (and its sub-pages); links with a hash are never highlighted. */
  activePath: string | null;
}

export interface NavGroup {
  title: string | null;
  items: readonly NavItem[];
}

export const NAV_GROUPS: readonly NavGroup[] = [
  {
    title: null,
    items: [
      { name: "Обзор", href: "/staff/overview", activePath: "/staff/overview", icon: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" },
      { name: "Календарь", href: null, activePath: null, icon: "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4" },
      { name: "Команда", href: "/staff/players#team", activePath: null, icon: "M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6z" },
      { name: "Игроки", href: "/staff/players", activePath: "/staff/players", icon: "M9 4.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM2.5 20c.8-3.4 3.3-5 6.5-5s5.7 1.6 6.5 5M16 4.5a3.5 3.5 0 0 1 0 7M18 15c2 .6 3.2 2.2 3.5 5" },
      { name: "Раскидки и скрипты", href: null, activePath: null, icon: "M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3zM9 3v15M15 6v15" },
    ],
  },
  {
    title: "Аналитика",
    items: [
      { name: "Статистика", href: null, activePath: null, icon: "M4 20V10M10 20V4M16 20v-7M22 20H2" },
      { name: "Игровая статистика", href: null, activePath: null, icon: "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 3v3M12 18v3M3 12h3M18 12h3" },
    ],
  },
];

/** Whether `pathname` is the item's page or one under it. */
export function isActive(item: NavItem, pathname: string): boolean {
  const base = item.activePath;
  return base !== null && (pathname === base || pathname.startsWith(base + "/"));
}
