"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(
  __dirname, "..", "..", "main", "resources", "static", "js", "phone-country-picker.js",
);

// Just enough DOM for the picker: nodes that hold children, attributes, classes and listeners.
function node(tag) {
  const listeners = {};
  const classes = new Set();
  const self = {
    tag, listeners, classes, attrs: {}, children: [], dataset: {}, style: {},
    hidden: false, textContent: "", value: "", id: "", className: "",
    classList: {
      add: (name) => classes.add(name),
      remove: (name) => classes.delete(name),
      toggle: (name, on) => (on ? classes.add(name) : classes.delete(name)),
    },
    setAttribute(name, value) { self.attrs[name] = String(value); },
    removeAttribute(name) { delete self.attrs[name]; },
    addEventListener(name, listener) { listeners[name] = listener; },
    // Appending a node that is already a child moves it, as the DOM does.
    append(...items) {
      self.children = self.children.filter((child) => !items.includes(child));
      self.children.push(...items);
    },
    contains: (other) => other === self || self.children.some((child) => child.contains?.(other)),
    focus() { node.focused = self; },
    scrollIntoView() {},
    getBoundingClientRect: () => ({ top: 100, bottom: 140, left: 20 }),
    dispatchEvent(event) { self.dispatched = event; },
    after(...items) { self.followers = items; },
    closest(selector) { return self.className.includes(selector.slice(1)) ? self : null; },
  };
  return self;
}

const OPTIONS = [
  ["", "Code"],
  ["+502", "🇬🇹 +502 Guatemala"],
  ["+1", "🇺🇸 +1 United States"],
  ["+52", "🇲🇽 +52 México"],
];

