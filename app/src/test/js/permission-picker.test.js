"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "permission-picker.js");

// The script initialises against `document` when present; node has none, so only the pure helpers
// are exercised here — the DOM wiring is covered by the controller test (markup contract) and a
// real-browser check.
const { tokenize, serialize } = require(SCRIPT_PATH);

test("tokenize splits on newlines and commas, trims, drops blanks and keeps first-seen order", () => {
  assert.deepEqual(tokenize("a:read\n  b:write ,\r\n\n c:delete,,a:read"), ["a:read", "b:write", "c:delete"]);
});

test("tokenize keeps spaces inside a permission so existing data round-trips unchanged", () => {
  assert.deepEqual(tokenize("manage users\nview reports"), ["manage users", "view reports"]);
});

test("tokenize tolerates null, undefined and empty input", () => {
  assert.deepEqual(tokenize(null), []);
  assert.deepEqual(tokenize(undefined), []);
  assert.deepEqual(tokenize(""), []);
});

test("serialize writes one permission per line, the format the server parses", () => {
  assert.equal(serialize(["a:read", "b:write"]), "a:read\nb:write");
  assert.equal(serialize([]), "");
});

test("serialize and tokenize are inverses for a normal permission list", () => {
  const permissions = ["clavaris:workspace:manage_roles", "jobseeker:candidates:review"];
  assert.deepEqual(tokenize(serialize(permissions)), permissions);
});
