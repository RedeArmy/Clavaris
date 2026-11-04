"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "login-submit-guard.js");

const LOCK_KEY = "clavaris_login_submit_lock";

// A localStorage over a plain object; `broken` makes every access throw, as private mode can.
function load({ stored = {}, broken = false, withForm = true } = {}) {
  const store = { ...stored };
  const listeners = {};
  const formListeners = {};
  const notice = { hidden: true };
  const guard = () => { if (broken) { throw new Error("storage blocked"); } };
  global.window = {
    localStorage: {
      getItem: (key) => { guard(); return key in store ? store[key] : null; },
      setItem: (key, value) => { guard(); store[key] = value; },
      removeItem: (key) => { guard(); delete store[key]; },
    },
  };
  global.document = {
    addEventListener: (name, listener) => { listeners[name] = listener; },
    querySelector: () => (withForm
      ? { addEventListener: (name, listener) => { formListeners[name] = listener; } }
      : null),
    getElementById: (id) => (id === "login-submit-guard-notice" ? notice : null),
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  listeners.DOMContentLoaded();
  return { store, notice, submit: () => {
    const event = { prevented: false, preventDefault() { this.prevented = true; } };
    formListeners.submit(event);
    return event;
  } };
}

test("the first submit goes through and takes the lock", () => {
  const { store, submit } = load();
  assert.equal(submit().prevented, false);
  assert.ok(Number(store[LOCK_KEY]) > 0);
});

test("a second submit while the first is in flight is stopped and says so", () => {
  const { notice, submit } = load();
  submit();
  const second = submit();
  assert.equal(second.prevented, true);
  assert.equal(notice.hidden, false);
});

test("a lock older than the window no longer blocks", () => {
  const stale = String(Date.now() - 6000);
  // The page load clears a stale lock, so put it back after loading to reach the TTL check.
  const harness = load();
  harness.store[LOCK_KEY] = stale;
  assert.equal(harness.submit().prevented, false);
});

test("a lock left by an earlier visit is cleared when the page loads", () => {
  const { store } = load({ stored: { [LOCK_KEY]: String(Date.now()) } });
  assert.equal(LOCK_KEY in store, false);
});

test("a lock that is not a number blocks nothing", () => {
  const harness = load();
  harness.store[LOCK_KEY] = "garbage";
  assert.equal(harness.submit().prevented, false);
});

test("blocked storage never breaks the login", () => {
  const { submit } = load({ broken: true });
  assert.equal(submit().prevented, false);
  assert.equal(submit().prevented, false);
});

test("a page without the login form wires nothing", () => {
  assert.doesNotThrow(() => load({ withForm: false }));
});
