import { authorizeUrl, expect, seeded, test } from "../fixtures";

// The pages the consuming application's own people use: sign-in, sign-up and forgot-password. They
// must read as the application's own (nothing of Clavaris), always offer a way back, and never be a
// dead end. Each test also fails on any CSP violation, script error or failed request (fixtures.ts).

test.describe("consumer pages", () => {
  test("sign-in for a client shows a way back to the application, and it leads there", async ({
    watched: page,
    baseURL,
  }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}`);

    const home = page.getByRole("link", { name: "Back to home" });
    await expect(home).toBeVisible();
    // The address is where the client registered its redirect, never something the request carried.
    await expect(home).toHaveAttribute("href", `${baseURL}/`);
    await home.click();
    await expect(page).toHaveURL(`${baseURL}/`);
  });

  test("there is no way back with no client, and none inside the application's own frame", async ({
    watched: page,
  }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login`);
    await expect(page.getByRole("link", { name: "Back to home" })).toHaveCount(0);

    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}&display=modal`);
    await expect(page.getByRole("link", { name: "Back to home" })).toHaveCount(0);
    await page.goto(`/o/${s.orgId}/register?clientId=${s.clientId}&display=modal`);
    await expect(page.getByRole("link", { name: "Back to home" })).toHaveCount(0);
  });

  test("keyboard: the way back is reached first, then the email field", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}`);
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Back to home" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(page.getByLabel("Email")).toBeFocused();
  });

  test("the sign-in page is the application's own: no Clavaris in its title or text", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}`);
    expect(await page.title()).not.toMatch(/clavaris/i);
    expect(await page.locator("main").innerText()).not.toMatch(/clavaris/i);
  });

  test("a wrong password keeps the way back on the page", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}`);
    await page.getByLabel("Email").fill(s.email);
    await page.getByLabel("Password").fill("not-the-password-1A!");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.locator(".clavaris-alert--error").first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });

  test("sign-up: a bad address and mismatched passwords are refused next to their fields", async ({
    watched: page,
  }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/register?clientId=${s.clientId}`);
    await page.getByLabel("Email").fill("not-an-email");
    await page.getByLabel("Password", { exact: true }).fill("Valid-passw0rd-1");
    await page.getByLabel("Confirm password").fill("Different-passw0rd-2");
    // The browser's own validation may stop the submit; submitting the form directly checks the server.
    await page.locator("form").evaluate((f) => (f as HTMLFormElement).noValidate = true);
    await page.getByRole("button", { name: /create|sign up|register/i }).click();
    await expect(page.locator(".clavaris-field-error").first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });

  test("sign-up: a new account ends on a page that says where the email went, with a way back and on to sign-in", async ({
    watched: page,
  }) => {
    const s = seeded();
    const email = `e2e-new-${Date.now()}+clavaris_test@example.test`;
    await page.goto(`/o/${s.orgId}/register?clientId=${s.clientId}`);
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill("Valid-passw0rd-1");
    await page.getByLabel("Confirm password").fill("Valid-passw0rd-1");
    await page.getByRole("button", { name: /create|sign up|register/i }).click();

    await expect(page).toHaveURL(/pending-verification/);
    await expect(page.getByText(email)).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
    await page.getByRole("link", { name: "sign in" }).click();
    await expect(page).toHaveURL(new RegExp(`/o/${s.orgId}/login.*clientId=${s.clientId}`));
  });

  test("forgot password: the answer is the same for any address, and the page is not a dead end", async ({
    watched: page,
  }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/forgot-password?clientId=${s.clientId}`);
    await page.getByLabel("Email").fill("nobody-here@example.test");
    await page.getByRole("button", { name: /send|reset|continue/i }).click();
    await expect(page).toHaveURL(/pending|forgot-password/);
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });

  test("the passkey button appears, which shows the page script was allowed to run", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(await authorizeUrl(s.orgId, s.clientId, s.redirectUri));
    // The page reveals the button only where the browser has the WebAuthn JSON methods (Chrome 129,
    // Firefox 119, Safari 18.4); the WebKit build Playwright ships does not, and hiding it there is the
    // page doing its job.
    const hasJsonMethods = await page.evaluate(
      () => typeof (window.PublicKeyCredential as unknown as { parseRequestOptionsFromJSON?: unknown })?.parseRequestOptionsFromJSON === "function",
    );
    test.skip(!hasJsonMethods, "this browser build has no WebAuthn JSON methods, so the passkey button stays hidden");
    await expect(page.locator("#passkey-signin-button")).toBeVisible();
  });
});

test.describe("consumer pages in Spanish", () => {
  test.use({ locale: "es-ES" });

  test("the sign-in page is in Spanish, with the way back named in Spanish", async ({ watched: page }) => {
    const s = seeded();
    await page.goto(`/o/${s.orgId}/login?clientId=${s.clientId}`);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/Inicia sesión/);
    await expect(page.getByRole("link", { name: "Volver al inicio" })).toBeVisible();
    expect(await page.locator("main").innerText()).not.toMatch(/clavaris/i);
    await expect(page.locator("html")).toHaveAttribute("lang", /^es/);
  });
});

test.describe("consumer pages on a phone", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test("sign-in and sign-up fit the screen, with the way back clear of the content", async ({ watched: page }) => {
    const s = seeded();
    for (const path of ["login", "register"]) {
      await page.goto(`/o/${s.orgId}/${path}?clientId=${s.clientId}`);
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `${path} width`).toBeLessThanOrEqual(375);
      const home = (await page.getByRole("link", { name: "Back to home" }).boundingBox())!;
      const firstContent = (await page.locator("main .clavaris-card h1, main .clavaris-card .clavaris-app-header").first().boundingBox())!;
      expect(home.y + home.height, `${path}: the icon ends above the content`).toBeLessThanOrEqual(firstContent.y + 1);
      expect(home.width, "touch target").toBeGreaterThanOrEqual(24);
    }
  });
});