function load({ selected = 0, restored, withNext = true, withPreview = true } = {}) {
  node.focused = null;
  const select = node("select");
  select.id = "country";
  select.options = OPTIONS.map(([value, text]) => ({ value, text }));
  select.selectedIndex = selected;
  if (restored) { select.dataset.phoneSelected = restored; }
  if (withNext) { select.dataset.phoneNext = "local"; }
  if (withPreview) { select.dataset.phonePreview = "preview"; }
  const local = node("input");
  local.value = "5555 1234";
  const preview = node("p");
  const label = node("label");
  const documentListeners = {};
  global.globalThis.clavarisI18n = undefined;
  global.Event = class { constructor(type) { this.type = type; } };
  global.window = { innerHeight: 800, innerWidth: 1200, addEventListener() {} };
  global.document = {
    readyState: "complete",
    createElement: node,
    createElementNS: (_ns, tag) => node(tag),
    getElementById: (id) => ({ local: withNext ? local : null, preview: withPreview ? preview : null })[id] || null,
    querySelector: () => label,
    querySelectorAll: () => [select],
    addEventListener: (name, listener) => { documentListeners[name] = listener; },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  const [trigger, panel] = select.followers;
  const [search, list, empty, status] = panel.children;
  return { select, trigger, panel, search, list, empty, status, local, preview, label, documentListeners };
}

const key = (search, name, extra = {}) => {
  const event = { key: name, prevented: false, preventDefault() { this.prevented = true; }, stopPropagation() {}, ...extra };
  search.listeners.keydown(event);
  return event;
};
const names = (list) => list.children.filter((row) => !row.hidden).map((row) => row.children[1].textContent);

test("replaces the select with a button and a closed panel", () => {
  const { select, trigger, panel } = load();
  assert.equal(select.hidden, true);
  assert.equal(select.tabIndex, -1);
  assert.equal(trigger.attrs["aria-expanded"], "false");
  assert.equal(panel.hidden, true);
});

test("an enhanced select is not enhanced twice", () => {
  const { select } = load();
  const first = select.followers;
  require(SCRIPT_PATH);
  assert.equal(select.followers, first);
});

test("lists one row per country, with the flag turned into its ISO code", () => {
  const { list } = load();
  assert.deepEqual(list.children.map((row) => row.children[0].textContent), ["GT", "US", "MX"]);
  assert.deepEqual(list.children.map((row) => row.children[2].textContent), ["+502", "+1", "+52"]);
});

test("with nothing chosen the button says Code", () => {
  const { trigger } = load();
  assert.equal(trigger.children[1].textContent, "Code");
  assert.equal(trigger.children[0].hidden, true);
  assert.equal(trigger.attrs["aria-label"], "Country code. Choose");
});

test("a country the server pre-selected shows on the button", () => {
  const { trigger } = load({ restored: "+52" });
  assert.equal(trigger.children[0].textContent, "MX");
  assert.equal(trigger.children[1].textContent, "+52");
  assert.equal(trigger.attrs["aria-label"], "Country code, México +52. Change");
});

test("the label now points at the button", () => {
  const { label, trigger } = load();
  assert.equal(label.attrs.for, "country-trigger");
  assert.equal(trigger.id, "country-trigger");
});

test("clicking the button opens the panel listing every country", () => {
  const { trigger, panel, list, status } = load();
  trigger.listeners.click();
  assert.equal(panel.hidden, false);
  assert.equal(trigger.attrs["aria-expanded"], "true");
  assert.deepEqual(names(list), ["Guatemala", "United States", "México"]);
  assert.equal(status.textContent, "3 countries");
});

test("clicking it again closes the panel and returns focus to it", () => {
  const { trigger, panel } = load();
  trigger.listeners.click();
  trigger.listeners.click();
  assert.equal(panel.hidden, true);
  assert.equal(node.focused, trigger);
});

test("typing a letter on the button opens the search seeded with it", () => {
  const { trigger, search, list } = load();
  trigger.listeners.keydown({ key: "m", preventDefault() {} });
  assert.equal(search.value, "m");
  assert.deepEqual(names(list), ["México", "Guatemala"]);
});

test("searching ignores accents and case", () => {
  const { trigger, search, list } = load();
  trigger.listeners.click();
  search.value = "MEXICO";
  search.listeners.input();
  assert.deepEqual(names(list), ["México"]);
});

test("a dial code finds its country, with or without the plus", () => {
  const { trigger, search, list } = load();
  trigger.listeners.click();
  search.value = "+502";
  search.listeners.input();
  assert.deepEqual(names(list), ["Guatemala"]);
  search.value = "502";
  search.listeners.input();
  assert.deepEqual(names(list), ["Guatemala"]);
});

test("an exact ISO code ranks above a country that merely contains it", () => {
  const { trigger, search, list } = load();
  trigger.listeners.click();
  search.value = "us";
  search.listeners.input();
  assert.equal(names(list)[0], "United States");
});

test("no match shows the empty message and an empty-count status", () => {
  const { trigger, search, empty, status } = load();
  trigger.listeners.click();
  search.value = "zzz";
  search.listeners.input();
  assert.equal(empty.hidden, false);
  assert.equal(status.textContent, "No countries found");
});

test("a single match says so", () => {
  const { trigger, search, status } = load();
  trigger.listeners.click();
  search.value = "guat";
  search.listeners.input();
  assert.equal(status.textContent, "1 country");
});

test("the arrow keys walk the list and wrap around", () => {
  const { trigger, search, list } = load();
  trigger.listeners.click();
  assert.ok(list.children[0].classes.has("is-active"));
  key(search, "ArrowDown");
  assert.ok(list.children[1].classes.has("is-active"));
  key(search, "ArrowUp");
  key(search, "ArrowUp");
  assert.ok(list.children[2].classes.has("is-active"));
  key(search, "ArrowDown");
  assert.ok(list.children[0].classes.has("is-active"));
});

test("Enter chooses the active country, tells the form and moves on to the number", () => {
  const { select, trigger, search, panel, local } = load();
  trigger.listeners.click();
  key(search, "ArrowDown");
  key(search, "Enter");
  assert.equal(select.selectedIndex, 2);
  assert.equal(select.dispatched.type, "change");
  assert.equal(panel.hidden, true);
  assert.equal(node.focused, local);
  assert.equal(trigger.children[1].textContent, "+1");
});

test("clicking a row chooses it", () => {
  const { select, trigger, list } = load();
  trigger.listeners.click();
  list.listeners.click({ target: list.children[2] });
  assert.equal(select.selectedIndex, 3);
});

test("Enter with nothing to choose does nothing", () => {
  const { select, trigger, search } = load();
  trigger.listeners.click();
  search.value = "zzz";
  search.listeners.input();
  key(search, "Enter");
  assert.equal(select.selectedIndex, 0);
});

test("Escape closes the panel and returns focus to the button", () => {
  const { trigger, search, panel } = load();
  trigger.listeners.click();
  key(search, "Escape");
  assert.equal(panel.hidden, true);
  assert.equal(node.focused, trigger);
});

test("Tab closes the panel without stealing focus", () => {
  const { trigger, search, panel } = load();
  trigger.listeners.click();
  key(search, "Tab");
  assert.equal(panel.hidden, true);
});

test("a click outside the picker closes the panel; one inside does not", () => {
  const { trigger, panel, documentListeners } = load();
  trigger.listeners.click();
  documentListeners.click({ target: panel });
  assert.equal(panel.hidden, false);
  documentListeners.click({ target: node("div") });
  assert.equal(panel.hidden, true);
});

test("the preview shows the full number once a country and a number exist", () => {
  const { preview } = load({ restored: "+502" });
  assert.equal(preview.textContent, "Will be saved as +502 5555 1234");
});

test("the preview follows the number as it is typed", () => {
  const { preview, local } = load({ restored: "+502" });
  local.value = "7777";
  local.listeners.input();
  assert.equal(preview.textContent, "Will be saved as +502 7777");
});

test("no preview without a country", () => {
  assert.equal(load().preview.textContent, "");
});

test("the picker works without a number field or a preview", () => {
  const { select, trigger, search } = load({ withNext: false, withPreview: false });
  trigger.listeners.click();
  key(search, "Enter");
  assert.equal(select.selectedIndex, 1);
  assert.equal(node.focused, trigger);
});

test("the panel opens upward when there is no room below the button", () => {
  const { trigger, panel } = load();
  global.window.innerHeight = 200;
  trigger.listeners.click();
  assert.equal(panel.style.top, "auto");
  assert.equal(panel.style.bottom, "106px");
});
