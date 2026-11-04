"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "webauthn-login.js");

// Runs the click handler and lets every promise in its chain settle.
const settle = () => new Promise((resolve) => setImmediate(resolve));

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return { ok, status, json: () => Promise.resolve(body) };
}

function load({ supported = true, withButton = true, responses = [], assertion = null } = {}) {
  const requests = [];
  const queue = [...responses];
  const button = {
    hidden: true,
    disabled: false,
    attrs: { "data-client-id": "web", "data-redirect-url": "https://app.test/cb" },
    getAttribute(name) { return this.attrs[name] ?? null; },
    addEventListener(name, listener) { this.click = listener; },
  };
  const error = { hidden: true, textContent: "" };
  const inputs = {
    "webauthn-csrf-token": { value: "tok" },
    "webauthn-csrf-header": { value: "X-CSRF" },
  };
  global.window = { location: { href: "" } };
  if (supported) {
    global.PublicKeyCredential = { parseRequestOptionsFromJSON: (json) => ({ parsed: json }) };
    global.window.PublicKeyCredential = global.PublicKeyCredential;
  } else {
    delete global.PublicKeyCredential;
  }
  Object.defineProperty(global, "navigator", { configurable: true, value: { credentials: { get: (options) => { requests.push(["get", options]); return Promise.resolve(assertion); } } } });
  global.fetch = (url, init) => { requests.push([url, init]); return Promise.resolve(queue.shift()); };
  global.document = {
    getElementById: (id) => {
      if (id === "passkey-signin-button") { return withButton ? button : null; }
      return id === "passkey-signin-error" ? error : inputs[id] || null;
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { button, error, requests };
}

const goodAssertion = { toJSON: () => ({ id: "cred" }) };

test("reveals the button only where the browser supports passkeys", () => {
  assert.equal(load().button.hidden, false);
  assert.equal(load({ supported: false }).button.hidden, true);
});

test("a page without the button wires nothing", () => {
  assert.doesNotThrow(() => load({ withButton: false }));
});

test("signs in: unwraps publicKey, sends the CSRF header, redirects to the answer", async () => {
  const { button, requests } = load({
    assertion: goodAssertion,
    responses: [
      jsonResponse({ publicKey: { challenge: "c" } }),
      jsonResponse({ redirectTo: "/done" }),
    ],
  });
  button.click();
  await settle();
  assert.equal(requests[0][0], "login/webauthn/start");
  assert.equal(requests[0][1].headers["X-CSRF"], "tok");
  assert.deepEqual(requests[1][1].publicKey, { parsed: { challenge: "c" } });
  const finish = JSON.parse(requests[2][1].body);
  assert.equal(finish.clientId, "web");
  assert.equal(finish.redirectUrl, "https://app.test/cb");
  assert.equal(JSON.parse(finish.credential).id, "cred");
  assert.equal(global.window.location.href, "/done");
});

test("an internal error code shows the generic message and re-enables the button", async () => {
  const { button, error } = load({ responses: [jsonResponse({}, { ok: false, status: 500 })] });
  button.click();
  await settle();
  assert.equal(button.disabled, false);
  assert.equal(error.hidden, false);
  assert.equal(error.textContent, "Something went wrong signing in with your passkey.");
});

test("the server's own message is shown as given", async () => {
  const { button, error } = load({
    assertion: goodAssertion,
    responses: [
      jsonResponse({ publicKey: {} }),
      jsonResponse({ error: "This passkey is not registered." }, { ok: false, status: 401 }),
    ],
  });
  button.click();
  await settle();
  assert.equal(error.textContent, "This passkey is not registered.");
});

test("a finish failure without a message falls back to the generic one", async () => {
  const { button, error } = load({
    assertion: goodAssertion,
    responses: [jsonResponse({ publicKey: {} }), jsonResponse({}, { ok: false, status: 400 })],
  });
  button.click();
  await settle();
  assert.equal(error.textContent, "Something went wrong signing in with your passkey.");
});
