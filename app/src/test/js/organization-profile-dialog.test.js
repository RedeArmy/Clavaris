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
  "organization-profile-dialog.js",
);

const MB = 1024 * 1024;

// Loads the script against a minimal document that records the listeners it registers.
function load() {
  const listeners = {};
  global.document = {
    addEventListener: (name, listener) => {
      listeners[name] = listener;
    },
    createElement: (tag) => ({
      tag,
      attributes: {},
      setAttribute(name, value) {
        this.attributes[name] = value;
      },
    }),
  };
  global.URL.createObjectURL = () => "blob:preview";
  global.URL.revokeObjectURL = () => {};
  delete globalThis.clavarisI18n;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { api: globalThis.clavarisOrganizationProfile, listeners };
}

// A logo field as the dialog renders it: a file input, a preview slot and an error line.
function logoField({ withPlaceholder = true } = {}) {
  const error = { textContent: "", hidden: true };
  const removed = [];
  const placeholder = { remove: () => removed.push("placeholder") };
  let image = null;
  const slot = {
    classList: {
      classes: new Set(),
      add(c) {
        this.classes.add(c);
      },
      remove(c) {
        this.classes.delete(c);
      },
      toggle(c, on) {
        on ? this.classes.add(c) : this.classes.delete(c);
      },
    },
    querySelector: (selector) => {
      if (selector === "[data-logo-preview]") return image;
      if (selector === "[data-logo-placeholder]") return withPlaceholder ? placeholder : null;
      return null;
    },
    append: (el) => {
      image = el;
    },
  };
  const remove = { checked: false };
  const input = { value: "chosen.png", files: [], closest: () => field, matches: (s) => s === "[data-logo-input]" };
  const field = {
    querySelector: (selector) =>
      ({
        "[data-logo-error]": error,
        ".clavaris-logo-field__preview": slot,
        "[data-logo-remove]": remove,
        "[data-logo-input]": input,
      })[selector] ?? null,
  };
  return { field, input, error, slot, remove, image: () => image, removed };
}

test("accepts a PNG, JPEG, WebP or GIF within the size limit", () => {
  const { api } = load();

  for (const type of ["image/png", "image/jpeg", "image/webp", "image/gif"]) {
    assert.equal(api.logoProblem({ type, size: 1000 }), null, type);
  }
  assert.equal(api.logoProblem({ type: "image/png", size: MB }), null, "exactly 1 MB is fine");
  assert.equal(api.logoProblem(undefined), null, "no file chosen is not a problem");
});

test("explains a type that could never be accepted, an SVG included", () => {
  const { api } = load();

  for (const type of ["image/svg+xml", "text/html", "application/pdf", ""]) {
    assert.equal(
      api.logoProblem({ type, size: 100 }),
      "The logo must be a PNG, JPEG, WebP or GIF image",
      type || "(no type)",
    );
  }
});

test("explains a file over 1 MB", () => {
  const { api } = load();

  assert.equal(
    api.logoProblem({ type: "image/png", size: MB + 1 }),
    "The logo must not be larger than 1 MB",
  );
});

test("a usable file previews in place of the placeholder and clears any earlier message", () => {
  const { listeners } = load();
  const f = logoField();
  f.error.textContent = "old problem";
  f.error.hidden = false;
  f.input.files = [{ type: "image/png", size: 5000 }];

  listeners.change({ target: f.input });

  assert.equal(f.error.hidden, true);
  assert.equal(f.error.textContent, "");
  assert.equal(f.image().src, "blob:preview");
  assert.deepEqual(f.removed, ["placeholder"]);
});

test("an unusable file is explained and dropped from the chooser", () => {
  const { listeners } = load();
  const f = logoField();
  f.input.files = [{ type: "image/svg+xml", size: 100 }];

  listeners.change({ target: f.input });

  assert.equal(f.error.hidden, false);
  assert.equal(f.error.textContent, "The logo must be a PNG, JPEG, WebP or GIF image");
  assert.equal(f.input.value, "");
  assert.equal(f.image(), null);
});

test("choosing a new logo cancels a pending removal of the current one", () => {
  const { listeners } = load();
  const f = logoField({ withPlaceholder: false });
  f.remove.checked = true;
  f.slot.classList.add("clavaris-logo-field__preview--removing");
  f.input.files = [{ type: "image/jpeg", size: 100 }];

  listeners.change({ target: f.input });

  assert.equal(f.remove.checked, false);
  assert.equal(f.slot.classList.classes.has("clavaris-logo-field__preview--removing"), false);
});

test("removing the current logo clears a chosen file and any message", () => {
  const { listeners } = load();
  const f = logoField();
  f.error.hidden = false;
  f.error.textContent = "a problem";
  const toggle = {
    checked: true,
    closest: () => f.field,
    matches: (s) => s === "[data-logo-remove]",
  };

  listeners.change({ target: toggle });

  assert.equal(f.input.value, "");
  assert.equal(f.error.hidden, true);
  assert.equal(f.slot.classList.classes.has("clavaris-logo-field__preview--removing"), true);
});

test("the colour swatch and the hex field stay in step", () => {
  const { listeners } = load();
  const picker = { value: "#f59e0b" };
  const text = { value: "" };
  const container = {
    querySelector: (selector) =>
      selector === "[data-color-picker]" ? picker : selector === "[data-color-text]" ? text : null,
  };
  const pickerEvent = {
    target: { value: "#2563eb", closest: () => container, matches: (s) => s === "[data-color-picker]" },
  };
  const textEvent = (value) => ({
    target: { value, closest: () => container, matches: (s) => s === "[data-color-text]" },
  });

  listeners.input(pickerEvent);
  assert.equal(text.value, "#2563eb");

  listeners.input(textEvent("#112233"));
  assert.equal(picker.value, "#112233");

  // A half-typed or invalid value never reaches the swatch.
  listeners.input(textEvent("#1122"));
  assert.equal(picker.value, "#112233");
  listeners.input(textEvent("blue"));
  assert.equal(picker.value, "#112233");

  // Upper case is accepted and normalised.
  listeners.input(textEvent(" #ABCDEF "));
  assert.equal(picker.value, "#abcdef");
});
