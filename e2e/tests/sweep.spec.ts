import AxeBuilder from "@axe-core/playwright";
import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

/**
 * Page sweep: visits every page of the operator dashboard (discovered by following its links, as
 * dashboard-scripts.spec.ts does) and every public and consumer page, in four variants (language,
 * colour scheme and viewport), and records what a person or an assistive technology would run into:
 * browser errors and CSP violations, horizontal overflow, broken images, accessibility violations
 * (axe, WCAG 2 A and AA), English text left in the Spanish interface, and a page without a title or
 * with the wrong number of top-level headings.
 *
 * It is an analysis, not a gate: it writes every finding to test-results/findings.ndjson and fails
 * only on what is never acceptable (a 5xx, an uncaught script error). See e2e/README.md.
 */

type Variant = {
  name: string;
  locale: string;
  scheme: "light" | "dark";
  viewport: { width: number; height: number };
};

const VARIANTS: Variant[] = [
  { name: "desktop-light-en", locale: "en-US", scheme: "light", viewport: { width: 1280, height: 800 } },
  { name: "desktop-dark-es", locale: "es-ES", scheme: "dark", viewport: { width: 1280, height: 800 } },
  { name: "mobile-light-es", locale: "es-ES", scheme: "light", viewport: { width: 375, height: 812 } },
  { name: "mobile-dark-en", locale: "en-US", scheme: "dark", viewport: { width: 375, height: 812 } },
];

type Finding = { page: string; variant: string; kind: string; detail: string };

const OUT = path.join(process.env.E2E_OUT ?? "test-results", "findings.ndjson");
const MAX_OPERATOR_PAGES = 90;

function record(finding: Finding) {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.appendFileSync(OUT, JSON.stringify(finding) + "\n");
}

