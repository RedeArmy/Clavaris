import { expect, test } from "../fixtures";
import type { Page } from "@playwright/test";

// What an operator does to an Organization from the dashboard, in a real browser: create it, edit its
// profile from its card (name, application name, description, brand colour, logo) and see that edit
// reach the pages the application's own people see. Each test also fails on any CSP violation, script
// error or failed request (fixtures.ts).

// A real 1x1 PNG: the server checks an upload's bytes, not just its declared type.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

async function createOrganization(page: Page, name: string): Promise<string> {
  await page.goto("/platform/dashboard");
  await page.locator('[data-dialog-open="create-organization-dialog"]').click();
  const dialog = page.locator("#create-organization-dialog");
  await expect(dialog).toBeVisible();
  await dialog.locator('input[name="name"]').fill(name);
  await dialog.getByRole("button", { name: "Create organization", exact: true }).click();
  return organizationId(page, name);
}

// The id of the Organization whose edit dialog carries `name`.
async function organizationId(page: Page, name: string): Promise<string> {
  await page.goto("/platform/dashboard");
  const buttons = page.locator('[data-dialog-open^="edit-organization-"]');
  for (let i = 0; i < (await buttons.count()); i++) {
    const dialogId = (await buttons.nth(i).getAttribute("data-dialog-open")) as string;
    const value = await page.locator(`#${dialogId} input[name="name"]`).inputValue();
    if (value === name) return dialogId.replace("edit-organization-", "");
  }
  throw new Error(`no Organization named ${name} on the dashboard`);
}

const dialogOf = (page: Page, id: string) => page.locator(`#edit-organization-${id}`);

async function openEdit(page: Page, id: string) {
  await page.goto("/platform/dashboard");
  await page.locator(`[data-dialog-open="edit-organization-${id}"]`).click();
  await expect(dialogOf(page, id)).toBeVisible();
}

test.describe("organization profile", () => {
  test.describe.configure({ mode: "serial" });
  const name = `E2E Profile ${Date.now()}`;
  let id = "";

  test("an operator creates an Organization from the dashboard", async ({ watched: page }) => {
    id = await createOrganization(page, name);
    await expect(page.getByText(name).first()).toBeVisible();
    // The card shows no sentence nobody wrote: no description, no text at all.
    await expect(page.locator("article, li, div").filter({ hasText: "Isolated tenant with its own" })).toHaveCount(0);
  });

  test("the edit dialog opens, shows the current values, and Cancel discards what was typed", async ({
    watched: page,
  }) => {
    await openEdit(page, id);
    const dialog = dialogOf(page, id);
    await expect(dialog.locator('input[name="name"]')).toHaveValue(name);

    await dialog.locator('input[name="applicationName"]').fill("TYPED AND CANCELLED");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();

    await page.locator(`[data-dialog-open="edit-organization-${id}"]`).click();
    await expect(dialog.locator('input[name="applicationName"]')).toHaveValue("");
  });

  test("Escape closes the dialog and focus goes back to the button that opened it", async ({ watched: page }) => {
    await openEdit(page, id);
    await page.keyboard.press("Escape");
    await expect(dialogOf(page, id)).toBeHidden();
    await expect(page.locator(`[data-dialog-open="edit-organization-${id}"]`)).toBeFocused();
  });

  test("a description that is too long is refused next to its field, with the dialog reopened", async ({
    watched: page,
  }) => {
    await openEdit(page, id);
    const dialog = dialogOf(page, id);
    // The browser stops a person at 500; lift that so the check is the server's own.
    await dialog.locator('textarea[name="description"]').evaluate((t) => t.removeAttribute("maxlength"));
    await dialog.locator('textarea[name="description"]').fill("d".repeat(501));
    await dialog.getByRole("button", { name: "Save changes" }).click();

    await expect(dialogOf(page, id)).toBeVisible();
    await expect(dialog.locator(".clavaris-field-error:not([hidden])")).toContainText(/500/);
  });

  test("a file that is not an image, and one that is too big, are refused in the browser before upload", async ({
    watched: page,
  }) => {
    await openEdit(page, id);
    const dialog = dialogOf(page, id);
    const file = dialog.locator('input[name="logo"]');

    await file.setInputFiles({ name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
    await expect(dialog.locator("[data-logo-error]")).toBeVisible();

    await file.setInputFiles({ name: "big.png", mimeType: "image/png", buffer: Buffer.alloc(1024 * 1024 + 1, 1) });
    await expect(dialog.locator("[data-logo-error]")).toBeVisible();
  });

  test("saving the profile updates the card, and reaches the application's own sign-in page", async ({
    watched: page,
    request,
  }) => {
    await openEdit(page, id);
    const dialog = dialogOf(page, id);
    await dialog.locator('input[name="name"]').fill(`${name} Inc`);
    await dialog.locator('input[name="applicationName"]').fill("Acme Portal");
    await dialog.locator('textarea[name="description"]').fill("Analytics for teams");
    await dialog.locator('input[name="brandColor"]').fill("#e11d48");
    await dialog.locator('input[name="logo"]').setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: PNG });
    // The chosen file previews before it is saved.
    await expect(dialog.locator("[data-logo-preview]")).toBeVisible();
    await dialog.getByRole("button", { name: "Save changes" }).click();

    await expect(page).toHaveURL(/\/platform\/dashboard/);
    await expect(page.getByText("Analytics for teams").first()).toBeVisible();
    await expect(page.getByText(`${name} Inc`).first()).toBeVisible();

    // The application's own people see the name, the colour and the logo, and nothing of Clavaris.
    const login = await (await request.get(`/o/${id}/login`)).text();
    expect(login).toContain("Acme Portal");
    expect(login).toMatch(/branding\/theme\.css/);
    expect(login).toMatch(/branding\/logo\?v=\d+/);
    expect(login).not.toMatch(/<title>[^<]*Clavaris/);
    const theme = await (await request.get(`/o/${id}/branding/theme.css`)).text();
    expect(theme).toContain("#e11d48");
    const logo = await request.get(`/o/${id}/branding/logo`);
    expect(logo.status()).toBe(200);
    expect(logo.headers()["content-type"]).toBe("image/png");
    expect(logo.headers()["x-content-type-options"]).toBe("nosniff");
  });

  test("removing the logo and the colour takes them off the application's pages", async ({ watched: page, request }) => {
    await openEdit(page, id);
    const dialog = dialogOf(page, id);
    await dialog.locator("[data-logo-remove]").check({ force: true });
    await dialog.locator('input[name="brandColor"]').fill("");
    await dialog.getByRole("button", { name: "Save changes" }).click();
    await expect(page).toHaveURL(/\/platform\/dashboard/);

    expect((await request.get(`/o/${id}/branding/logo?x=${Date.now()}`, { headers: { "cache-control": "no-cache" } })).status()).toBe(404);
    const login = await (await request.get(`/o/${id}/login`)).text();
    expect(login).not.toMatch(/branding\/theme\.css/);
    expect(login).not.toMatch(/branding\/logo/);
  });

  test("someone else's Organization cannot be edited through the dialog's address", async ({ watched: page }) => {
    // A real form post (with the page's own CSRF token) for an Organization this account does not own.
    await page.goto("/platform/dashboard");
    const csrf = await page.locator('input[name="_csrf"]').first().getAttribute("value");
    const response = await page.request.post(
      `/platform/dashboard/organizations/00000000-0000-0000-0000-000000000000/profile`,
      { multipart: { _csrf: csrf as string, name: "x" }, maxRedirects: 0 },
    );
    expect([403, 404]).toContain(response.status());
  });
});
