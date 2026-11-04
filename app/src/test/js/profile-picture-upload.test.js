"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "profile-picture-upload.js");

function load({ withPreview = true, readyState = "complete", preFiles = [] } = {}) {
  const listeners = {};
  const classes = {};
  const status = {
    textContent: "",
    classList: { toggle: (name, on) => { classes[name] = on; } },
  };
  const preview = {
    src: "/current.png",
    getAttribute() { return this.src; },
    setAttribute(_name, value) { this.src = value; },
  };
  const submit = { disabled: false };
  const input = { type: "file", value: "x", files: preFiles };
  const form = {
    dataset: { pictureStatus: "status", picturePreview: "preview" },
    querySelector: (selector) => (selector.includes("file") ? input : submit),
  };
  input.closest = () => form;
  class FakeInput {}
  Object.setPrototypeOf(input, FakeInput.prototype);
  const revoked = [];
  let urls = 0;
  global.HTMLInputElement = FakeInput;
  global.URL = { createObjectURL: () => "blob:" + (urls += 1), revokeObjectURL: (url) => revoked.push(url) };
  global.globalThis.clavarisI18n = undefined;
  global.document = {
    readyState,
    addEventListener: (name, listener) => { listeners[name] = listener; },
    getElementById: (id) => (id === "status" ? status : id === "preview" && withPreview ? preview : null),
    querySelectorAll: () => [form],
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  const choose = (file) => {
    input.files = file ? [file] : [];
    listeners.change({ target: input });
  };
  return { listeners, status, preview, submit, input, classes, revoked, choose };
}

const image = (props) => ({ name: "me.png", type: "image/png", size: 2048, ...props });

test("a valid image enables the upload, previews it and says what will happen", () => {
  const { choose, submit, preview, status, classes } = load();
  choose(image());
  assert.equal(submit.disabled, false);
  assert.equal(preview.src, "blob:1");
  assert.equal(status.textContent, "me.png (2 KB) will replace the current picture.");
  assert.equal(classes["clavaris-field-error"], false);
});

test("a file that is not an image type is rejected and the input cleared", () => {
  const { choose, submit, status, input, classes } = load();
  choose(image({ type: "application/pdf" }));
  assert.equal(submit.disabled, true);
  assert.equal(input.value, "");
  assert.equal(status.textContent, "Choose a JPG, PNG, WebP or GIF image.");
  assert.equal(classes["clavaris-field-error"], true);
});

test("an image over 10 MB is rejected with its size", () => {
  const { choose, submit, status } = load();
  choose(image({ size: 12 * 1024 * 1024 }));
  assert.equal(submit.disabled, true);
  assert.equal(status.textContent, "That image is 12.0 MB. The limit is 10 MB.");
});

test("rejecting after a preview puts the current picture back", () => {
  const { choose, preview, revoked } = load();
  choose(image());
  choose(image({ type: "text/plain" }));
  assert.equal(preview.src, "/current.png");
  assert.deepEqual(revoked, ["blob:1"]);
});

test("choosing another valid image releases the previous preview", () => {
  const { choose, revoked } = load();
  choose(image());
  choose(image({ name: "other.png" }));
  assert.deepEqual(revoked, ["blob:1"]);
});

test("clearing the choice disables the upload and empties the message", () => {
  const { choose, submit, status, preview } = load();
  choose(image());
  choose(null);
  assert.equal(submit.disabled, true);
  assert.equal(status.textContent, "");
  assert.equal(preview.src, "/current.png");
});

test("a form with no preview element still validates", () => {
  const { choose, submit } = load({ withPreview: false });
  choose(image());
  assert.equal(submit.disabled, false);
});

test("a change that is not a file input of this form is ignored", () => {
  const { listeners, submit } = load();
  listeners.change({ target: { type: "text" } });
  assert.equal(submit.disabled, true);
});

test("the upload starts disabled until a file is chosen, and again after htmx swaps", () => {
  const { listeners, submit } = load();
  assert.equal(submit.disabled, true);
  submit.disabled = false;
  listeners["htmx:afterSettle"]();
  assert.equal(submit.disabled, true);
});

test("while the page is still loading it primes on DOMContentLoaded", () => {
  const { listeners, submit } = load({ readyState: "loading" });
  submit.disabled = false;
  listeners.DOMContentLoaded();
  assert.equal(submit.disabled, true);
});
