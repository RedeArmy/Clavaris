"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "event-type-picker.js");

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

const CONTAINER = "event-type-options";
const CATEGORY_CLASS = "clavaris-event-category-checkbox";
const CHILDREN = '#event-type-options .clavaris-event-option input[data-category="jobs"]';
const ALL_BOXES = "#event-type-options input[type=checkbox]";

test("ticking a category ticks its events but leaves a disabled one alone", () => {
  const open = checkbox();
  const locked = checkbox({ disabled: true });
  const { listeners } = load({ [CHILDREN]: [open, locked] });
  const category = checkbox({ checked: true, indeterminate: true, dataset: { category: "jobs" } });
  listeners.change(eventFor(category, ["." + CATEGORY_CLASS]));
  assert.equal(open.checked, true);
  assert.equal(locked.checked, false);
  assert.equal(category.indeterminate, false);
});

test("the wildcard ticks and locks every box and enables its hidden value", () => {
  const boxes = [checkbox({ indeterminate: true }), checkbox()];
  const hidden = { disabled: true };
  const { listeners } = load({ [ALL_BOXES]: boxes }, { "all-events-hidden-value": hidden });
  listeners.change({ target: { id: "all-events-checkbox", checked: true } });
  assert.deepEqual(boxes.map((b) => [b.checked, b.disabled, b.indeterminate]), [
    [true, true, false],
    [true, true, false],
  ]);
  assert.equal(hidden.disabled, false);
});

test("turning the wildcard off clears and unlocks every box and disables its hidden value", () => {
  const boxes = [checkbox({ checked: true, disabled: true })];
  const hidden = { disabled: false };
  const { listeners } = load({ [ALL_BOXES]: boxes }, { "all-events-hidden-value": hidden });
  listeners.change({ target: { id: "all-events-checkbox", checked: false } });
  assert.deepEqual([boxes[0].checked, boxes[0].disabled], [false, false]);
  assert.equal(hidden.disabled, true);
});

test("the wildcard still works on a page that has no hidden value", () => {
  const boxes = [checkbox()];
  const { listeners } = load({ [ALL_BOXES]: boxes });
  listeners.change({ target: { id: "all-events-checkbox", checked: true } });
  assert.equal(boxes[0].checked, true);
});

test("a single event asks the shared picker to refresh its category", () => {
  const { listeners, categoryPickerCalls } = load({});
  const event = checkbox({ dataset: { category: "jobs" } });
  listeners.change(eventFor(event, ["#event-type-options .clavaris-event-option input[type=checkbox]"]));
  assert.deepEqual(categoryPickerCalls, [["update", CONTAINER, CATEGORY_CLASS, event]]);
});

test("on load an endpoint already subscribed to everything renders locked", () => {
  const boxes = [checkbox({ checked: true })];
  const { listeners, categoryPickerCalls } = load(
    { [ALL_BOXES]: boxes },
    { "all-events-checkbox": { checked: true } },
  );
  listeners.DOMContentLoaded();
  assert.deepEqual(categoryPickerCalls, [["init", CONTAINER, CATEGORY_CLASS]]);
  assert.equal(boxes[0].disabled, true);
});

test("on load an endpoint with specific events is left as rendered", () => {
  const boxes = [checkbox({ checked: true })];
  const { listeners } = load({ [ALL_BOXES]: boxes }, { "all-events-checkbox": { checked: false } });
  listeners.DOMContentLoaded();
  assert.equal(boxes[0].disabled, false);
});
