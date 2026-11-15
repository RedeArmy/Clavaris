import { expect, operator, test } from "../fixtures";
import type { Page } from "@playwright/test";

// Configure > Connections: Google and GitHub sign-in per Organization. A development Organization
// uses Clavaris's shared apps; a production one signs in only through its own credentials. Each test
// also fails on any CSP violation, script error or failed same-origin request (fixtures.ts).

const connections = (id: string) => `/platform/dashboard/organizations/${id}/connections`;

// The switches hide their checkbox and draw a track beside it: a person presses the track, so the test does.
async function setSwitch(page: Page, input: string, on: boolean) {
  if ((await page.locator(input).isChecked()) !== on) {
    await page.locator(`${input} + .clavaris-toggle__track`).click();
  }
  await expect(page.locator(input)).toBeChecked({ checked: on });
}

// Pressing "Google" ends at Google's own page. The test never goes there: it follows the redirects
// that stay inside Clavaris and returns the address Clavaris sends the person to.
async function providerAddress(page: Page, loginPath: string): Promise<string> {
  await page.goto(loginPath);
  let next = (await page.getByRole("link", { name: /Google/ }).getAttribute("href")) as string;
  for (let hop = 0; hop < 6; hop++) {
    const response = await page.request.get(new URL(next, page.url()).toString(), { maxRedirects: 0 });
    const location = response.headers()["location"];
    if (!location) throw new Error(`no redirect from ${next} (status ${response.status()})`);
    if (/accounts\.google\.com/.test(location)) return location;
    next = location;
  }
  throw new Error("never reached the provider");
}

test.describe("connections: development Organization", () => {
  test.describe.configure({ mode: "serial" });

  test("the list shows both providers and says a development Organization uses the shared apps", async ({
    watched: page,
  }) => {
    const who = operator();
    await page.goto(connections(who.orgId));
    await expect(page.getByRole("heading", { level: 1, name: "Connections" })).toBeVisible();
    await expect(page.getByRole("row", { name: /Google/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /GitHub/ })).toBeVisible();
    await expect(page.locator(".clavaris-alert--warning")).toContainText(/shared Google and GitHub apps/);
    // The sidebar marks Connections as the current page.
    await expect(page.locator('.clavaris-configure-sidebar a[aria-current="page"]')).toHaveText("Connections");
  });

  test("a provider's page has no credential fields in development, and links to promote", async ({ watched: page }) => {
    const who = operator();
    await page.goto(`${connections(who.orgId)}/google`);
    await expect(page.locator("#connection-client-id")).toHaveCount(0);
    await expect(page.locator("#connection-client-secret")).toHaveCount(0);
    await expect(page.getByRole("link", { name: /Promote to production|Set up this connection in the production/ })).toBeVisible();
  });

  test("enabling Google offers it on the sign-in page and sends the person to Google with the account chooser", async ({
    watched: page,
    browser,
    baseURL,
  }) => {
    const who = operator();
    await page.goto(`${connections(who.orgId)}/google`);
    await setSwitch(page, "#connection-enabled", true);
    await setSwitch(page, "#connection-block-subaddresses", true);
    await setSwitch(page, "#connection-account-selector", true);
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.locator("output.clavaris-alert--success")).toContainText("Connection saved");
    await expect(page.locator("h1 .clavaris-badge")).toHaveText("Enabled");
    await expect(page.locator("#connection-block-subaddresses")).toBeChecked();

    // A person on the application's own sign-in page now sees Google, and Google's own screen is
    // asked to show the account chooser.
    const consumer = await browser.newContext({ baseURL });
    const visitor = await consumer.newPage();
    expect(await providerAddress(visitor, `/o/${who.orgId}/login`)).toContain("prompt=select_account");
    await consumer.close();
  });

  test("switching Google off takes it off the sign-in page", async ({ watched: page, request }) => {
    const who = operator();
    await page.goto(`${connections(who.orgId)}/google`);
    await setSwitch(page, "#connection-enabled", false);
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator("h1 .clavaris-badge")).toHaveText("Disabled");
    const login = await (await request.get(`/o/${who.orgId}/login`)).text();
    expect(login).not.toContain("login/social/google");
  });

  test("a provider that does not exist is not a page", async ({ watched: page }) => {
    const who = operator();
    const response = await page.request.get(`${connections(who.orgId)}/facebook`);
    expect(response.status()).toBe(404);
  });
});

