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
  "role-picker.js",
);

function loadScript(documentStub) {
  global.document = documentStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

function documentHarness(pickers) {
  const listeners = {};
  const bodyListeners = {};
  const elementsById = {};
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
    dispatchOnBody(eventName) {
      bodyListeners[eventName]();
    },
    querySelectorAll(selector) {
      return selector === "[data-role-picker]" ? pickers : [];
    },
    getElementById(id) {
      return elementsById[id] ?? null;
    },
    registerElement(id, element) {
      elementsById[id] = element;
    },
  };
}

function optionStub() {
  return { hidden: false };
}

function pickerStub(optionCount) {
  const options = Array.from({ length: optionCount }, optionStub);
  const indicator = { textContent: "" };
  const prevButton = { disabled: false };
  const nextButton = { disabled: false };
  return {
    dataset: {},
    options,
    indicator,
    prevButton,
    nextButton,
    querySelectorAll: (selector) =>
      selector === ".clavaris-role-picker__option" ? options : [],
    querySelector: (selector) => {
      if (selector === ".clavaris-role-picker__page-indicator") {
        return indicator;
      }
      if (selector === "[data-role-picker-prev]") {
        return prevButton;
      }
      if (selector === "[data-role-picker-next]") {
        return nextButton;
      }
      return null;
    },
  };
}

function navButtonTarget(kind, picker) {
  const target = {
    closest(selector) {
      if (selector === `[data-role-picker-${kind}]`) {
        return target;
      }
      if (selector === "[data-role-picker]") {
        return picker;
      }
      return null;
    },
  };
  return target;
}

function dialogOpenerTarget(dialogId) {
  return {
    closest(selector) {
      if (selector === "[data-role-picker-prev]" || selector === "[data-role-picker-next]") {
        return null;
      }
      if (selector === "[data-dialog-open]") {
        return { dataset: { dialogOpen: dialogId } };
      }
      return null;
    },
  };
}

test("renders only the first 10 options and labels the page on load", () => {
  const picker = pickerStub(15);
  const documentStub = documentHarness([picker]);

  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(picker.options.slice(0, 10).every((option) => !option.hidden), true);
  assert.equal(picker.options.slice(10).every((option) => option.hidden), true);
  assert.equal(picker.indicator.textContent, "Page 1 of 2");
  assert.equal(picker.prevButton.disabled, true);
  assert.equal(picker.nextButton.disabled, false);
});

test("clicking Next reveals the second page and re-enables Previous", () => {
  const picker = pickerStub(15);
  const documentStub = documentHarness([picker]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  documentStub.dispatch("click", { target: navButtonTarget("next", picker) });

  assert.equal(picker.options.slice(0, 10).every((option) => option.hidden), true);
  assert.equal(picker.options.slice(10, 15).every((option) => !option.hidden), true);
  assert.equal(picker.indicator.textContent, "Page 2 of 2");
  assert.equal(picker.prevButton.disabled, false);
  assert.equal(picker.nextButton.disabled, true);
});

test("Next does nothing once already on the last page", () => {
  const picker = pickerStub(5);
  const documentStub = documentHarness([picker]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  documentStub.dispatch("click", { target: navButtonTarget("next", picker) });

  assert.equal(picker.dataset.currentPage, "1");
  assert.equal(picker.indicator.textContent, "Page 1 of 1");
});

test("clicking Previous returns to the first page", () => {
  const picker = pickerStub(15);
  const documentStub = documentHarness([picker]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");
  documentStub.dispatch("click", { target: navButtonTarget("next", picker) });

  documentStub.dispatch("click", { target: navButtonTarget("prev", picker) });

  assert.equal(picker.options.slice(0, 10).every((option) => !option.hidden), true);
  assert.equal(picker.indicator.textContent, "Page 1 of 2");
});

test("opening a dialog resets every picker inside it back to page 1", () => {
  const picker = pickerStub(15);
  const documentStub = documentHarness([picker]);
  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");
  documentStub.dispatch("click", { target: navButtonTarget("next", picker) });
  assert.equal(picker.dataset.currentPage, "2");

  const dialog = { querySelectorAll: (selector) => (selector === "[data-role-picker]" ? [picker] : []) };
  documentStub.registerElement("add-role-dialog-1", dialog);
  documentStub.dispatch("click", { target: dialogOpenerTarget("add-role-dialog-1") });

  assert.equal(picker.dataset.currentPage, "1");
  assert.equal(picker.indicator.textContent, "Page 1 of 2");
});
