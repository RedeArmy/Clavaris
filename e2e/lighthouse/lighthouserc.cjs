// Lighthouse CI for the pages a person actually lands on. It exists beside the Playwright suite
// because the two look at different things: Playwright proves a control works under the real
// Content-Security-Policy, Lighthouse measures how the page loads, whether it is accessible, and
// whether it follows web best practice, and fails the build when a score drops below a floor.
//
// Environment: CLAVARIS_BASE_URL, ORG_ID, CLIENT_ID (what e2e/seed.sh prints) and, for the signed-in
// set, PLATFORM_SESSION (the value of the operator's PLATFORM_SESSION cookie, from .auth/operator.json).
//   LH_SET     public (default) | operator      which pages
//   LH_PRESET  desktop (default) | mobile       Lighthouse's own mobile profile is slow 4G and a throttled CPU
//   LH_ASSERT  1 to enforce the floors below; unset only collects, to measure a new baseline
//   LH_RUNS    runs per page, default 3 (Lighthouse's own advice: one run is noise)
//
// The floors are set from a measured baseline, a little below it, so they catch a regression and do not
// fail on noise. Raise them when the baseline rises.

const base = (process.env.CLAVARIS_BASE_URL || "http://localhost:8080").replace(/\/+$/, "");
const org = process.env.ORG_ID;
const client = process.env.CLIENT_ID;
const set = process.env.LH_SET || "public";
const mobile = process.env.LH_PRESET === "mobile";

const pages = {
  public: [
    `${base}/platform/login`,
    `${base}/platform/register`,
    `${base}/o/${org}/login?clientId=${client}`,
    `${base}/o/${org}/register?clientId=${client}`,
  ],
  operator: [`${base}/platform/dashboard`, `${base}/platform/account`],
}[set];

if (!pages) {
  throw new Error(`LH_SET must be public or operator, not ${set}`);
}
if (set === "public" && (!org || !client)) {
  throw new Error("ORG_ID and CLIENT_ID are needed for the public set: run e2e/seed.sh and export its output");
}

const settings = {
  preset: mobile ? undefined : "desktop",
  // The container the suite runs in has no user namespace for Chromium's sandbox.
  chromeFlags: "--no-sandbox --headless=new",
  // These pages are behind a Content-Security-Policy that blocks inline script; Lighthouse's own
  // injection works through the DevTools protocol, which the policy does not touch.
  onlyCategories: ["performance", "accessibility", "best-practices"],
};
if (set === "operator") {
  settings.extraHeaders = JSON.stringify({ Cookie: `PLATFORM_SESSION=${process.env.PLATFORM_SESSION}` });
}

// Floors from the first measured baseline (2026-11-13, medians of three runs, Lighthouse 12 on
// Chromium in the Playwright image, against a local instance), set a little below it. The public pages
// scored 99-100 everywhere. The operator dashboard on the throttled mobile profile is the noisy one:
// its three runs scored 79, 92 and 95 on the same page (total blocking time 224 to 683 ms), so its
// performance floor is lower. LHCI's default aggregation passes a floor if the best run reaches it.
const floors = {
  public: { performance: 0.9, accessibility: 0.95, "best-practices": 0.95 },
  operator: { performance: 0.8, accessibility: 0.95, "best-practices": 0.95 },
}[set];

module.exports = {
  ci: {
    collect: {
      url: pages,
      numberOfRuns: parseInt(process.env.LH_RUNS || "3", 10),
      settings,
    },
    assert: process.env.LH_ASSERT
      ? {
          assertions: Object.fromEntries(
            Object.entries(floors).map(([category, min]) => [`categories:${category}`, ["error", { minScore: min }]]),
          ),
        }
      : undefined,
  },
};