test.describe("connections: production Organization", () => {
  test.describe.configure({ mode: "serial" });
  let productionId = "";

  test("an operator promotes an Organization to production", async ({ watched: page }) => {
    const name = `E2E Prod ${Date.now()}`;
    await page.goto("/platform/dashboard");
    await page.locator('[data-dialog-open="create-organization-dialog"]').click();
    const dialog = page.locator("#create-organization-dialog");
    await dialog.locator('input[name="name"]').fill(name);
    await dialog.getByRole("button", { name: "Create organization", exact: true }).click();
    await page.goto("/platform/dashboard");
    const developmentId = (
      await page.locator(`[data-dialog-open^="edit-organization-"]`).evaluateAll((buttons, wanted) => {
        for (const b of buttons) {
          const d = document.getElementById(b.getAttribute("data-dialog-open") as string);
          if ((d?.querySelector('input[name="name"]') as HTMLInputElement | null)?.value === wanted) {
            return (b.getAttribute("data-dialog-open") as string).replace("edit-organization-", "");
          }
        }
        return "";
      }, name)
    ) as string;
    expect(developmentId).not.toBe("");

    await page.goto(`/platform/dashboard/organizations/${developmentId}/promote-to-production`);
    await page.locator('input[name="name"]').fill(`${name} (Production)`);
    await page.getByRole("button", { name: /promote|create/i }).first().click();
    await page.waitForURL(/\/platform\/dashboard\/organizations\/[0-9a-f-]{36}$/);
    productionId = page.url().split("/").pop() as string;
    expect(productionId).not.toBe(developmentId);
  });

  test("it cannot enable a provider without credentials of its own, and says why", async ({ watched: page }) => {
    await page.goto(`${connections(productionId)}/google`);
    await expect(page.locator("#connection-client-id")).toBeVisible();
    await expect(page.locator("#connection-redirect-uri")).toContainText("/login/oauth2/code/google");
    await setSwitch(page, "#connection-enabled", true);
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator(".clavaris-field-error").first()).toContainText(/client ID and client secret/i);
  });

  test("half a credential is refused", async ({ watched: page }) => {
    await page.goto(`${connections(productionId)}/google`);
    await page.locator("#connection-client-id").fill("only-an-id");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator(".clavaris-field-error").first()).toContainText(/both/i);
  });

  test("with credentials it is enabled, the secret is never shown again, and sign-in uses its own app", async ({
    watched: page,
    browser,
    baseURL,
  }) => {
    await page.goto(`${connections(productionId)}/google`);
    await page.locator("#connection-client-id").fill("prod-client-123");
    await page.locator("#connection-client-secret").fill("a-secret-that-must-never-come-back");
    await setSwitch(page, "#connection-enabled", true);
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator("h1 .clavaris-badge")).toHaveText("Enabled");
    await expect(page.locator("#connection-client-id")).toHaveValue("prod-client-123");
    await expect(page.locator("#connection-client-secret")).toHaveValue("");
    expect(await page.content()).not.toContain("a-secret-that-must-never-come-back");

    const consumer = await browser.newContext({ baseURL });
    const visitor = await consumer.newPage();
    expect(await providerAddress(visitor, `/o/${productionId}/login`)).toContain("client_id=prod-client-123");
    await consumer.close();
  });

  test("removing the credentials asks first, then switches the provider off", async ({ watched: page, request }) => {
    await page.goto(`${connections(productionId)}/google`);
    await page.getByRole("button", { name: "Remove credentials" }).first().click();
    // The confirmation is a modal; cancelling leaves everything as it was.
    const modal = page.locator("dialog[open]");
    await expect(modal).toBeVisible();
    await modal.locator("[data-confirm-cancel]").click();
    await expect(page.locator("h1 .clavaris-badge")).toHaveText("Enabled");

    await page.getByRole("button", { name: "Remove credentials" }).first().click();
    await page.locator("dialog[open] [data-confirm-accept]").click();
    await expect(page.locator("output.clavaris-alert--success")).toContainText(/removed/i);
    await expect(page.locator("h1 .clavaris-badge")).toHaveText("Disabled");
    const login = await (await request.get(`/o/${productionId}/login`)).text();
    expect(login).not.toContain("login/social/google");
  });
});
