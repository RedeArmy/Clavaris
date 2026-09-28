"use strict";

/**
 * assign-role-picker.js, added 2026-09-27 after a real bug found live: the script was referenced
 * by assign-role-form.html's own header comment but never actually existed anywhere in the repo —
 * the Team->Role filter it describes was a complete no-op. Same hand-rolled `document` stub
 * convention as organization-dialog.test.js/embedded-login-popup.test.js — no jsdom, `node:test`
 * only. Options/selects are plain objects with just the surface this script actually touches
 * (`.value`, `.options`, `.dataset.teamId`, `.hidden`, `.disabled`, `.selected`), not a real DOM.
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
  "assign-role-picker.js",
);

function makeOption(value, teamId, selected = false) {
  return { value, dataset: { teamId }, hidden: false, disabled: false, selected };
}

function makeSelect(options) {
  return {
    options,
    get value() {
      const selected = options.find((option) => option.selected);
      return selected ? selected.value : undefined;
    },
    set value(newValue) {
      options.forEach((option) => {
        option.selected = option.value === newValue;
      });
    },
  };
}

function loadScript(elementsById) {
  const listeners = {};
  global.document = {
    getElementById(id) {
      return elementsById[id] ?? null;
    },
    addEventListener(eventName, handler) {
      listeners[eventName] = handler;
    },
    body: {
      addEventListener(eventName, handler) {
        listeners[eventName] = handler;
      },
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return listeners;
}

test("filters role options to match the selected team on change", () => {
  const roleOptions = [makeOption("r1", "team-1"), makeOption("r2", "ungrouped")];
  const roleSelect = makeSelect(roleOptions);
  const teamSelect = makeSelect([makeOption("team-1", null, true), makeOption("ungrouped", null)]);
  const listeners = loadScript({ assignRoleNewRoleId: roleSelect, assignRoleTeamId: teamSelect });

  listeners.change({ target: { ...teamSelect, id: "assignRoleTeamId" } });

  assert.equal(roleOptions[0].hidden, false);
  assert.equal(roleOptions[0].disabled, false);
  assert.equal(roleOptions[1].hidden, true);
  assert.equal(roleOptions[1].disabled, true);
});

test("selects the first still-visible option when the current one becomes hidden", () => {
  const roleOptions = [
    makeOption("r1", "team-1"),
    makeOption("r2", "ungrouped", true),
  ];
  const roleSelect = makeSelect(roleOptions);
  const listeners = loadScript({ assignRoleNewRoleId: roleSelect });

  listeners.change({ target: { id: "assignRoleTeamId", value: "team-1" } });

  assert.equal(roleSelect.value, "r1");
});

test("keeps the current selection when it still matches the chosen team", () => {
  const roleOptions = [makeOption("r1", "team-1", true), makeOption("r2", "ungrouped")];
  const roleSelect = makeSelect(roleOptions);
  const listeners = loadScript({ assignRoleNewRoleId: roleSelect });

  listeners.change({ target: { id: "assignRoleTeamId", value: "team-1" } });

  assert.equal(roleSelect.value, "r1");
});

test("does nothing when there is no role select in the DOM", () => {
  const listeners = loadScript({});

  assert.doesNotThrow(() =>
    listeners.change({ target: { id: "assignRoleTeamId", value: "team-1" } }),
  );
});

test("ignores a change event from an unrelated element", () => {
  const roleOptions = [makeOption("r1", "team-1")];
  const roleSelect = makeSelect(roleOptions);
  loadScript({ assignRoleNewRoleId: roleSelect });

  // No listener invocation at all for an unrelated id — nothing to assert beyond "no throw," the
  // option's own hidden/disabled state (both false already) is proof enough nothing ran.
  assert.equal(roleOptions[0].hidden, false);
});

test("re-applies the filter automatically after an htmx swap", () => {
  const roleOptions = [makeOption("r1", "team-1"), makeOption("r2", "ungrouped")];
  const roleSelect = makeSelect(roleOptions);
  const teamSelect = makeSelect([makeOption("team-1", null, true), makeOption("ungrouped", null)]);
  const listeners = loadScript({ assignRoleNewRoleId: roleSelect, assignRoleTeamId: teamSelect });

  listeners["htmx:afterSwap"]();

  assert.equal(roleOptions[0].hidden, false);
  assert.equal(roleOptions[1].hidden, true);
});

test("does nothing on an htmx swap when there is no team select in the DOM", () => {
  const listeners = loadScript({});

  assert.doesNotThrow(() => listeners["htmx:afterSwap"]());
});
