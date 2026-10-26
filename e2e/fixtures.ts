import { test as base, expect, type Page } from "@playwright/test";

/**
 * A page that fails its test if the browser reports anything the Content-Security-Policy blocked,
 * an uncaught script error, or a failed same-origin request. This is the point of the suite: the
 * policy has no inline script or style allowance, and a script, style or handler it blocks does not
 * fail any server-side test; it just leaves a control that silently does nothing (TD-FUT-034,
 * TD-ARCH-031 and TD-UX-001 were all found that way, live).
 */
export const test = base.extend<{ watched: Page }>({
  watched: async ({ page, baseURL }, use) => {
    const problems: string[] = [];

    await page.addInitScript(() => {
      (window as unknown as { __csp: string[] }).__csp = [];
      document.addEventListener("securitypolicyviolation", (event) => {
        (window as unknown as { __csp: string[] }).__csp.push(
          `${event.violatedDirective} blocked ${event.blockedURI || "inline"}`,
        );
      });
    });
    page.on("pageerror", (error) => problems.push(`script error: ${error.message}`));
    page.on("console", (message) => {
      // Chromium reports a blocked resource as a console error naming the policy.
      if (message.type() === "error" && /Content Security Policy|Refused to/i.test(message.text())) {
        problems.push(`csp: ${message.text()}`);
      }
    });
    page.on("requestfailed", (request) => {
      if (baseURL && request.url().startsWith(baseURL)) {
        problems.push(`request failed: ${request.url()} ${request.failure()?.errorText ?? ""}`);
      }
    });

    await use(page);

    const reported = await page
      .evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? [])
      .catch(() => [] as string[]);
    expect([...problems, ...reported.map((v) => `csp: ${v}`)], "browser problems").toEqual([]);
  },
});

export { expect };

/** What load-testing/setup-pkce-fixture.sh printed, read from the environment. */
export function seeded() {
  const need = (name: string): string => {
    const value = process.env[name];
    if (!value) {
      throw new Error(`${name} is not set: run load-testing/setup-pkce-fixture.sh and export its output`);
    }
    return value;
  };
  return {
    orgId: need("ORG_ID"),
    clientId: need("CLIENT_ID"),
    redirectUri: need("REDIRECT_URI"),
    email: `${need("USER_EMAIL_PREFIX")}1@example.test`,
    password: need("USER_PASSWORD"),
  };
}

/** A PKCE authorization request, so the tests can start where a consuming application would. */
export async function authorizeUrl(orgId: string, clientId: string, redirectUri: string) {
  const { createHash, randomBytes } = await import("node:crypto");
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const query = new URLSearchParams({
    response_type: "code",
    client_id: clientId,
    redirect_uri: redirectUri,
    scope: "openid",
    code_challenge: challenge,
    code_challenge_method: "S256",
    state: "e2e-state",
  });
  return `/o/${orgId}/oauth2/authorize?${query.toString()}`;
}

/** The operator the seed script registered and made owner of the seeded Organization. */
export function operator() {
  const need = (name: string): string => {
    const value = process.env[name];
    if (!value) {
      throw new Error(`${name} is not set: run e2e/seed.sh and export its output`);
    }
    return value;
  };
  return {
    email: need("OPERATOR_EMAIL"),
    password: need("OPERATOR_PASSWORD"),
    orgId: need("ORG_ID"),
    orgName: need("ORG_NAME"),
  };
}

/** Signs the seeded operator in to the dashboard. */
export async function signInAsOperator(page: Page) {
  const who = operator();
  await page.goto("/platform/login");
  await page.getByLabel("Email").fill(who.email);
  await page.getByLabel("Password").fill(who.password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.waitForURL("**/platform/dashboard");
}
