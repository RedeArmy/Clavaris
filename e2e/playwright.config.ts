import { defineConfig, devices } from "@playwright/test";

// TD-TEST-008. The suite drives a running Clavaris instance (CLAVARIS_BASE_URL) that has already been
// seeded by load-testing/setup-pkce-fixture.sh, whose output supplies ORG_ID, CLIENT_ID, REDIRECT_URI,
// USER_EMAIL_PREFIX and USER_PASSWORD. See e2e/README.md.
export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  expect: { timeout: 7_000 },
  // One shared instance and one shared set of seeded accounts: run serially so a test that signs a
  // user in cannot race one that checks the rate limiter or the session.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.CLAVARIS_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    // Signs the seeded operator in once and saves the session, so the dashboard tests start signed
    // in instead of each spending one of the account's allowed sign-ins (the login limiter is real).
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "dashboard",
      testMatch: /dashboard.*\.spec\.ts/,
      dependencies: ["setup"],
      use: { ...devices["Desktop Chrome"], storageState: ".auth/operator.json" },
    },
    { name: "hosted", testMatch: /hosted-.*\.spec\.ts/, use: { ...devices["Desktop Chrome"] } },
  ],
});
