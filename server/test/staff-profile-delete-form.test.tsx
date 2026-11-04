import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/app/staff/actions", () => ({ deleteAccountAction: vi.fn() }));

import { DeleteForm } from "@/components/staff/profile/DeleteForm";

const LAST_STAFF = "Вы — последний стафф команды: команда и все её данные (игры, календарь, раскидки, заметки) будут удалены.";

describe("account deletion warning", () => {
  it("warnsTheLastStaffThatTheTeamGoesToo", () => {
    expect(renderToStaticMarkup(<DeleteForm lastStaff />)).toContain(LAST_STAFF);
  });

  it("saysNothingAboutTheTeamWhenAnotherStaffRemains", () => {
    const markup = renderToStaticMarkup(<DeleteForm lastStaff={false} />);
    expect(markup).not.toContain("последний стафф");
    expect(markup).toContain("Аккаунт и все данные будут удалены без возможности восстановления.");
  });
});
