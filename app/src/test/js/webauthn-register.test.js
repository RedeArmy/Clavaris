"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "webauthn-register.js");

// Runs the click handler and lets every promise in its chain settle.
const settle = () => new Promise((resolve) => setImmediate(resolve));

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return { ok, status, json: () => Promise.resolve(body) };
}

function load({ supported = true, withButton = true, responses = [], credential = null } = {}) {
  const requests = [];
  const queue = [...responses];
  const button = {
    hidden: true,
    disabled: false,
    addEventListener(name, listener) { this.click = listener; },
  };
  const error = { hidden: true, textContent: "" };
  const unsupported = { hidden: true };
  const inputs = {
    "webauthn-csrf-token": { value: "tok" },
    "webauthn-csrf-header": { value: "X-CSRF" },
    "passkey-nickname": { value: "Laptop" },
  };
  const location = { reloads: 0, reload() { this.reloads += 1; } };
  global.window = { location };
  if (supported) {
    global.PublicKeyCredential = { parseCreationOptionsFromJSON: (json) => ({ parsed: json }) };
    global.window.PublicKeyCredential = global.PublicKeyCredential;
  } else {
    delete global.PublicKeyCredential;
  }
  Object.defineProperty(global, "navigator", { configurable: true, value: { credentials: { create: (options) => { requests.push(["create", options]); return Promise.resolve(credential); } } } });
  global.fetch = (url, init) => { requests.push([url, init]); return Promise.resolve(queue.shift()); };
  global.document = {
    getElementById: (id) => {
      if (id === "register-passkey-button") { return withButton ? button : null; }
      if (id === "passkey-registration-error") { return error; }
      return id === "passkey-unsupported-notice" ? unsupported : inputs[id] || null;
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { button, error, unsupported, requests, location };
}

const goodCredential = { toJSON: () => ({ id: "cred" }) };

test("reveals the button where passkeys are supported", () => {
  const { button, unsupported } = load();
  assert.equal(button.hidden, false);
  assert.equal(unsupported.hidden, true);
});

test("where they are not, shows the notice and leaves the button hidden", () => {
  const { button, unsupported } = load({ supported: false });
  assert.equal(button.hidden, true);
  assert.equal(unsupported.hidden, false);
});

test("a page without the button wires nothing", () => {
  assert.doesNotThrow(() => load({ withButton: false }));
});

test("registers: sends the nickname and CSRF header, then reloads on 204", async () => {
  const { button, requests, location } = load({
    credential: goodCredential,
    responses: [jsonResponse({ challenge: "c" }), { ok: true, status: 204 }],
  });
  button.click();
  await settle();
  assert.equal(requests[0][0], "passkeys/registration/start");
  assert.equal(requests[0][1].headers["X-CSRF"], "tok");
  assert.deepEqual(requests[1][1].publicKey, { parsed: { challenge: "c" } });
  const finish = JSON.parse(requests[2][1].body);
  assert.equal(finish.nickname, "Laptop");
  assert.equal(JSON.parse(finish.credential).id, "cred");
  assert.equal(location.reloads, 1);
});

test("a failed start shows the generic message and re-enables the button", async () => {
  const { button, error } = load({ responses: [jsonResponse({}, { ok: false, status: 500 })] });
  button.click();
  await settle();
  assert.equal(button.disabled, false);
  assert.equal(error.textContent, "Something went wrong registering your passkey.");
  assert.equal(error.hidden, false);
});

test("the server's own message on finish is shown as given", async () => {
  const { button, error, location } = load({
    credential: goodCredential,
    responses: [
      jsonResponse({}),
      jsonResponse({ error: "That passkey is already registered." }, { ok: false, status: 409 }),
    ],
  });
  button.click();
  await settle();
  assert.equal(error.textContent, "That passkey is already registered.");
  assert.equal(location.reloads, 0);
});
