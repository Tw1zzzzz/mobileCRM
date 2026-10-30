import type { Metadata } from "next";
import { Onest } from "next/font/google";
import styles from "./staff.module.css";

const onest = Onest({
  subsets: ["cyrillic", "latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-onest",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Кабинет стаффа — Pulse",
  robots: { index: false, follow: false },
};

/** Tokens, font and background for everything under /staff; who may see what is decided by the route groups. */
export default function StaffLayout({ children }: { children: React.ReactNode }) {
  return <div className={`${styles.root} ${onest.variable}`}>{children}</div>;
}
