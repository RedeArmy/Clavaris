"use strict";

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
  "duration-field.js",
);

const MINUTE = 1;
const HOUR = 60;
const DAY = 1440;
const WEEK = 10080;
const YEAR = 525600;

function load(documentStub) {
  global.document = documentStub;
  delete globalThis.clavarisI18n;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return globalThis.clavarisDuration;
}

// A field as the page renders it: an amount, an optional unit selector, and the two messages
// beneath it. Only what the script reads is simulated.
function field({ value, unit = MINUTE, min = 5, max = 5256000, selector = true, notLongerThan }) {
  const total = { textContent: "", hidden: true };
  const error = { textContent: "", hidden: true };
  const attributes = {};
  const input = {
    value: String(value),
    min: "",
    max: "",
    dataset: selector ? {} : { minutesPerUnit: String(unit) },
    validity: "",
    setCustomValidity(message) {
      this.validity = message;
    },
    setAttribute(name, content) {
      attributes[name] = content;
    },
    removeAttribute(name) {
      delete attributes[name];
    },
    closest: (selectorText) => (selectorText === "[data-duration-field]" ? wrapper : null),
  };
  const select = selector ? { selectedOptions: [{ dataset: { minutes: String(unit) } }] } : null;
  const container = {
    querySelector: (selectorText) =>
      ({ "[data-duration-total]": total, "[data-duration-error]": error })[selectorText] ?? null,
  };
  const wrapper = {
    dataset: {
      minMinutes: String(min),
      maxMinutes: String(max),
      ...(notLongerThan ? { notLongerThan } : {}),
    },
    parentElement: container,
    querySelector: (selectorText) =>
      selectorText === "input" ? input : selectorText === "select" ? select : null,
    closest: () => null,
    ownerDocument: null,
  };
  return { wrapper, input, select, total, error, attributes };
}

function pageWith(fields, byId = {}) {
  const listeners = {};
  const form = {
    querySelectorAll: (selector) => (selector === "[data-duration-field]" ? fields.map((f) => f.wrapper) : []),
  };
  const doc = {
    addEventListener: (name, listener) => {
      listeners[name] = listener;
    },
    body: { addEventListener: () => {} },
    getElementById: (id) => byId[id] ?? null,
    querySelectorAll: form.querySelectorAll,
  };
  fields.forEach((f) => {
    f.wrapper.ownerDocument = doc;
    f.wrapper.closest = (selectorText) => (selectorText === "form" ? form : null);
  });
  return { doc, listeners };
}

test("states a duration in the largest unit that is exact", () => {
  const { describe } = load({ addEventListener() {}, body: null });

  assert.equal(describe(5), "5 minutes");
  assert.equal(describe(1), "1 minute");
  assert.equal(describe(60), "1 hour");
  assert.equal(describe(1080), "18 hours");
  assert.equal(describe(WEEK), "1 week");
  assert.equal(describe(YEAR), "1 year");
  assert.equal(describe(10 * YEAR), "10 years");
});

test("breaks an awkward duration into units, largest first", () => {
  const { humanize } = load({ addEventListener() {}, body: null });

  assert.equal(humanize(1080), "18 hours");
  assert.equal(humanize(DAY + 6 * HOUR), "1 day, 6 hours");
  assert.equal(humanize(YEAR + 5 * WEEK + 2 * DAY), "1 year, 1 month, 1 week");
});

test("accepts an amount inside the bounds, in any unit", () => {
  const { analyse } = load({ addEventListener() {}, body: null });

  assert.deepEqual(analyse("18", HOUR, 5, 5256000), { minutes: 1080 });
  assert.deepEqual(analyse("10", YEAR, 5, 5256000), { minutes: 5256000 });
  assert.deepEqual(analyse(" 5 ", MINUTE, 5, 5256000), { minutes: 5 });
});

