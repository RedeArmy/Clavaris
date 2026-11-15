import { authorizeUrl, expect, seeded, test } from "../fixtures";

// The consent screen: what an application asks for, and the two answers a person can give. Needs a
// client that requires consent, which the seed does not create, so it makes one through the
// management API with the platform bootstrap credentials (the ones the instance was started with).

const bootstrapId = process.env.PLATFORM_BOOTSTRAP_CLIENT_ID;
const bootstrapSecret = process.env.PLATFORM_BOOTSTRAP_CLIENT_SECRET;

test.describe("consent screen", () => {
  test.skip(!bootstrapId || !bootstrapSecret, "needs PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET");
  test.describe.configure({ mode: "serial" });
  let consentClientId = "";

  test.beforeAll(async ({ playwright, baseURL }) => {
    const api = await playwright.request.newContext({ baseURL });
    const token = await api.post("/oauth2/token", {
      // Form-encoded before the base64 (RFC 6749 2.3.1): a secret with "+" or "=" is read back decoded.
      headers: {
        Authorization:
          "Basic " + Buffer.from(`${encodeURIComponent(bootstrapId!)}:${encodeURIComponent(bootstrapSecret!)}`).toString("base64"),
      },
      form: { grant_type: "client_credentials", scope: "platform:organizations:write" },
    });
    expect(token.ok(), "bootstrap token").toBeTruthy();
    const { access_token } = (await token.json()) as { access_token: string };
    const s = seeded();
    const created = await api.post(`/api/v1/admin/organizations/${s.orgId}/clients`, {
      headers: { Authorization: `Bearer ${access_token}` },
      data: {
        redirectUris: [s.redirectUri],
        allowedGrantTypes: ["authorization_code"],
        allowedScopes: ["openid", "profile", "email"],
        requireConsent: true,
      },
    });
    expect(created.ok(), `client created: ${created.status()}`).toBeTruthy();
    consentClientId = ((await created.json()) as { clientId: string }).clientId;
    await api.dispose();
  });

  async function reachConsent(page: import("@playwright/test").Page) {
    const s = seeded();
    await page.route(`${s.redirectUri}**`, (route) => route.fulfill({ status: 200, body: "callback" }));
    const url = (await authorizeUrl(s.orgId, consentClientId, s.redirectUri)).replace("scope=openid", "scope=openid+profile+email");
    await page.goto(url);
    if (/\/login/.test(page.url())) {
      await page.getByLabel("Email").fill(s.email);
      await page.getByLabel("Password").fill(s.password);
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
    }
    await expect(page).toHaveURL(/consent/);
  }

  test("the screen says what the application asks for and offers both answers", async ({ watched: page }) => {
    await reachConsent(page);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/wants to access your account/i);
    await expect(page.getByRole("button", { name: "Approve" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Deny" })).toBeVisible();
    expect(await page.title()).not.toMatch(/clavaris/i);
    // Each requested scope is a switch with a name, none of them left unlabelled.
    for (const box of await page.locator('input[name="scope"]').all()) {
      await expect(box).toHaveAccessibleName(/.+/);
    }
  });

  test("denying sends the application back with access_denied", async ({ watched: page }) => {
    await reachConsent(page);
    await page.getByRole("button", { name: "Deny" }).click();
    await page.waitForURL(/e2e\/callback/);
    expect(new URL(page.url()).searchParams.get("error")).toBe("access_denied");
    expect(new URL(page.url()).searchParams.get("state")).toBe("e2e-state");
  });

  test("approving sends the application back with an authorization code", async ({ watched: page }) => {
    await reachConsent(page);
    await page.getByRole("button", { name: "Approve" }).click();
    await page.waitForURL(/e2e\/callback/);
    expect(new URL(page.url()).searchParams.get("code"), "authorization code").toBeTruthy();
  });
});