const need = (name: string): string => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: run e2e/seed.sh and export its output`);
  return value;
};

// Words that show up in English sentences and in no Spanish one. Enough to notice a sentence that
// was never translated, without flagging brand names, ids or what a person typed.
const ENGLISH = /\b(the|your|with|this|that|from|you|are|will|when|which|please|has|have|not|can)\b/gi;

async function inspect(page: Page, url: string, variant: Variant, signedIn: boolean, origin: string) {
  const problems: Finding[] = [];
  const add = (kind: string, detail: string) => problems.push({ page: url, variant: variant.name, kind, detail });
  const csp: string[] = [];
  const errors: string[] = [];

  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error" && /Content Security Policy|Refused to/i.test(m.text())) csp.push(m.text());
  });
  const failed: string[] = [];
  page.on("response", (r) => {
    if (r.status() >= 400 && new URL(r.url()).origin === origin) {
      failed.push(`${r.status()} ${new URL(r.url()).pathname}`);
    }
  });

  const response = await page.goto(url, { waitUntil: "networkidle" }).catch((e) => {
    add("navigation-failed", String(e.message).slice(0, 160));
    return null;
  });
  if (!response) return problems;
  const status = response.status();
  if (status >= 500) add("http-5xx", String(status));
  else if (status >= 400) add("http-4xx", String(status));
  // A sign-in page reached while signed in redirects: the page that rendered is not the one asked for.
  if (signedIn && /\/(login|register)(\?|$)/.test(new URL(page.url()).pathname) && !/login|register/.test(url)) {
    add("bounced-to-login", page.url());
  }

  const dom = await page.evaluate((english) => {
    const out: Record<string, unknown> = {};
    const root = document.documentElement;
    out.overflow = root.scrollWidth - window.innerWidth;
    out.title = document.title;
    out.h1 = document.querySelectorAll("h1").length;
    out.lang = root.lang;
    out.brokenImages = [...document.images]
      .filter((i) => i.complete && i.naturalWidth === 0 && i.getAttribute("src"))
      .map((i) => i.getAttribute("src"));
    const re = new RegExp(english, "gi");
    const leftovers: string[] = [];
    for (const el of document.querySelectorAll("h1,h2,h3,p,label,button,a,th,td,span,li,legend,summary,option,small,dt,dd")) {
      if (el.closest("code,pre,script,style,[data-user-content],#clavaris-confirm")) continue;
      const own = [...el.childNodes]
        .filter((n) => n.nodeType === Node.TEXT_NODE)
        .map((n) => n.textContent ?? "")
        .join(" ")
        .replace(/\s+/g, " ")
        .trim();
      if (own.length < 12) continue;
      const hits = new Set((own.match(re) ?? []).map((w) => w.toLowerCase()));
      if (hits.size >= 2 || (own.length > 25 && hits.size >= 1)) leftovers.push(own.slice(0, 110));
    }
    out.leftovers = [...new Set(leftovers)].slice(0, 12);
    return out;
  }, ENGLISH.source);

  if ((dom.overflow as number) > 1) add("horizontal-overflow", `${dom.overflow}px wider than the viewport`);
  if (!dom.title) add("no-title", "document.title is empty");
  if (dom.h1 !== 1) add("h1-count", `${dom.h1} top-level headings`);
  for (const src of dom.brokenImages as string[]) add("broken-image", src);
  if (variant.locale.startsWith("es")) {
    if (!String(dom.lang).startsWith("es")) add("lang-attribute", `html lang="${dom.lang}" in the Spanish interface`);
    for (const text of dom.leftovers as string[]) add("untranslated", text);
  }

  for (const message of csp) add("csp", message.slice(0, 200));
  for (const message of errors) add("script-error", message.slice(0, 200));
  for (const message of new Set(failed)) add("failed-request", message);

  try {
    const axe = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
    for (const v of axe.violations) {
      const first = v.nodes[0]?.target?.join(" ") ?? "";
      add(`a11y:${v.id}`, `${v.impact} x${v.nodes.length} ${first.slice(0, 110)} | ${v.help}`);
    }
  } catch (e) {
    add("axe-failed", String((e as Error).message).slice(0, 120));
  }

  page.removeAllListeners("pageerror");
  page.removeAllListeners("console");
  page.removeAllListeners("response");
  return problems;
}

async function newContext(browser: Browser, variant: Variant, baseURL: string, state?: string): Promise<BrowserContext> {
  return browser.newContext({
    baseURL,
    locale: variant.locale,
    colorScheme: variant.scheme,
    viewport: variant.viewport,
    storageState: state,
  });
}

// The operator's pages are found by following links, the way a person would.
async function discoverOperatorPages(browser: Browser, baseURL: string): Promise<string[]> {
  const orgId = need("ORG_ID");
  const scope = `/platform/dashboard/organizations/${orgId}`;
  const inScope = (p: string) =>
    (p === "/platform/dashboard" || p.startsWith(scope) || p.startsWith("/platform/account")) &&
    !/logout|delete|impersonat|download|export|\.(csv|json)$/.test(p);
  const context = await newContext(browser, VARIANTS[0], baseURL, ".auth/operator.json");
  const page = await context.newPage();
  const seen: string[] = [];
  const queue = ["/platform/dashboard", scope];
  while (queue.length > 0 && seen.length < MAX_OPERATOR_PAGES) {
    const p = (queue.shift() as string).split("#")[0];
    if (seen.includes(p) || !inScope(p)) continue;
    const response = await page.goto(p).catch(() => null);
    if (!response || response.status() >= 400) continue;
    seen.push(p);
    for (const href of await page.$$eval("a[href]", (links) =>
      links.map((l) => l.getAttribute("href") ?? "").filter((h) => h.startsWith("/")),
    )) {
      // The language switch is the same page again in the other language: the variants already cover it.
      if (/[?&]lang=/.test(href)) continue;
      if (!seen.includes(href.split("#")[0])) queue.push(href);
    }
  }
  await context.close();
  return seen;
}

test.describe("page sweep", () => {
  test.setTimeout(45 * 60_000);

  test("every page, in every variant", async ({ browser, baseURL }) => {
    expect(baseURL).toBeTruthy();
    const base = baseURL as string;
    const orgId = need("ORG_ID");
    const clientId = need("CLIENT_ID");
    fs.rmSync(OUT, { force: true });

    const operatorPages = await discoverOperatorPages(browser, base);
    const publicPages = [
      "/platform/login",
      "/platform/register",
      "/platform/forgot-password",
      `/o/${orgId}/login`,
      `/o/${orgId}/login?clientId=${clientId}`,
      `/o/${orgId}/login?clientId=${clientId}&display=modal`,
      `/o/${orgId}/register`,
      `/o/${orgId}/register?clientId=${clientId}`,
      `/o/${orgId}/forgot-password`,
      `/o/${orgId}/forgot-password?clientId=${clientId}`,
      `/o/${orgId}/register/pending-verification?email=a%40b.test&clientId=${clientId}`,
      `/o/${orgId}/register/pending-approval?email=a%40b.test&clientId=${clientId}`,
    ];
    record({ page: "-", variant: "-", kind: "inventory", detail: `${operatorPages.length} operator pages, ${publicPages.length} public pages` });

    let total = 0;
    for (const variant of VARIANTS) {
      const operator = await newContext(browser, variant, base, ".auth/operator.json");
      const anonymous = await newContext(browser, variant, base);
      const operatorPage = await operator.newPage();
      const anonymousPage = await anonymous.newPage();
      for (const url of operatorPages) {
        for (const f of await inspect(operatorPage, url, variant, true, new URL(base).origin)) record(f), total++;
      }
      for (const url of publicPages) {
        for (const f of await inspect(anonymousPage, url, variant, false, new URL(base).origin)) record(f), total++;
      }
      await operator.close();
      await anonymous.close();
    }

    const findings = fs
      .readFileSync(OUT, "utf8")
      .trim()
      .split("\n")
      .map((l) => JSON.parse(l) as Finding);
    // eslint-disable-next-line no-console
    console.log(`sweep: ${operatorPages.length + publicPages.length} pages x ${VARIANTS.length} variants, ${total} findings -> ${OUT}`);

    // Never acceptable, in any variant: a server error, or a script that threw.
    const hard = findings.filter((f) => f.kind === "http-5xx" || f.kind === "script-error");
    expect(hard, "server errors and uncaught script errors").toEqual([]);
  });
});
