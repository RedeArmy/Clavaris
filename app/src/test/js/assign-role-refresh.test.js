"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "assign-role-refresh.js");

function load({ openDialog = null } = {}) {
  const calls = { reloaded: 0, closed: 0 };
  const bodyListeners = {};
  global.window = { location: { reload: () => { calls.reloaded += 1; } } };
  global.document = {
    body: { addEventListener: (name, listener) => { bodyListeners[name] = listener; } },
    querySelector: (selector) => (selector === "dialog[open]" && openDialog
      ? { close: () => { calls.closed += 1; } }
      : null),
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { calls, bodyListeners };
}

test("reloads the page once a workspace role is assigned", () => {
  const { calls, bodyListeners } = load();
  bodyListeners["workspace-role-assigned"]();
  assert.equal(calls.reloaded, 1);
});

test("closes the open dialog before it reloads", () => {
  const { calls, bodyListeners } = load({ openDialog: true });
  bodyListeners["workspace-role-assigned"]();
  assert.equal(calls.closed, 1);
  assert.equal(calls.reloaded, 1);
});

test("listens to nothing but the one event the server sets", () => {
  const { bodyListeners } = load();
  assert.deepEqual(Object.keys(bodyListeners), ["workspace-role-assigned"]);
});
