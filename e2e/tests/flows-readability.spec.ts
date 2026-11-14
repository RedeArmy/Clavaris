import AxeBuilder from "@axe-core/playwright";
import { expect, operator, signInAsOperator, test } from "../fixtures";

// Two things the full-frontend sweep found in the operator dashboard and that a person reads every
// day: the sessions list, and the phone field's country names in Spanish. Each test also fails on any
// CSP violation, script error or failed request (fixtures.ts).

test.describe("the sessions list", () => {
  test("the current device and the other rows read alike, and every colour in it passes contrast", async ({
    watched: page,
    browser,
    baseURL,
  }) => {
    // A second sign-in of the same account is a second row. It matters because the two kinds of row
    // are different markup: the current one carries a "This device" badge beside the label, the
    // others hold the label alone. A rule written for the old marker once painted the lone label of
    // every other row in the brand colour and the badge itself in it, 2.05:1 on the badge's green.
    // An empty storage state, explicitly: a context made from the browser fixture inherits the
    // project's saved operator session, which would be the same session and not a second one.
    const elsewhere = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
    await signInAsOperator(await elsewhere.newPage());

    await page.goto("/platform/account/sessions");
    await expect(page.locator(".clavaris-session-list tbody tr").nth(1)).toBeVisible();
    await expect(page.locator(".clavaris-badge--success")).toHaveCount(1);

    const labelColours = await page
      .locator(".clavaris-session-list tbody tr td:first-child > span:first-child")
      .evaluateAll((labels) => labels.map((label) => getComputedStyle(label).color));
    expect(new Set(labelColours).size, `device labels in ${labelColours.join(" and ")}`).toBe(1);

    const contrast = await new AxeBuilder({ page })
      .include(".clavaris-session-list")
      .withRules(["color-contrast"])
      .analyze();
    expect(contrast.violations.flatMap((v) => v.nodes.map((n) => n.html))).toEqual([]);

    await elsewhere.close();
  });
});

test.describe("the phone field in Spanish", () => {
  test.use({ locale: "es-ES" });

  test("the country picker names the countries in Spanish, not in the server's English", async ({ watched: page }) => {
    // The <option> text the server sends is English and stays so: phone-country-picker.js hides that
    // <select> and lists the names itself in the page's language (Intl.DisplayNames). So the check is
    // on what the picker draws.
    const who = operator();
    await page.goto(`/platform/dashboard/organizations/${who.orgId}/users`);
    await page.locator('[data-dialog-open="create-user-dialog"]').click();

    const names = page.locator("#create-user-dialog .clavaris-phone-code__name");
    await expect(names.filter({ hasText: /^Alemania$/ })).toHaveCount(1);
    await expect(names.filter({ hasText: /^Estados Unidos$/ })).toHaveCount(1);
    await expect(names.filter({ hasText: /^(Germany|United States)$/ })).toHaveCount(0);
  });
});
