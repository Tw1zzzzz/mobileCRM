import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const testDatabaseUrl = process.env.TEST_DATABASE_URL ?? "postgres://localhost/pulse_test";

export default defineConfig({
  resolve: {
    // Mirrors tsconfig "paths": { "@/*": ["./*"] }.
    alias: { "@": fileURLToPath(new URL(".", import.meta.url)) },
  },
  test: {
    globalSetup: ["./test/setup.ts"],
    // All DB tests share one database and resetDb() truncates tables.
    fileParallelism: false,
    env: {
      // Route files call defaultDeps(), which reads these.
      DATABASE_URL: testDatabaseUrl,
      TEST_DATABASE_URL: testDatabaseUrl,
      AUTH_SECRET: "test-secret-test-secret-test-secret-0123456789",
    },
  },
});
