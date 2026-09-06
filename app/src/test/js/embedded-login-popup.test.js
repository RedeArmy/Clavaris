"use strict";

/**
 * ADR-0009 §1: proves embedded-login-popup.js's own DOMContentLoaded wiring against hand-rolled
 * `document`/`window` stubs — no jsdom, no test framework beyond Node's own built-in `node:test`
 * (stable since Node 20, no npm install needed at all). A single ~15-line vanilla script doesn't
 * justify a real DOM-emulation dependency; these stubs are exactly the surface the script actually
 * touches (`document.body.dataset`, `document.addEventListener`, `document.querySelectorAll`,
 * `element.addEventListener`, `window.open`), nothing more.
 *
 * Requires the real shipped file directly (not a copy) via CommonJS `require`, after stubbing
 * `global.document`/`global.window` — the script itself has no import/export of its own (it runs
 * as a plain `<script src=...>` tag in the browser), so this is the only way to exercise it as-is.
 */

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
  "embedded-login-popup.js",
);

function stubLink(href) {
  const handlers = {};
  return {
    href,
    addEventListener(eventName, handler) {
      handlers[eventName] = handler;
    },
    dispatchClick() {
      const event = {
        defaultPrevented: false,
        preventDefault() {
          this.defaultPrevented = true;
        },
      };
      event.currentTarget = { href };
      handlers.click(event);
      return event;
    },
  };
}

// Stubs document/window, then requires the real script fresh (require's own module cache is
// cleared first, since every test needs its own independent DOMContentLoaded registration run
// against that test's own stub document).
function loadScript(documentStub, windowStub) {
  global.document = documentStub;
  global.window = windowStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

test("does nothing when the page was not rendered in modal mode", () => {
  let querySelectorAllCalls = 0;
  const documentStub = {
    body: { dataset: {} },
    addEventListener(eventName, handler) {
      if (eventName === "DOMContentLoaded") {
        handler();
      }
    },
    querySelectorAll() {
      querySelectorAllCalls += 1;
      return [];
    },
  };

  loadScript(documentStub, {});

  assert.equal(querySelectorAllCalls, 0);
});

test("wires a click handler on every social-login link when in modal mode", () => {
  const link = stubLink("https://accounts.google.com/o/oauth2/authorize");
  let queriedSelector = null;
  const documentStub = {
    body: { dataset: { modal: "true" } },
    addEventListener(eventName, handler) {
      if (eventName === "DOMContentLoaded") {
        handler();
      }
    },
    querySelectorAll(selector) {
      queriedSelector = selector;
      return [link];
    },
  };

  loadScript(documentStub, {});

  assert.equal(queriedSelector, "a[data-social-login]");
});

test("a click on a social-login link opens it as a popup instead of navigating the iframe", () => {
  const link = stubLink("https://accounts.google.com/o/oauth2/authorize");
  const documentStub = {
    body: { dataset: { modal: "true" } },
    addEventListener(eventName, handler) {
      if (eventName === "DOMContentLoaded") {
        handler();
      }
    },
    querySelectorAll() {
      return [link];
    },
  };
  let openCall = null;
  const windowStub = {
    open(url, target, features) {
      openCall = { url, target, features };
    },
  };

  loadScript(documentStub, windowStub);
  const event = link.dispatchClick();

  assert.equal(event.defaultPrevented, true, "must never navigate the iframe itself");
  assert.deepEqual(openCall, {
    url: "https://accounts.google.com/o/oauth2/authorize",
    target: "_blank",
    features: "width=480,height=640",
  });
});

test("multiple social-login links each get their own independent click handler", () => {
  const googleLink = stubLink("https://accounts.google.com/o/oauth2/authorize");
  const githubLink = stubLink("https://github.com/login/oauth/authorize");
  const documentStub = {
    body: { dataset: { modal: "true" } },
    addEventListener(eventName, handler) {
      if (eventName === "DOMContentLoaded") {
        handler();
      }
    },
    querySelectorAll() {
      return [googleLink, githubLink];
    },
  };
  const openCalls = [];
  const windowStub = {
    open(url) {
      openCalls.push(url);
    },
  };

  loadScript(documentStub, windowStub);
  googleLink.dispatchClick();
  githubLink.dispatchClick();

  assert.deepEqual(openCalls, [
    "https://accounts.google.com/o/oauth2/authorize",
    "https://github.com/login/oauth/authorize",
  ]);
});
