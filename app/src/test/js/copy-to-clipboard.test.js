"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "copy-to-clipboard.js");

function setup({ clipboard, source = { textContent: "  pk_test_abc  " } } = {}) {
  let clickHandler;
  const status = { textContent: "" };
  const label = { textContent: "Copy" };
  const button = {
    dataset: { copyTarget: "publishable-key" },
    querySelector: (selector) => (selector === "[data-copy-label]" ? label : null),
    getAttribute: (name) => (name === "aria-label" ? "Copy publishable key" : null),
  };
  global.document = {
    addEventListener: (name, handler) => { if (name === "click") clickHandler = handler; },
    getElementById: (id) => (id === "publishable-key" ? source : null),
    querySelector: (selector) => (selector === "[data-copy-status]" ? status : null),
    createRange: () => ({ selectNodeContents() {} }),
    execCommand: () => true,
  };
  global.window = { getSelection: () => ({ removeAllRanges() {}, addRange() {} }) };
  Object.defineProperty(global, "navigator", { value: { clipboard }, configurable: true, writable: true });
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  const click = (target = { closest: (selector) => (selector === "[data-copy-target]" ? button : null) }) =>
    clickHandler({ target });
  return { click, button, label, status };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("copies the trimmed text of the target and confirms on the button and the live region", async () => {
  const written = [];
  const { click, button, label, status } = setup({ clipboard: { writeText: async (text) => written.push(text) } });

  click();
  await tick();

  assert.deepEqual(written, ["pk_test_abc"]);
  assert.equal(label.textContent, "Copied");
  assert.equal(button.dataset.copied, "true");
  assert.equal(status.textContent, "Copied: Copy publishable key");
  clearTimeout(button.copyTimer);
});

test("falls back to a selection when the Clipboard API rejects", async () => {
  const { click, label, button } = setup({
    clipboard: { writeText: async () => { throw new Error("not focused"); } },
  });

  click();
  await tick();

  assert.equal(label.textContent, "Copied");
  clearTimeout(button.copyTimer);
});

test("reports a failure only when both routes fail", async () => {
  const { click, label, status, button } = setup({
    clipboard: { writeText: async () => { throw new Error("denied"); } },
  });
  global.document.execCommand = () => false;

  click();
  await tick();

  assert.equal(label.textContent, "Copy failed");
  assert.equal(status.textContent, "Copy failed: Copy publishable key");
  clearTimeout(button.copyTimer);
});

test("falls back to a selection and execCommand when the Clipboard API is missing", async () => {
  const { click, label, button } = setup({ clipboard: undefined });

  click();
  await tick();

  assert.equal(label.textContent, "Copied");
  clearTimeout(button.copyTimer);
});

test("ignores clicks that are not on a copy button or whose target element is gone", async () => {
  const written = [];
  const { click, label } = setup({ clipboard: { writeText: async (text) => written.push(text) }, source: null });

  click({ closest: () => null });
  click();
  await tick();

  assert.deepEqual(written, []);
  assert.equal(label.textContent, "Copy");
});
