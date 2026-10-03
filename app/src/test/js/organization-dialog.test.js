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
  "organization-dialog.js",
);

function loadScript(documentStub) {
  global.document = documentStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

function documentHarness() {
  const listeners = {};
  const bodyListeners = {};
  return {
    addEventListener(eventName, listener) {
      listeners[eventName] = listener;
    },
    body: {
      addEventListener(eventName, listener) {
        bodyListeners[eventName] = listener;
      },
    },
    dispatch(eventName, event = {}) {
      listeners[eventName](event);
    },
    dispatchOnBody(eventName, event = {}) {
      bodyListeners[eventName](event);
    },
    querySelector() {
      return null;
    },
  };
}

test("opens the requested organisation dialog from a creation tile", () => {
  const documentStub = documentHarness();
  const dialog = { showModalCalls: 0, showModal() { this.showModalCalls += 1; } };
  const trigger = { dataset: { dialogOpen: "create-organization-dialog" } };
  documentStub.getElementById = (id) => (id === "create-organization-dialog" ? dialog : null);

  loadScript(documentStub);
  documentStub.dispatch("click", { target: { closest: (selector) => (selector === "[data-dialog-open]" ? trigger : null) } });

  assert.equal(dialog.showModalCalls, 1);
});

test("does not fail when a creation trigger has no available dialog", () => {
  const documentStub = documentHarness();
  documentStub.getElementById = () => null;

  loadScript(documentStub);
  documentStub.dispatch("click", { target: { closest: (selector) => (selector === "[data-dialog-open]" ? { dataset: {} } : null) } });
});

test("closes the containing dialog from either modal close control", () => {
  const documentStub = documentHarness();
  let closeCalls = 0;
  const closeTrigger = { closest: (selector) => (selector === "dialog" ? { close: () => { closeCalls += 1; } } : null) };

  loadScript(documentStub);
  documentStub.dispatch("click", { target: { closest: (selector) => (selector === "[data-dialog-close]" ? closeTrigger : null) } });

  assert.equal(closeCalls, 1);
});

test("reopens the dialog when server-side name validation returns an error", () => {
  const documentStub = documentHarness();
  let showModalCalls = 0;
  const dialog = { showModal: () => { showModalCalls += 1; } };
  documentStub.querySelector = (selector) =>
    selector === "[data-dialog-open-on-load]" ? { closest: (target) => (target === "dialog" ? dialog : null) } : null;

  loadScript(documentStub);
  documentStub.dispatch("DOMContentLoaded");

  assert.equal(showModalCalls, 1);
});

test("does nothing on load when the page has no validation error marker", () => {
  const documentStub = documentHarness();
  loadScript(documentStub);

  documentStub.dispatch("DOMContentLoaded");
});

test("reopens the dialog after an HTMX fragment swap returns a validation error", () => {
  const documentStub = documentHarness();
  let showModalCalls = 0;
  const dialog = { showModal: () => { showModalCalls += 1; } };
  documentStub.querySelector = (selector) =>
    selector === "[data-dialog-open-on-load]" ? { closest: (target) => (target === "dialog" ? dialog : null) } : null;

  loadScript(documentStub);
  documentStub.dispatchOnBody("htmx:afterSwap");

  assert.equal(showModalCalls, 1);
});

test("does nothing after an HTMX swap when the swapped content has no validation error marker", () => {
  const documentStub = documentHarness();
  loadScript(documentStub);

  assert.doesNotThrow(() => documentStub.dispatchOnBody("htmx:afterSwap"));
});

test("closes any open dialog when a role is assigned elsewhere on the page", () => {
  const documentStub = documentHarness();
  let closeCalls = 0;
  documentStub.querySelector = (selector) =>
    selector === "dialog[open]" ? { close: () => { closeCalls += 1; } } : null;

  loadScript(documentStub);
  documentStub.dispatch("workspace-role-assigned");

  assert.equal(closeCalls, 1);
});

test("does nothing on a role-assigned event when no dialog is open", () => {
  const documentStub = documentHarness();
  loadScript(documentStub);

  assert.doesNotThrow(() => documentStub.dispatch("workspace-role-assigned"));
});

test("closes a modal dialog when its backdrop is clicked", () => {
  const documentStub = documentHarness();
  let closeCalls = 0;
  const dialog = {
    tagName: "DIALOG",
    open: true,
    close() { closeCalls += 1; },
    getBoundingClientRect: () => ({ left: 100, right: 300, top: 100, bottom: 300 }),
  };

  loadScript(documentStub);
  documentStub.dispatch("click", { target: dialog, clientX: 50, clientY: 150 });

  assert.equal(closeCalls, 1);
});

test("keeps a dialog open when the click lands inside its own box", () => {
  const documentStub = documentHarness();
  let closeCalls = 0;
  const dialog = {
    tagName: "DIALOG",
    open: true,
    close() { closeCalls += 1; },
    closest: () => null,
    getBoundingClientRect: () => ({ left: 100, right: 300, top: 100, bottom: 300 }),
  };

  loadScript(documentStub);
  documentStub.dispatch("click", { target: dialog, clientX: 150, clientY: 150 });

  assert.equal(closeCalls, 0);
});

function labelledDialog(attributes = {}) {
  const heading = { id: "" };
  const dialog = {
    id: "delete-role-dialog-1",
    attributes: { ...attributes },
    showModalCalls: 0,
    showModal() { this.showModalCalls += 1; },
    getAttribute(name) { return this.attributes[name]; },
    setAttribute(name, value) { this.attributes[name] = value; },
    querySelector: () => heading,
  };
  return { dialog, heading };
}

function openFrom(dialog) {
  const documentStub = documentHarness();
  documentStub.getElementById = () => dialog;
  loadScript(documentStub);
  documentStub.dispatch("click", {
    target: { closest: (selector) => (selector === "[data-dialog-open]" ? { dataset: { dialogOpen: dialog.id } } : null) },
  });
}

test("names a dialog after its own heading when it opens", () => {
  const { dialog, heading } = labelledDialog();

  openFrom(dialog);

  assert.equal(dialog.attributes["aria-labelledby"], heading.id);
  assert.match(heading.id, /^delete-role-dialog-1-title-\d+$/);
  assert.equal(dialog.showModalCalls, 1);
});

test("leaves a dialog that already has an accessible name alone", () => {
  const { dialog, heading } = labelledDialog({ "aria-label": "Already named" });

  openFrom(dialog);

  assert.equal(dialog.attributes["aria-labelledby"], undefined);
  assert.equal(heading.id, "");
});
