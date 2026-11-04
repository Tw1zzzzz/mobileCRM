import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

let pathname = "/staff/overview";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

const { Sidebar } = await import("@/components/staff/Sidebar");

function html(path: string): string {
  pathname = path;
  return renderToStaticMarkup(<Sidebar teamName="Atlant Esports" nickname="Coach" />);
}

describe("sidebar", () => {
  it("showsTheBoardMenu", () => {
    const markup = html("/staff/overview");
    expect(markup).toContain('aria-label="Меню Staff"');
    expect(markup).toContain("Atlant Technology");
    expect(markup).toContain("Performance CRM");
    for (const name of ["Обзор", "Календарь", "Игроки", "Раскидки и скрипты", "Статистика", "Игровая статистика"]) {
      expect(markup).toContain(name);
    }
    expect(markup).toContain("Команда</span>"); // a heading, not a link
    expect(markup).not.toContain('href="/staff/team"');
    expect(markup).not.toContain("Выйти");
    expect((markup.match(/aria-current="page"/g) ?? []).length).toBe(1);
  });

  it("profileCardShowsNicknameRoleAndLinksToTheProfile", () => {
    const markup = html("/staff/overview");
    expect(markup).toContain('aria-label="Профиль и настройки"');
    expect(markup).toContain('href="/staff/profile"');
    expect(markup).toContain(">CO<");
    expect(markup).toContain(">Coach<");
    expect(markup).toContain("Staff · Atlant Esports");
  });

  it("highlightsThePlayersAndTheProfileCard", () => {
    expect(html("/staff/players")).toMatch(/aria-current="page"[^>]*href="\/staff\/players"/);
    const profile = html("/staff/profile");
    expect(profile).toMatch(/aria-current="page"[^>]*href="\/staff\/profile"/);
    expect(profile).toContain("accountOn");
  });
});

describe("old team page", () => {
  it("redirectsPermanentlyToTheProfileBlock", () => {
    const source = readFileSync(join(__dirname, "..", "app", "staff", "(panel)", "team", "page.tsx"), "utf8");
    expect(source).toContain('permanentRedirect("/staff/profile#team")');
  });
});
