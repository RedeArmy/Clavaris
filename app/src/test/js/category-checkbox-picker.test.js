"use strict";

/**
 * category-checkbox-picker.js, extracted 2026-09-27 to close a real SonarCloud duplication
 * finding between scope-picker.js and event-type-picker.js (both wire an identical parent/child
 * category-checkbox shape over differently-scoped DOM trees). Same hand-rolled `document`/
 * `window` stub convention as embedded-login-popup.test.js/organization-dialog.test.js — no
 * jsdom, `node:test` only. The script assigns to `window.ClavarisCategoryPicker` (a plain global,
 * no bundler in this codebase's static JS), so `global.window` must be stubbed before requiring
 * it, exactly as `global.document` is.
 */

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(
  __dirname,
  "..",
  "..",
  "main",
  "resources",
  "static",
  "js",
  "category-checkbox-picker.js",
);

function loadScript(selectors) {
  global.window = {};
  global.document = {
    querySelector(selector) {
      return Object.prototype.hasOwnProperty.call(selectors.one ?? {}, selector)
        ? selectors.one[selector]
        : null;
    },
    querySelectorAll(selector) {
      return Object.prototype.hasOwnProperty.call(selectors.all ?? {}, selector)
        ? selectors.all[selector]
        : [];
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return global.window.ClavarisCategoryPicker;
}

test("marks the category checkbox checked when every child is checked", () => {
  const categoryCheckbox = { checked: false, indeterminate: false };
  const picker = loadScript({
    one: {
      '#scope-options .clavaris-scope-category-checkbox[data-category="billing"]':
        categoryCheckbox,
    },
    all: {
      '#scope-options .clavaris-event-option input[data-category="billing"]': [
        { checked: true },
        { checked: true },
      ],
    },
  });

  picker.updateCategoryCheckboxState("scope-options", "clavaris-scope-category-checkbox", {
    dataset: { category: "billing" },
  });

  assert.equal(categoryCheckbox.checked, true);
  assert.equal(categoryCheckbox.indeterminate, false);
});

test("marks the category checkbox indeterminate when some but not all children are checked", () => {
  const categoryCheckbox = { checked: true, indeterminate: false };
  const picker = loadScript({
    one: {
      '#scope-options .clavaris-scope-category-checkbox[data-category="billing"]':
        categoryCheckbox,
    },
    all: {
      '#scope-options .clavaris-event-option input[data-category="billing"]': [
        { checked: true },
        { checked: false },
      ],
    },
  });

  picker.updateCategoryCheckboxState("scope-options", "clavaris-scope-category-checkbox", {
    dataset: { category: "billing" },
  });

  assert.equal(categoryCheckbox.checked, false);
  assert.equal(categoryCheckbox.indeterminate, true);
});

test("does nothing when there is no matching category checkbox", () => {
  const picker = loadScript({});

  assert.doesNotThrow(() =>
    picker.updateCategoryCheckboxState("scope-options", "clavaris-scope-category-checkbox", {
      dataset: { category: "billing" },
    }),
  );
});

test("initializes every category checkbox found from its own first child's checked state", () => {
  const billingCategoryCheckbox = { checked: false, indeterminate: false, dataset: { category: "billing" } };
  const usersCategoryCheckbox = { checked: false, indeterminate: false, dataset: { category: "users" } };
  const billingFirstChild = { checked: true, dataset: { category: "billing" } };
  const usersFirstChild = { checked: true, dataset: { category: "users" } };

  const picker = loadScript({
    all: {
      "#scope-options .clavaris-scope-category-checkbox": [
        billingCategoryCheckbox,
        usersCategoryCheckbox,
      ],
      '#scope-options .clavaris-event-option input[data-category="billing"]': [billingFirstChild],
      '#scope-options .clavaris-event-option input[data-category="users"]': [usersFirstChild],
    },
    one: {
      '#scope-options .clavaris-event-option input[data-category="billing"]': billingFirstChild,
      '#scope-options .clavaris-event-option input[data-category="users"]': usersFirstChild,
      '#scope-options .clavaris-scope-category-checkbox[data-category="billing"]':
        billingCategoryCheckbox,
      '#scope-options .clavaris-scope-category-checkbox[data-category="users"]':
        usersCategoryCheckbox,
    },
  });

  picker.initializeCategoryCheckboxes("scope-options", "clavaris-scope-category-checkbox");

  assert.equal(billingCategoryCheckbox.checked, true);
  assert.equal(usersCategoryCheckbox.checked, true);
});

test("skips a category checkbox with no matching child in the DOM", () => {
  const categoryCheckbox = { checked: false, indeterminate: false, dataset: { category: "billing" } };

  const picker = loadScript({
    all: {
      "#scope-options .clavaris-scope-category-checkbox": [categoryCheckbox],
    },
  });

  assert.doesNotThrow(() =>
    picker.initializeCategoryCheckboxes("scope-options", "clavaris-scope-category-checkbox"),
  );
  assert.equal(categoryCheckbox.checked, false);
});
