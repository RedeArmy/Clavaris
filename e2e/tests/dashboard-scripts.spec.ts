import { expect, operator, test } from "../fixtures";

// TD-FUT-051: the build minifies the page scripts. A script that parses (the build checks that) can
// still misbehave in a page, and one the policy refuses fails no server-side test. This walks the
// dashboard the way an operator would, following its links, and lets the `watched` fixture fail the
// test on any CSP violation, script error or failed request along the way. It also checks the
// served scripts really are the minified ones.

const MAX_PAGES = 60;

test.describe("operator dashboard scripts", () => {
  test("every script the dashboard pages load runs without a browser problem, and is minified", async ({
    watched: page,
    baseURL,
  }) => {
    const who = operator();
    const scope = `/platform/dashboard/organizations/${who.orgId}`;
    const inScope = (path: string) =>
      (path === "/platform/dashboard" || path.startsWith(scope) || path.startsWith("/platform/account")) &&
      !/logout|delete|impersonat|download|export|\.(csv|json)$/.test(path);

    const scripts = new Map<string, string>();
    page.on("response", (response) => {
      const url = response.url();
      const name = /\/js\/([^/?]+\.js)/.exec(url)?.[1];
      if (name) scripts.set(name.replace(/-[0-9a-f]{32}\.js$/, ".js"), url);
    });

    const seen = new Set<string>();
    const queue = ["/platform/dashboard", scope];
    while (queue.length > 0 && seen.size < MAX_PAGES) {
      const path = (queue.shift() as string).split("#")[0];
      if (seen.has(path) || !inScope(path)) continue;
      seen.add(path);
      const response = await page.goto(path);
      expect(response?.status(), `status of ${path}`).toBeLessThan(400);
      for (const href of await page.$$eval("a[href]", (links) =>
        links.map((link) => link.getAttribute("href") ?? "").filter((h) => h.startsWith("/")),
      )) {
        if (!seen.has(href.split("#")[0])) queue.push(href);
      }
    }

    // Enough of the dashboard was reached for the check to mean something.
    expect(scripts.size, `scripts loaded across ${seen.size} pages`).toBeGreaterThanOrEqual(18);

    for (const [name, url] of scripts) {
      if (name === "htmx.min.js") continue; // already minified upstream, served untouched
      const body = await (await page.request.get(url)).text();
      expect(body, `${name} is the minified script`).not.toContain("\n  ");
      expect(body, `${name} carries no comment block`).not.toMatch(/\/\*[\s\S]*?\*\//);
    }
    expect(baseURL).toBeTruthy();
  });
});
