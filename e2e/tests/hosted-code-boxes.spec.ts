import { expect, test } from "@playwright/test";
import path from "node:path";

// The one-time code as six boxes (code-boxes.js, ADR-0034): it must fill the plain field the form
// submits, so the server still sees one `code` value. The script is loaded from the source tree into
// a minimal page, so this needs no running instance; it checks the behaviour people rely on: typing,
// Backspace, paste with separators, a phone filling the whole code into the first box, and sending
// once the sixth character is in.

const SCRIPT = path.resolve(__dirname, "../../app/src/main/resources/static/js/code-boxes.js");

const PAGE = `
  <form id="f" action="javascript:void(0)">
    <label for="code">Code</label>
    <input id="code" name="code" type="text" data-code-boxes="6" required />
    <button type="submit">Verify</button>
  </form>`;

test.describe("code boxes", () => {
  test.beforeEach(async ({ page }) => {
    await page.setContent(PAGE);
    await page.evaluate(() => {
      (window as unknown as { sent: string[] }).sent = [];
      document.getElementById("f")!.addEventListener("submit", (event) => {
        event.preventDefault();
        (window as unknown as { sent: string[] }).sent.push((document.getElementById("code") as HTMLInputElement).value);
      });
    });
    await page.addScriptTag({ path: SCRIPT });
  });

  const field = (page: import("@playwright/test").Page) => page.locator("#code");
  const sent = (page: import("@playwright/test").Page) =>
    page.evaluate(() => (window as unknown as { sent: string[] }).sent);

  test("shows six boxes with the first one focused", async ({ page }) => {
    await expect(page.locator(".clavaris-code-box")).toHaveCount(6);
    await expect(page.locator(".clavaris-code-box").first()).toBeFocused();
  });

  test("typing fills the boxes in capitals and sends once, at the sixth character", async ({ page }) => {
    await page.keyboard.type("k7m2q");
    await expect(field(page)).toHaveValue("K7M2Q");
    expect(await sent(page)).toEqual([]);

    await page.keyboard.type("x");
    await expect(field(page)).toHaveValue("K7M2QX");
    expect(await sent(page)).toEqual(["K7M2QX"]);
  });

  test("Backspace on an empty box goes back and clears the one before", async ({ page }) => {
    await page.keyboard.type("k7m");
    await page.keyboard.press("Backspace");
    await expect(field(page)).toHaveValue("K7");
  });

  test("a paste with spaces and hyphens is spread over the boxes", async ({ page }) => {
    await page.locator(".clavaris-code-box").first().evaluate((box) => {
      const data = new DataTransfer();
      data.setData("text", " k7m-2qx ");
      box.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
    });
    await expect(field(page)).toHaveValue("K7M2QX");
    expect(await sent(page)).toEqual(["K7M2QX"]);
  });

  test("a code filled whole into the first box, as a phone does, is spread over the boxes", async ({ page }) => {
    await page.locator(".clavaris-code-box").first().fill("ab12cd");
    await expect(field(page)).toHaveValue("AB12CD");
  });

  test("anything that is not a letter or a digit is ignored", async ({ page }) => {
    await page.keyboard.type("a!@b");
    await expect(field(page)).toHaveValue("AB");
  });
});
