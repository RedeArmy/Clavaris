"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(
  __dirname, "..", "..", "main", "resources", "static", "js", "appearance.js",
);

function run({ search = "", stored = null }) {
  const props = {};
  const store = { value: stored };
  global.window = {
    location: { search },
    sessionStorage: {
      getItem: () => store.value,
      setItem: (_key, value) => { store.value = value; },
    },
  };
  global.document = {
    documentElement: {
      style: { setProperty: (name, value) => { props[name] = value; } },
      setAttribute: (name, value) => { props[name] = value; },
    },
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  return { props, store };
}

test("applies and remembers a valid appearance_bg from the URL", () => {
  const { props, store } = run({ search: "?clientId=x&appearance_bg=%23ECFDF5" });
  assert.equal(props["--clavaris-bg"], "#ecfdf5");
  assert.equal(store.value, "#ecfdf5");
});

test("keeps the colour on a later page whose URL no longer carries it", () => {
  const { props } = run({ stored: "#ecfdf5" });
  assert.equal(props["--clavaris-bg"], "#ecfdf5");
});

test("ignores anything that is not a plain #rrggbb colour", () => {
  const { props, store } = run({ search: "?appearance_bg=red;}body{display:none" });
  assert.equal(props["--clavaris-bg"], undefined);
  assert.equal(store.value, null);
});

test("does nothing when there is neither a parameter nor a remembered colour", () => {
  const { props } = run({});
  assert.deepEqual(props, {});
});

test("a light colour selects the light palette and a dark colour the dark one", () => {
  assert.equal(run({ search: "?appearance_bg=%23ecfdf5" }).props["data-appearance"], "light");
  assert.equal(run({ search: "?appearance_bg=%23111827" }).props["data-appearance"], "dark");
});
