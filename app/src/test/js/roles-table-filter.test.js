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
  "roles-table-filter.js",
);

function loadScript(documentStub) {
  global.document = documentStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

function documentHarness(tables) {
  const listeners = {};
  const bodyListeners = {};
  return {
    body: {
      addEventListener(eventName, listener) {
        bodyListeners[eventName] = listener;
      },
    },
    addEventListener(eventName, listener) {
      listeners[eventName] = listener;
    },
    dispatch(eventName, event = {}) {
      listeners[eventName](event);
    },
    querySelectorAll(selector) {
      return selector === "[data-roles-table]" ? tables : [];
    },
  };
}

function rowStub(name, teamId) {
  const nameCell = { textContent: name };
  return {
    hidden: false,
    dataset: { rolesTeamId: teamId },
    querySelector: (selector) => (selector === "[data-roles-name]" ? nameCell : null),
  };
}

function tableStub(rows) {
  const searchInput = { value: "" };
  const teamSelect = { value: "" };
  const wrapper = { hidden: false };
  const emptyState = { hidden: true };
  const indicator = { textContent: "" };
  const prevButton = { disabled: false };
  const nextButton = { disabled: false };
  const pagination = { hidden: false };
  return {
    dataset: {},
    rows,
    searchInput,
    teamSelect,
    wrapper,
    emptyState,
    indicator,
    prevButton,
    nextButton,
    pagination,
    querySelectorAll: (selector) => (selector === "[data-roles-row]" ? rows : []),
    querySelector(selector) {
      switch (selector) {
        case "[data-roles-search]":
          return searchInput;
        case "[data-roles-team-filter]":
          return teamSelect;
        case ".clavaris-table-wrapper":
          return wrapper;
        case "[data-roles-empty]":
          return emptyState;
        case "[data-roles-page-indicator]":
          return indicator;
        case "[data-roles-prev]":
          return prevButton;
        case "[data-roles-next]":
          return nextButton;
        case "[data-roles-pagination]":
          return pagination;
        default:
          return null;
      }
    },
  };
}

function searchInputTarget(table) {
  return {
    matches: (selector) => selector === "[data-roles-search]",
    closest: (selector) => (selector === "[data-roles-table]" ? table : null),
  };
}

function teamFilterTarget(table) {
  return {
    matches: (selector) => selector === "[data-roles-team-filter]",
    closest: (selector) => (selector === "[data-roles-table]" ? table : null),
  };
}

function navButtonTarget(kind, table) {
  const target = {
    closest(selector) {
      if (selector === `[data-roles-${kind}]`) {
        return target;
      }
      if (selector === "[data-roles-table]") {
        return table;
      }
      return null;
    },
  };
  return target;
}

test("renders every role visible on load when no search or filter is set", () => {
  const rows = [rowStub("Admin", "__none__"), rowStub("Reviewer", "team-1")];
  const table = tableStub(rows);
  const documentStub = documentHarness([table]);

  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(rows.every((row) => !row.hidden), true);
  assert.equal(table.indicator.textContent, "Page 1 of 1");
  assert.equal(table.pagination.hidden, true);
});

test("searching by name hides every role that doesn't match, case-insensitively", () => {
  const rows = [rowStub("Admin", "__none__"), rowStub("Reviewer", "team-1")];
  const table = tableStub(rows);
  const documentStub = documentHarness([table]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  table.searchInput.value = "adm";
  documentStub.dispatch("input", { target: searchInputTarget(table) });

  assert.equal(rows[0].hidden, false);
  assert.equal(rows[1].hidden, true);
});

test("filtering by team shows only that team's own roles, including Without Team", () => {
  const rows = [
    rowStub("Admin", "__none__"),
    rowStub("Reviewer", "team-1"),
    rowStub("Supervisor", "team-2"),
  ];
  const table = tableStub(rows);
  const documentStub = documentHarness([table]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  table.teamSelect.value = "__none__";
  documentStub.dispatch("change", { target: teamFilterTarget(table) });

  assert.equal(rows[0].hidden, false);
  assert.equal(rows[1].hidden, true);
  assert.equal(rows[2].hidden, true);
});

test("paginates 10 at a time and Next/Previous move between pages", () => {
  const rows = Array.from({ length: 15 }, (_unused, index) => rowStub(`Role ${index}`, "__none__"));
  const table = tableStub(rows);
  const documentStub = documentHarness([table]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(rows.slice(0, 10).every((row) => !row.hidden), true);
  assert.equal(rows.slice(10).every((row) => row.hidden), true);
  assert.equal(table.indicator.textContent, "Page 1 of 2");
  assert.equal(table.prevButton.disabled, true);
  assert.equal(table.nextButton.disabled, false);

  documentStub.dispatch("click", { target: navButtonTarget("next", table) });

  assert.equal(rows.slice(0, 10).every((row) => row.hidden), true);
  assert.equal(rows.slice(10, 15).every((row) => !row.hidden), true);
  assert.equal(table.indicator.textContent, "Page 2 of 2");
  assert.equal(table.prevButton.disabled, false);
  assert.equal(table.nextButton.disabled, true);

  documentStub.dispatch("click", { target: navButtonTarget("prev", table) });

  assert.equal(rows.slice(0, 10).every((row) => !row.hidden), true);
  assert.equal(table.indicator.textContent, "Page 1 of 2");
});

test("shows the empty state and hides the table wrapper when nothing matches", () => {
  const rows = [rowStub("Admin", "__none__")];
  const table = tableStub(rows);
  const documentStub = documentHarness([table]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  table.searchInput.value = "nonexistent";
  documentStub.dispatch("input", { target: searchInputTarget(table) });

  assert.equal(rows[0].hidden, true);
  assert.equal(table.wrapper.hidden, true);
  assert.equal(table.emptyState.hidden, false);
});
