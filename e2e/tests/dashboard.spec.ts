import { expect, operator, test } from "../fixtures";

// The operator dashboard is server-rendered HTML progressively enhanced by htmx and a handful of
// small scripts, under DASHBOARD_PAGE_POLICY. Each test also fails on any CSP violation, script
// error or failed request (fixtures.ts).

test.describe("operator dashboard", () => {
  test("signs in and lists the operator's Organization with its scripts running", async ({
    watched: page,
  }) => {
    const who = operator();
    await page.goto("/platform/dashboard");

    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/your organizations/i);
    await expect(page.getByText(who.orgName).first()).toBeVisible();
    // htmx is the script every in-place update depends on; a policy that refuses it leaves nothing
    // else wrong on the page, so this is the check that notices.
    expect(await page.evaluate(() => typeof (window as unknown as { htmx?: unknown }).htmx)).toBe(
      "object",
    );
  });

  test("creating a Workspace swaps the list in place, without leaving the page", async ({
    watched: page,
  }) => {
    const who = operator();
    const name = `E2E workspace ${Date.now()}`;
    await page.goto(`/platform/dashboard/organizations/${who.orgId}`);
    const here = page.url();

    await page.getByRole("button", { name: "New workspace" }).click();
    const dialog = page.locator("#create-workspace-dialog");
    await expect(dialog).toBeVisible();
    await dialog.locator('input[name="name"]').fill(name);
    await dialog.getByRole("button", { name: "Create workspace" }).click();

    await expect(page.getByRole("cell", { name })).toBeVisible();
    expect(page.url(), "htmx updated the fragment; the page did not navigate").toBe(here);
  });

  // TD-FUT-051: these two pages sit in the dashboard shell but were served `script-src 'none'`,
  // so every script on them was refused.
  for (const path of ["/platform/account", "/platform/account/sessions"]) {
    test(`${path} loads the shell's scripts`, async ({ watched: page }) => {
  
      await page.goto(path);

      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      expect(
        await page.evaluate(() => typeof (window as unknown as { htmx?: unknown }).htmx),
        `htmx on ${path}`,
      ).toBe("object");
    });
  }

  // TD-ARCH-038: the Organization's log reads by the tenant key the audit table now carries. A
  // Workspace created through the dashboard must show up, with the person who did it.
  test("the audit log lists what was just done in the Organization", async ({ watched: page }) => {
    const who = operator();
    const name = `E2E audited ${Date.now()}`;
    await page.goto(`/platform/dashboard/organizations/${who.orgId}`);
    await page.getByRole("button", { name: "New workspace" }).click();
    const dialog = page.locator("#create-workspace-dialog");
    await dialog.locator('input[name="name"]').fill(name);
    await dialog.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByRole("cell", { name })).toBeVisible();

    await page.goto(`/platform/dashboard/organizations/${who.orgId}/audit-log`);

    const row = page.locator("tr", { hasText: name });
    await expect(row).toBeVisible();
    await expect(row).toContainText("Workspace created");
    await expect(row).toContainText("You");
  });
});
