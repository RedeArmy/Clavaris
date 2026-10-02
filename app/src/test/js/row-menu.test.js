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
  "row-menu.js",
);

function loadScript(documentStub, windowStub) {
  global.document = documentStub;
  global.window = windowStub;
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
}

function documentHarness() {
  const listeners = {};
  return {
    addEventListener(eventName, listener) {
      listeners[eventName] = listener;
    },
    dispatch(eventName, event = {}) {
      listeners[eventName](event);
    },
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
  };
}

function windowHarness() {
  const listeners = {};
  return {
    addEventListener(eventName, listener) {
      listeners[eventName] = listener;
    },
    dispatch(eventName) {
      listeners[eventName]();
    },
  };
}

// A real DOMRect-shaped stub — only the two fields positionPanel actually reads.
function triggerStub(bottom, right) {
  return { getBoundingClientRect: () => ({ bottom, right }) };
}

function openMenuStub(trigger, panel) {
  return {
    open: true,
    querySelector: (selector) =>
      selector === ".clavaris-menu__trigger"
        ? trigger
        : selector === ".clavaris-menu__panel"
          ? panel
          : null,
  };
}

test("scrolling while a menu is open repositions its panel under the trigger's current rect", () => {
  const documentStub = documentHarness();
  const windowStub = windowHarness();
  const panel = { style: {}, offsetWidth: 120 };
  const menu = openMenuStub(triggerStub(240, 300), panel);
  documentStub.querySelector = (selector) => (selector === ".clavaris-menu[open]" ? menu : null);

  loadScript(documentStub, windowStub);
  windowStub.dispatch("scroll");

  assert.equal(panel.style.top, "246px");
  assert.equal(panel.style.left, "180px");
});

test("resizing while a menu is open repositions its panel the same way scrolling does", () => {
  const documentStub = documentHarness();
  const windowStub = windowHarness();
  const panel = { style: {}, offsetWidth: 100 };
  const menu = openMenuStub(triggerStub(500, 400), panel);
  documentStub.querySelector = (selector) => (selector === ".clavaris-menu[open]" ? menu : null);

  loadScript(documentStub, windowStub);
  windowStub.dispatch("resize");

  assert.equal(panel.style.top, "506px");
  assert.equal(panel.style.left, "300px");
});

test("scrolling with no menu open does nothing", () => {
  const documentStub = documentHarness();
  const windowStub = windowHarness();
  documentStub.querySelector = () => null;

  loadScript(documentStub, windowStub);

  assert.doesNotThrow(() => windowStub.dispatch("scroll"));
});

test("the left position never goes negative when the panel is wider than the trigger's own right edge", () => {
  const documentStub = documentHarness();
  const windowStub = windowHarness();
  const panel = { style: {}, offsetWidth: 500 };
  const menu = openMenuStub(triggerStub(100, 50), panel);
  documentStub.querySelector = (selector) => (selector === ".clavaris-menu[open]" ? menu : null);

  loadScript(documentStub, windowStub);
  windowStub.dispatch("scroll");

  assert.equal(panel.style.left, "8px");
});

test("closes every open menu that a click originated outside of", () => {
  const documentStub = documentHarness();
  const windowStub = windowHarness();
  const insideClickMenu = { open: true, contains: () => true };
  const outsideClickMenu = { open: true, contains: () => false };
  documentStub.querySelectorAll = (selector) =>
    selector === ".clavaris-menu[open]" ? [insideClickMenu, outsideClickMenu] : [];

  loadScript(documentStub, windowStub);
  documentStub.dispatch("click", { target: {} });

  assert.equal(insideClickMenu.open, true);
  assert.equal(outsideClickMenu.open, false);
});
