"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "oauth-client-scope-row.js");

function row() {
  const attributes = {};
  const input = { id: "" };
  const label = { setAttribute: (name, value) => { attributes[name] = value; } };
  return { input, label, attributes };
}

function load({ withTemplate = true, existingRows = 0 } = {}) {
  const listeners = {};
  const appended = [];
  const rows = [];
  const container = { appendChild: (clone) => { appended.push(clone); } };
  const template = {
    content: {
      cloneNode: () => {
        const made = row();
        rows.push(made);
        return { querySelector: (s) => (s === "input" ? made.input : made.label) };
      },
    },
  };
  global.document = {
    addEventListener: (name, listener) => { listeners[name] = listener; },
    getElementById: (id) => {
      if (id === "scope-row-template") { return withTemplate ? template : null; }
      return id === "scopes-rows" ? container : null;
    },
    querySelectorAll: () => new Array(existingRows).fill({}),
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { listeners, appended, rows };
}

const click = (action, closest = () => null) => ({ target: { dataset: { action }, closest } });

test("adding a row appends a clone whose input and label share a fresh id", () => {
  const { listeners, appended, rows } = load();
  listeners.click(click("add-scope-row"));
  assert.equal(appended.length, 1);
  assert.equal(rows[0].input.id, "scope0");
  assert.equal(rows[0].attributes.for, "scope0");
});

test("every added row gets an id of its own", () => {
  const { listeners, rows } = load();
  listeners.click(click("add-scope-row"));
  listeners.click(click("add-scope-row"));
  assert.deepEqual(rows.map((r) => r.input.id), ["scope0", "scope1"]);
});

test("new ids start past the rows the server already rendered", () => {
  const { listeners, rows } = load({ existingRows: 3 });
  listeners.DOMContentLoaded();
  listeners.click(click("add-scope-row"));
  assert.equal(rows[0].input.id, "scope3");
});

test("a page without the template or the container adds nothing", () => {
  const { listeners, appended } = load({ withTemplate: false });
  listeners.click(click("add-scope-row"));
  assert.equal(appended.length, 0);
});

test("removing a row removes the row that contains the button", () => {
  const { listeners } = load();
  let removed = 0;
  listeners.click(click("remove-scope-row", () => ({ remove: () => { removed += 1; } })));
  assert.equal(removed, 1);
});

test("removing from outside any row does nothing", () => {
  const { listeners } = load();
  assert.doesNotThrow(() => listeners.click(click("remove-scope-row")));
});

test("a click on anything else is ignored", () => {
  const { listeners, appended } = load();
  listeners.click(click(undefined));
  assert.equal(appended.length, 0);
});
