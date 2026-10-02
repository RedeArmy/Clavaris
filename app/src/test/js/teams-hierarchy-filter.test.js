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
  "teams-hierarchy-filter.js",
);

function loadScript(documentStub) {
  global.document = documentStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

function documentHarness(containers) {
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
      return selector === "[data-teams-hierarchy]" ? containers : [];
    },
  };
}

function rowStub(teamName) {
  return { hidden: false, dataset: { teamName } };
}

function containerStub(rows) {
  const searchInput = { value: "" };
  const emptyState = { hidden: true };
  const indicator = { textContent: "" };
  const prevButton = { disabled: false };
  const nextButton = { disabled: false };
  const pagination = { hidden: false };
  return {
    dataset: {},
    rows,
    searchInput,
    emptyState,
    indicator,
    prevButton,
    nextButton,
    pagination,
    querySelectorAll: (selector) => (selector === "[data-team-row]" ? rows : []),
    querySelector(selector) {
      switch (selector) {
        case "[data-teams-search]":
          return searchInput;
        case "[data-teams-empty]":
          return emptyState;
        case "[data-teams-page-indicator]":
          return indicator;
        case "[data-teams-prev]":
          return prevButton;
        case "[data-teams-next]":
          return nextButton;
        case "[data-teams-pagination]":
          return pagination;
        default:
          return null;
      }
    },
  };
}

function searchInputTarget(container) {
  return {
    matches: (selector) => selector === "[data-teams-search]",
    closest: (selector) => (selector === "[data-teams-hierarchy]" ? container : null),
  };
}

function navButtonTarget(kind, container) {
  const target = {
    closest(selector) {
      if (selector === `[data-teams-${kind}]`) {
        return target;
      }
      if (selector === "[data-teams-hierarchy]") {
        return container;
      }
      return null;
    },
  };
  return target;
}

test("renders every team visible on load when there are 5 or fewer", () => {
  const rows = [rowStub("QA"), rowStub("Backend"), rowStub("Design")];
  const container = containerStub(rows);
  const documentStub = documentHarness([container]);

  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(rows.every((row) => !row.hidden), true);
  assert.equal(container.indicator.textContent, "Page 1 of 1");
  assert.equal(container.pagination.hidden, true);
});

test("searching by name hides every team that doesn't match, case-insensitively", () => {
  const rows = [rowStub("QA"), rowStub("Backend")];
  const container = containerStub(rows);
  const documentStub = documentHarness([container]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  container.searchInput.value = "qa";
  documentStub.dispatch("input", { target: searchInputTarget(container) });

  assert.equal(rows[0].hidden, false);
  assert.equal(rows[1].hidden, true);
});

test("paginates 5 at a time and Next/Previous move between pages", () => {
  const rows = Array.from({ length: 8 }, (_unused, index) => rowStub(`Team ${index}`));
  const container = containerStub(rows);
  const documentStub = documentHarness([container]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(rows.slice(0, 5).every((row) => !row.hidden), true);
  assert.equal(rows.slice(5).every((row) => row.hidden), true);
  assert.equal(container.indicator.textContent, "Page 1 of 2");
  assert.equal(container.prevButton.disabled, true);
  assert.equal(container.nextButton.disabled, false);

  documentStub.dispatch("click", { target: navButtonTarget("next", container) });

  assert.equal(rows.slice(0, 5).every((row) => row.hidden), true);
  assert.equal(rows.slice(5, 8).every((row) => !row.hidden), true);
  assert.equal(container.indicator.textContent, "Page 2 of 2");
  assert.equal(container.prevButton.disabled, false);
  assert.equal(container.nextButton.disabled, true);

  documentStub.dispatch("click", { target: navButtonTarget("prev", container) });

  assert.equal(rows.slice(0, 5).every((row) => !row.hidden), true);
  assert.equal(container.indicator.textContent, "Page 1 of 2");
});

test("shows the empty state when no team matches the search", () => {
  const rows = [rowStub("QA")];
  const container = containerStub(rows);
  const documentStub = documentHarness([container]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  container.searchInput.value = "nonexistent";
  documentStub.dispatch("input", { target: searchInputTarget(container) });

  assert.equal(rows[0].hidden, true);
  assert.equal(container.emptyState.hidden, false);
});

test("searching resets back to page 1", () => {
  const rows = Array.from({ length: 8 }, (_unused, index) => rowStub(`Team ${index}`));
  const container = containerStub(rows);
  const documentStub = documentHarness([container]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");
  documentStub.dispatch("click", { target: navButtonTarget("next", container) });
  assert.equal(container.dataset.currentPage, "2");

  container.searchInput.value = "team";
  documentStub.dispatch("input", { target: searchInputTarget(container) });

  assert.equal(container.dataset.currentPage, "1");
});
