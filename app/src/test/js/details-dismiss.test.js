"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "details-dismiss.js");

function setup(...openDetails) {
  const listeners = {};
  global.document = {
    addEventListener: (name, handler) => { listeners[name] = handler; },
    querySelectorAll: (selector) => (selector === "details[data-dismissible][open]" ? openDetails.filter((d) => d.open) : []),
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return listeners;
}

function details({ contains = false } = {}) {
  const summary = { focused: false, focus() { this.focused = true; } };
  return { open: true, summary, contains: () => contains, querySelector: (s) => (s === "summary" ? summary : null) };
}

test("closes an open switcher when the click lands outside it", () => {
  const outside = details();
  const listeners = setup(outside);

  listeners.click({ target: {} });

  assert.equal(outside.open, false);
});

test("keeps it open when the click lands inside it", () => {
  const inside = details({ contains: true });
  const listeners = setup(inside);

  listeners.click({ target: {} });

  assert.equal(inside.open, true);
});

test("Escape closes every open switcher and returns focus to its summary", () => {
  const one = details();
  const listeners = setup(one);

  listeners.keydown({ key: "Escape" });

  assert.equal(one.open, false);
  assert.equal(one.summary.focused, true);
});

test("other keys do nothing", () => {
  const one = details();
  const listeners = setup(one);

  listeners.keydown({ key: "Enter" });

  assert.equal(one.open, true);
  assert.equal(one.summary.focused, false);
});
