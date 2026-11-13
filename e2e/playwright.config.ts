import { defineConfig, devices } from "@playwright/test";

// TD-TEST-008. The suite drives a running Clavaris instance (CLAVARIS_BASE_URL) that has already been
// seeded by e2e/seed.sh, whose output supplies ORG_ID, CLIENT_ID, REDIRECT_URI, USER_EMAIL_PREFIX,
// USER_PASSWORD, OPERATOR_EMAIL and OPERATOR_PASSWORD. See e2e/README.md.

// When the browser runs in a container and the instance on its host, "localhost" has to mean the host
// for the instance's own absolute links to work (E2E_HOST_RESOLVER=host.docker.internal).
const hostMap = process.env.E2E_HOST_RESOLVER;
const launchOptions = hostMap ? { args: [`--host-resolver-rules=MAP localhost ${hostMap}`] } : {};

// The same flows in the other two engines. Chromium is the browser most people use, but the CSS and
// scripts also ship to Firefox (Gecko) and Safari (WebKit), which differ on the details a Chromium-only
// run cannot see. They take the operator session `setup` saved: a session cookie is not browser-specific.
const otherEngines = (["firefox", "webkit"] as const).flatMap((engine) => {
  const device = engine === "firefox" ? "Desktop Firefox" : "Desktop Safari";
  return [
    { name: `${engine}-hosted`, testMatch: /hosted-.*\.spec\.ts/, use: { ...devices[device] } },
    {
      name: `${engine}-operator`,
      testMatch: /(dashboard|flows-.*)\.spec\.ts/,
      dependencies: ["setup"],
      use: { ...devices[device], storageState: ".auth/operator.json" },
    },
  ];
});

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
    launchOptions,
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
    // What the operator does: create and edit Organizations, set up connections.
    {
      name: "flows",
      testMatch: /flows-.*\.spec\.ts/,
      dependencies: ["setup"],
      use: { ...devices["Desktop Chrome"], storageState: ".auth/operator.json" },
    },
    { name: "hosted", testMatch: /hosted-.*\.spec\.ts/, use: { ...devices["Desktop Chrome"] } },
    // The analysis: every page in four variants. Builds its own browser contexts (language, colour
    // scheme, viewport), so it takes no storage state here, only the saved operator session.
    // No trace or screenshot: a sweep of hundreds of pages makes a trace too large to write out, and
    // the findings file already says which page and what.
    { name: "sweep", testMatch: /sweep\.spec\.ts/, dependencies: ["setup"], use: { trace: "off", screenshot: "off" } },
    ...otherEngines,
  ],
});
