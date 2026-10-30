import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../lib/http";
import { MESSAGES, authMessage, playersLabel } from "../lib/staff/messages";

describe("staff messages", () => {
  it("playersLabelDeclines", () => {
    const labels = [0, 1, 2, 4, 5, 11, 12, 21, 22, 25, 111].map(playersLabel);
    expect(labels).toEqual([
      "0 игроков", "1 игрок", "2 игрока", "4 игрока", "5 игроков", "11 игроков",
      "12 игроков", "21 игрок", "22 игрока", "25 игроков", "111 игроков",
    ]);
  });

  it("authMessageUsesTheAppTexts", () => {
    expect(authMessage(new ApiError("invalid_credentials", "x", 401))).toBe("Неверный логин или пароль");
    expect(authMessage(new ApiError("rate_limited", "x", 429))).toBe(
      "Слишком много запросов. Попробуйте через несколько минут",
    );
    expect(authMessage(new ApiError("internal", "x", 500))).toBe(MESSAGES.server);
  });

  it("authMessageHidesUnexpectedErrors", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(authMessage(new Error("connect ECONNREFUSED db:5432"))).toBe(MESSAGES.server);
    expect(spy).toHaveBeenCalledOnce();
    spy.mockRestore();
  });
});
