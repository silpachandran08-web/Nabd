import { defineConfig } from "vitest/config";

// FEF-013: coverage gate on the safety logic. perFile: every matching file must reach the bar on its own, so one
// well-tested module cannot hide an untested one.
export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      reporter: ["text", "json-summary"],
      thresholds: {
        perFile: true,
        "src/{*Logic,vitals,money,format}.ts": { lines: 90 },
      },
    },
  },
});
