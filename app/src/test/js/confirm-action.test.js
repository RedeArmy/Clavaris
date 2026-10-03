"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "confirm-action.js");

function element() {
  const listeners = {};
  return {
    listeners,
    textContent: "",
    classList: { enabled: {}, toggle(name, on) { this.enabled[name] = on; } },
    addEventListener(name, fn) { listeners[name] = fn; },
    removeEventListener(name) { delete listeners[name]; },
    focus() {},
  };
}

function loadScript() {
  const parts = {
    "#clavaris-confirm-title": element(),
    "#clavaris-confirm-message": element(),
    "[data-confirm-accept]": element(),
    "[data-confirm-cancel]": element(),
  };
  const dialog = Object.assign(element(), {
    shown: 0,
    closed: 0,
    setAttribute() {},
    querySelector: (selector) => parts[selector],
    showModal() { this.shown += 1; },
    close() { this.closed += 1; },
  });
  let submitHandler;
  global.document = {
    createElement: () => dialog,
    body: { appendChild() {} },
    addEventListener(name, handler, capture) {
      if (name === "submit") {
        assert.equal(capture, true, "must run in the capture phase, before HTMX");
        submitHandler = handler;
      }
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { dialog, parts, submit: (event) => submitHandler(event) };
}

function submitEvent(dataset) {
  const calls = { prevented: 0, stopped: 0, requested: [] };
  const form = {
    dataset,
    requestSubmit(submitter) { calls.requested.push(submitter); },
  };
  const event = {
    target: form,
    submitter: "the-button",
    preventDefault() { calls.prevented += 1; },
    stopImmediatePropagation() { calls.stopped += 1; },
  };
  return { event, form, calls };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("ignores forms that do not ask for confirmation", () => {
  const { submit } = loadScript();
  const { event, calls } = submitEvent({});

  submit(event);

  assert.equal(calls.prevented, 0);
  assert.equal(calls.stopped, 0);
});

test("holds the submit and asks, then replays it on confirm", async () => {
  const { submit, dialog, parts } = loadScript();
  const { event, form, calls } = submitEvent({
    confirm: "The current secret stops working.",
    confirmTitle: "Rotate this secret?",
    confirmLabel: "Rotate",
    confirmTone: "danger",
  });

  submit(event);

  assert.equal(calls.prevented, 1);
  assert.equal(calls.stopped, 1);
  assert.equal(dialog.shown, 1);
  assert.equal(parts["#clavaris-confirm-title"].textContent, "Rotate this secret?");
  assert.equal(parts["#clavaris-confirm-message"].textContent, "The current secret stops working.");
  assert.equal(parts["[data-confirm-accept]"].textContent, "Rotate");
  assert.equal(parts["[data-confirm-accept]"].classList.enabled["clavaris-button--danger"], true);

  dialog.listeners.click({ target: { closest: (s) => (s === "[data-confirm-accept]" ? {} : null) } });
  await tick();

  assert.deepEqual(calls.requested, ["the-button"]);
  assert.equal(form.dataset.confirmed, undefined);
});

test("does not submit when the user cancels", async () => {
  const { submit, dialog } = loadScript();
  const { event, calls } = submitEvent({ confirm: "Sure?" });

  submit(event);
  dialog.listeners.click({ target: { closest: (s) => (s === "[data-confirm-cancel]" ? {} : null) } });
  await tick();

  assert.equal(calls.requested.length, 0);
  assert.equal(dialog.closed, 1);
});

test("treats Escape as cancel", async () => {
  const { submit, dialog } = loadScript();
  const { event, calls } = submitEvent({ confirm: "Sure?" });
  let defaultPrevented = false;

  submit(event);
  dialog.listeners.cancel({ preventDefault() { defaultPrevented = true; } });
  await tick();

  assert.equal(defaultPrevented, true);
  assert.equal(calls.requested.length, 0);
});

test("lets an already-confirmed replay through untouched", () => {
  const { submit } = loadScript();
  const { event, calls } = submitEvent({ confirm: "Sure?", confirmed: "true" });

  submit(event);

  assert.equal(calls.prevented, 0);
});
