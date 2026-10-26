import { expect, signInAsOperator, test } from "../fixtures";

// Runs once before the dashboard tests: sign in and keep the session (cookies) for them.
test("sign in as the seeded operator", async ({ watched: page }) => {
  await signInAsOperator(page);
  await expect(page).toHaveURL(/\/platform\/dashboard$/);
  await page.context().storageState({ path: ".auth/operator.json" });
});