test("explains an amount that is not a whole number above zero", () => {
  const { analyse } = load({ addEventListener() {}, body: null });

  for (const raw of ["", "  ", "abc", "1.5", "-3", "0", "5h", undefined, null]) {
    assert.equal(
      analyse(raw, HOUR, 5, 5256000).message,
      "Enter a whole number greater than zero.",
      String(raw),
    );
  }
});

test("judges an amount after its unit is applied, naming the bound in its largest unit", () => {
  const { analyse } = load({ addEventListener() {}, body: null });

  assert.equal(analyse("4", MINUTE, 5, 5256000).message, "Must be at least 5 minutes.");
  assert.equal(analyse("11", YEAR, 5, 5256000).message, "Must be at most 10 years.");
  assert.equal(analyse("3651", DAY, 5, 5256000).message, "Must be at most 10 years.");
  assert.equal(analyse("2", YEAR, 5, YEAR).message, "Must be at most 1 year.");
  assert.equal(analyse("11", MINUTE, 1, 10).message, "Must be at most 10 minutes.");
});

test("while typing, shows what an amount comes to but does not call it wrong yet", () => {
  const typed = field({ value: 1080, unit: MINUTE });
  const page = pageWith([typed]);
  load(page.doc);

  page.listeners.input({ target: typed.input });

  assert.equal(typed.total.textContent, "That is 18 hours.");
  assert.equal(typed.total.hidden, false);
  assert.equal(typed.error.hidden, true);
});

test("says nothing extra when the amount is already in its best unit", () => {
  const exact = field({ value: 18, unit: HOUR });
  const page = pageWith([exact]);
  load(page.doc);

  page.listeners.input({ target: exact.input });

  assert.equal(exact.total.hidden, true);
});

test("the limits follow the chosen unit", () => {
  const days = field({ value: 7, unit: DAY });
  const page = pageWith([days]);
  load(page.doc);

  page.listeners.input({ target: days.input });

  // 5 minutes rounds up to a day; ten years is 3650 days.
  assert.equal(days.input.min, "1");
  assert.equal(days.input.max, "3650");
});

test("once settled, an amount out of range is explained and marked invalid", () => {
  const tooShort = field({ value: 3, unit: MINUTE });
  const page = pageWith([tooShort]);
  load(page.doc);

  page.listeners.change({ target: tooShort.input });

  assert.equal(tooShort.error.textContent, "Must be at least 5 minutes.");
  assert.equal(tooShort.error.hidden, false);
  assert.equal(tooShort.attributes["aria-invalid"], "true");
  assert.equal(tooShort.input.validity, "Must be at least 5 minutes.");
});

test("a valid amount clears the message and the invalid mark", () => {
  const fixed = field({ value: 3, unit: MINUTE });
  const page = pageWith([fixed]);
  load(page.doc);
  page.listeners.change({ target: fixed.input });

  fixed.input.value = "30";
  page.listeners.change({ target: fixed.input });

  assert.equal(fixed.error.hidden, true);
  assert.equal(fixed.attributes["aria-invalid"], undefined);
  assert.equal(fixed.input.validity, "");
});

test("a field with no selector is always minutes", () => {
  const window = field({ value: 11, unit: MINUTE, min: 1, max: 10, selector: false });
  const page = pageWith([window]);
  load(page.doc);

  page.listeners.change({ target: window.input });

  assert.equal(window.error.textContent, "Must be at most 10 minutes.");
});

test("an inactivity timeout longer than the lifetime is refused, and clears when the lifetime grows", () => {
  const lifetime = field({ value: 1, unit: DAY });
  const inactivity = field({ value: 2, unit: DAY, notLongerThan: "lifetime-input" });
  const page = pageWith([lifetime, inactivity], { "lifetime-input": lifetime.input });
  load(page.doc);

  page.listeners.change({ target: inactivity.input });
  assert.equal(inactivity.error.textContent, "Can't be longer than the maximum lifetime.");

  lifetime.input.value = "1";
  lifetime.select.selectedOptions[0].dataset.minutes = String(WEEK);
  page.listeners.change({ target: lifetime.input });
  assert.equal(inactivity.error.hidden, true);
});
