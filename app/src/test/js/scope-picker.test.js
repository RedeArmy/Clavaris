"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "scope-picker.js");

function checkbox(props = {}) {
  return { checked: false, disabled: false, indeterminate: false, dataset: {}, ...props };
}

// A document whose querySelectorAll answers from a table keyed by selector, and whose
// querySelector returns the first match of that same table.
function load(selectors, byId = {}) {
  const listeners = {};
  const categoryPickerCalls = [];
  global.window = {
    ClavarisCategoryPicker: {
      updateCategoryCheckboxState: (...args) => { categoryPickerCalls.push(["update", ...args]); },
      initializeCategoryCheckboxes: (...args) => { categoryPickerCalls.push(["init", ...args]); },
    },
  };
  global.document = {
    addEventListener: (name, listener) => { listeners[name] = listener; },
    querySelectorAll: (selector) => selectors[selector] || [],
    getElementById: (id) => byId[id] || null,
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { listeners, categoryPickerCalls };
}

function eventFor(target, matching) {
  target.matches = (selector) => matching.includes(selector);
  return { target };
}

const CONTAINER = "scope-options";
const CATEGORY_CLASS = "clavaris-scope-category-checkbox";
const CHILDREN = '#scope-options .clavaris-event-option input[data-category="jobs"]';

test("ticking a category ticks every scope in it and clears its indeterminate state", () => {
  const children = [checkbox(), checkbox()];
  const { listeners } = load({ [CHILDREN]: children });
  const category = checkbox({ checked: true, indeterminate: true, dataset: { category: "jobs" } });
  listeners.change(eventFor(category, ["." + CATEGORY_CLASS]));
  assert.deepEqual(children.map((c) => c.checked), [true, true]);
  assert.equal(category.indeterminate, false);
});

test("unticking a category unticks every scope in it", () => {
  const children = [checkbox({ checked: true }), checkbox({ checked: true })];
  const { listeners } = load({ [CHILDREN]: children });
  const category = checkbox({ checked: false, dataset: { category: "jobs" } });
  listeners.change(eventFor(category, ["." + CATEGORY_CLASS]));
  assert.deepEqual(children.map((c) => c.checked), [false, false]);
});

test("a single scope asks the shared picker to refresh its category", () => {
  const { listeners, categoryPickerCalls } = load({});
  const scope = checkbox({ dataset: { category: "jobs" } });
  listeners.change(eventFor(scope, ["#scope-options .clavaris-event-option input[type=checkbox]"]));
  assert.deepEqual(categoryPickerCalls, [["update", CONTAINER, CATEGORY_CLASS, scope]]);
});

test("a change anywhere else on the page is ignored", () => {
  const { listeners, categoryPickerCalls } = load({});
  listeners.change(eventFor(checkbox(), []));
  assert.deepEqual(categoryPickerCalls, []);
});

test("on load every category starts from the scopes already ticked", () => {
  const { listeners, categoryPickerCalls } = load({});
  listeners.DOMContentLoaded();
  assert.deepEqual(categoryPickerCalls, [["init", CONTAINER, CATEGORY_CLASS]]);
});
