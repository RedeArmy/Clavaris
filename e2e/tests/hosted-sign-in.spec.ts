import { authorizeUrl, expect, seeded, test } from "../fixtures";

// The hosted sign-in page is what every consuming application sends its users to, and the page with
// the strictest policy (LOGIN_PAGE_POLICY). Each test also fails on any CSP violation, script error
// or failed request the page produces (see fixtures.ts).

test.describe("hosted sign-in", () => {
  test("renders styled and its script runs under the policy", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(await authorizeUrl(s.orgId, s.clientId, s.redirectUri));

    await expect(page).toHaveURL(new RegExp(`/o/${s.orgId}/login`));
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Sign in to");
    // The stylesheet applied: an unstyled <button> has a transparent background.
    await expect(page.getByRole("button", { name: "Sign in", exact: true })).not.toHaveCSS(
      "background-color",
      "rgba(0, 0, 0, 0)",
    );
    // webauthn-login.js removes `hidden` from the passkey button when the browser supports it, so
    // a visible button proves the script was allowed to run and its handlers attached.
    await expect(page.locator("#passkey-signin-button")).toBeVisible();
  });

  test("a person signs in and is sent back to the client with an authorization code", async ({
    watched: page,
  }) => {
    const s = seeded();
    await page.route(`${s.redirectUri}**`, (route) => route.fulfill({ status: 200, body: "callback" }));
    await page.goto(await authorizeUrl(s.orgId, s.clientId, s.redirectUri));

    await page.getByLabel("Email").fill(s.email);
    await page.getByLabel("Password").fill(s.password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();

    await page.waitForURL(`${s.redirectUri}**`);
    const returned = new URL(page.url());
    expect(returned.searchParams.get("code"), "authorization code").toBeTruthy();
    expect(returned.searchParams.get("state")).toBe("e2e-state");
  });

  test("a wrong password shows an error and leaves the form usable", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(await authorizeUrl(s.orgId, s.clientId, s.redirectUri));

    await page.getByLabel("Email").fill(s.email);
    await page.getByLabel("Password").fill("not-the-password-1A!");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();

    await expect(page.locator(".clavaris-alert--error").first()).toBeVisible();
    // login-submit-guard.js disables the button while a submit is in flight; it must come back.
    await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeEnabled();
  });
});
